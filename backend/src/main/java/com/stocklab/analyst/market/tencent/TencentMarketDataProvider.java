package com.stocklab.analyst.market.tencent;

import com.stocklab.analyst.common.constant.CacheNames;
import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.MarketDataUnavailableException;
import com.stocklab.analyst.market.StockCodes;
import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.IndexSnapshot;
import com.stocklab.analyst.market.model.MarketPeriod;
import com.stocklab.analyst.market.model.Mover;
import com.stocklab.analyst.market.model.MoverMetric;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.market.model.Sector;
import com.stocklab.analyst.market.model.StockBrief;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 腾讯公开行情接口实现（真实数据，无需 API Key）。
 *
 * <h2>为什么需要它</h2>
 * 主数据源东方财富对高频访问有 IP 级风控（2026-09-19、2026-09-21 两次实测：
 * 服务端直接断开连接，{@code HTTP/1.1 header parser received no bytes}，
 * 连 curl 都会被掐，不是本工程调用方式的问题）。一旦东财被风控，
 * 原先的降级链只有「东财 → mock」两级，mock 只内置少量演示标的，
 * 用户查询任意真实股票都会得到「演示数据源未收录该股票代码」——
 * 系统名义上没挂，实际上核心功能不可用。
 *
 * <p>本类在两者之间插入一级<b>真实数据源</b>：东财被风控时，
 * 报价、K 线、指数继续由腾讯提供真实数据，只有东财独有的能力
 * （全市场搜索、涨跌/成交额榜单）才落到演示数据。
 *
 * <h2>上游接口与数据形态</h2>
 * <ul>
 *   <li>实时报价：{@code GET https://qt.gtimg.cn/q=sh600019} —— GBK 编码文本，
 *       {@code ~} 分隔的扁平字段（与东财的 JSON 字段码完全不同的两套体系，
 *       字段下标见 {@link #parseQuote}）</li>
 *   <li>日/周/月 K 线：{@code GET web.ifzq.gtimg.cn/appstock/app/fqkline/get}，
 *       前复权 JSON，行结构为 {@code [日期, 开, 收, 高, 低, 量(手)]}——
 *       注意第 2、3 项是<b>收、高</b>而不是「高、收」，顺序与东财不同</li>
 *   <li>分时：{@code GET web.ifzq.gtimg.cn/appstock/app/minute/query}，
 *       每项 {@code HHMM 价格 累计量(手) 累计额(元)}，量额需要自行差分</li>
 *   <li>指数：{@code qt.gtimg.cn/q=s_sh000001,...} 简版格式</li>
 *   <li>行业板块：{@code proxy.finance.qq.com/cgi/cgi-bin/rank/pt/getRank}，
 *       31 个一级行业的涨跌幅、涨跌家数与领涨股（<b>UTF-8 JSON</b>，
 *       与本类其它 GBK 文本接口不是同一套解码路径）</li>
 * </ul>
 *
 * <h2>能力边界（如实声明）</h2>
 * 腾讯行情接口（内部接口，未获授权）<b>不提供</b>全市场模糊搜索与
 * 涨跌/成交额<b>个股</b>榜单，这两个方法抛 {@link MarketDataUnavailableException}，
 * 由降级路由交给下一级（搜索在东财不可用时落 mock 内置清单）。
 * 这不是缺陷而是一种诚实：与其在这里塞一个只能搜到内置清单的假搜索，
 * 不如让路由如实选择真正能服务的源。
 *
 * <p>板块榜单<b>是</b>例外：本源提供一级行业板块榜，而且口径比东财的
 * 496 个混层级板块更适合作榜单，因此板块操作的默认优先链是腾讯在前
 * （见 {@link #getSectors(int)}）。
 *
 * <h2>两个实现层面的坑，本类已处理</h2>
 * <ol>
 *   <li><b>GBK 编码</b>：响应头是 {@code text/html; charset=GBK}，股票名是中文。
 *       不依赖消息转换器对响应头 charset 的处理，直接以字节接收后按 GBK 解码，
 *       避免任何一层默认 ISO-8859-1 导致名称乱码。</li>
 *   <li><b>北交所不支持</b>：腾讯行情不覆盖北交所代码段，
 *       解析不出 qt 代码时抛「不可用」而不是「查无此股」——
 *       前者是「这个源服务不了」（应降级），后者是「标的不存在」（应 404），
 *       混淆两者会让真实存在的北交所股票被误判为 404。</li>
 * </ol>
 */
public class TencentMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(TencentMarketDataProvider.class);

    private static final String HOST_QUOTE = "https://qt.gtimg.cn";
    private static final String HOST_KLINE = "https://web.ifzq.gtimg.cn";

    /** 板块榜单接口所在域（与行情/K 线不同域，且返回 UTF-8 JSON 而非 GBK 文本）。 */
    private static final String HOST_RANK = "https://proxy.finance.qq.com";

    /**
     * 板块榜单单次拉取的条数。
     *
     * <p><b>接口没有可用的排序参数</b>：{@code sort_type} 传 {@code price} 时返回顺序
     * 与涨跌幅无关（实测 2026-10-21：第一页是 -1.65 / -1.65 / -0.11 / -3.67 / +0.79），
     * 传其它值（{@code zdf}、{@code zsz} 等）直接返回错误码与空列表。
     * 因此只能<b>把全量拿回来自己排序</b>——一级行业共 31 个，一次请求即可覆盖，
     * 成本可以忽略。这里的 100 是「全量 + 余量」，不是「分页大小」。
     */
    private static final int SECTOR_FETCH_COUNT = 100;

    /** 板块榜单对外返回的条数上限，与个股榜单保持一致（前端最多展示 20 条）。 */
    private static final int SECTOR_MAX_LIMIT = 20;

    private static final Charset GBK = Charset.forName("GBK");

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 上游时间字段格式：{@code 20260921161459}。 */
    private static final DateTimeFormatter UPSTREAM_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 首页/指数条展示的主要指数：qt 代码 -> 展示用带后缀代码（与东财保持一致）。
     *  必须保持插入顺序：指数接口按这里的顺序批量请求与解析，前端卡片顺序随之固定。 */
    private static final Map<String, String> INDEX_QT_CODES;
    private static final String INDEX_QT_PARAM;

    static {
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("sh000001", "000001.SH");
        ordered.put("sz399001", "399001.SZ");
        ordered.put("sz399006", "399006.SZ");
        ordered.put("sh000688", "000688.SH");
        ordered.put("sh000300", "000300.SH");
        INDEX_QT_CODES = Collections.unmodifiableMap(ordered);
        INDEX_QT_PARAM = INDEX_QT_CODES.keySet().stream()
                .map(code -> "s_" + code)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TencentMarketDataProvider(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "tencent";
    }

    @Override
    public boolean available() {
        // 无本地状态依赖，随时具备服务能力
        return true;
    }

    // ==================================================================
    // 实时报价
    // ==================================================================

    /**
     * 查询实时报价。缓存语义与东财实现一致：3 秒 TTL 折叠并发重复请求。
     */
    @Override
    @Cacheable(cacheNames = CacheNames.QUOTE, key = "#code", sync = true)
    public Quote getQuote(String code) {
        String qtCode = requireQtCode(code);
        String body = fetchText(HOST_QUOTE + "/q=" + qtCode, "实时报价", code);
        Quote quote = parseQuote(body, qtCode);
        if (quote == null) {
            // 上游对不存在的代码返回空数据而非错误，必须显式转成 404 语义
            throw new BizException(ErrorCode.QUOTE_NOT_FOUND, "未找到股票代码 " + code);
        }
        return quote;
    }

    /**
     * 解析实时报价响应。
     *
     * <p>响应形态：{@code v_sh600019="1~宝钢股份~600019~5.78~..."}，{@code ~} 分隔。
     * 关键字段下标（实测 2026-09-21，宝钢股份）：
     * <pre>
     *   1 名称 / 2 代码 / 3 现价 / 4 昨收 / 5 今开 / 6 成交量(手)
     *  30 时间(yyyyMMddHHmmss) / 31 涨跌额 / 32 涨跌幅 / 33 最高 / 34 最低
     *  37 成交额(万元) / 38 换手率
     * </pre>
     *
     * <p>上游对不存在的代码返回空串或字段不足，此时返回 {@code null}
     * 由调用方转成 {@code QUOTE_NOT_FOUND}，而不是在这里编一个默认值。
     *
     * <p>包级可见：便于用真实响应样本做单元测试，不需要起本地 HTTP 服务。
     */
    static Quote parseQuote(String body, String qtCode) {
        String payload = extractQuoted(body, "v_" + qtCode + "=");
        if (payload == null) {
            return null;
        }
        String[] f = payload.split("~", -1);
        if (f.length <= 38) {
            return null;
        }

        double price = parseDouble(f[3], 0);
        double prevClose = parseDouble(f[4], 0);
        double open = parseDouble(f[5], 0);
        // 涨跌额/幅直接取上游值（f[31]/f[32]），停牌时上游可能给 0，
        // 与东财把 "-" 解析成 0 的行为保持一致
        double change = parseDouble(f[31], prevClose == 0 ? 0 : price - prevClose);
        double changePct = parseDouble(f[32], 0);
        double high = parseDouble(f[33], price);
        double low = parseDouble(f[34], price);
        long volume = (long) parseDouble(f[6], 0);
        double turnover = parseDouble(f[38], 0);
        // 成交额上游是「万元」，Quote 的口径是「元」
        Double amount = f[37].isBlank() ? null : parseDouble(f[37], 0) * 10_000;
        long ts = parseUpstreamTimestamp(f[30]);

        String sixDigit = StockCodes.isSixDigitCode(f[2]) ? f[2] : qtCode.substring(2);
        return new Quote(
                sixDigit,
                f[1].isBlank() ? sixDigit : f[1],
                StockCodes.inferExchange(sixDigit) != null
                        ? StockCodes.inferExchange(sixDigit).display()
                        : qtCode.substring(0, 2).toUpperCase(),
                price,
                change,
                changePct,
                open,
                high,
                low,
                prevClose,
                volume,
                turnover,
                amount,
                ts,
                marketCapFrom(f),
                // 延迟声明由容灾链路统一盖章，这里固定 0（本源无自带延迟）
                0);
    }

    /**
     * 解析腾讯返回的总市值。
     *
     * <p>上游在索引 45 给出总市值，单位是「亿元」，而 {@link Quote#marketCap}
     * 的口径是「元」，因此这里做 ×1e8 换算。口径不统一是本类最容易出错的地方：
     * 成交额在索引 37 是「万元」，总市值却是「亿元」——两处单位不同，
     * 照抄其中一个必然错一个数量级。
     *
     * <p>字段缺失或不是数字时返回 {@code null}，前端渲染 {@code --}。
     * 不用 0 兜底：0 会被显示成「总市值 0 元」，看起来像数据，其实是缺失。
     */
    private static Double marketCapFrom(String[] f) {
        if (f.length <= 45) {
            return null;
        }
        String raw = f[45];
        if (raw == null || raw.isBlank()) {
            return null;
        }
        double hundredMillion = parseDouble(raw, Double.NaN);
        if (!Double.isFinite(hundredMillion) || hundredMillion <= 0) {
            return null;
        }
        return hundredMillion * 100_000_000d;
    }

    // ==================================================================
    // K 线
    // ==================================================================

    /**
     * 查询 K 线序列。
     *
     * <p>本源支持日/周/月（前复权）与当日分时；分钟级 K 线的接口形态
     * 与日线差异较大且当前前端没有入口，如实抛「不可用」交给降级路由，
     * 不为了「看起来完整」而实现一条没人验证过的解析路径。
     */
    @Override
    @Cacheable(cacheNames = CacheNames.BARS, key = "#code + ':' + #period.name() + ':' + #limit", sync = true)
    public List<Bar> getBars(String code, MarketPeriod period, int limit) {
        String qtCode = requireQtCode(code);
        return switch (period) {
            case DAILY, WEEKLY, MONTHLY -> fetchDailyKline(qtCode, period, limit);
            case TREND -> fetchTrend(qtCode, limit);
            // 分钟级、季 K、年 K 一并拒绝。措辞不能写死成「不提供分钟级」——
            // 季/年走同一个分支，那样报错文案会指错原因，排查时白绕一圈
            default -> throw new MarketDataUnavailableException(
                    "腾讯源不提供该周期 K 线（period=" + period.name() + " " + code + "）");
        };
    }

    /** 日/周/月 K 线：前复权 JSON，{@code qfqday / qfqweek / qfqmonth} 三种响应键。 */
    private List<Bar> fetchDailyKline(String qtCode, MarketPeriod period, int limit) {
        String bucket = switch (period) {
            case DAILY -> "day";
            case WEEKLY -> "week";
            default -> "month";
        };
        String url = HOST_KLINE + "/appstock/app/fqkline/get"
                + "?param=" + qtCode + "," + bucket + ",,," + limit + ",qfq";

        JsonNode root = fetchJson(url, "K线", qtCode);
        return parseKline(root, qtCode, "qfq" + bucket);
    }

    /**
     * 解析日/周/月 K 线响应。
     *
     * <p>行结构 {@code [日期, 开, 收, 高, 低, 量(手), ...]}：第 2 项是<b>收盘</b>、
     * 第 3 项才是最高价，与东财的「开收高低」位置不同但恰好逐位对齐——
     * 两家的字符串都是 {@code 开,收,高,低,量} 顺序，这里按语义显式取下标，
     * 不依赖「恰好对齐」的巧合。
     *
     * <p>前复权数据里除权日会多出第 7 个元素（分红信息对象），解析时跳过非文本项。
     * 上游不提供逐根昨收，与 Bar 契约一致：首根 {@code prevClose=null}，
     * 后续每根用前一根收盘价回填。
     *
     * <p>包级可见以便用真实响应样本直接做单测。
     */
    static List<Bar> parseKline(JsonNode root, String qtCode, String responseKey) {
        JsonNode rows = root.path("data").path(qtCode).path(responseKey);
        if (!rows.isArray() || rows.isEmpty()) {
            return List.of();
        }

        Double runningPrevClose = null;
        List<Bar> bars = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 6 || !row.get(0).isString()) {
                continue;
            }
            double close = parseDouble(row.get(2).asString(), 0);
            bars.add(new Bar(
                    row.get(0).asString(),
                    parseDouble(textOrNull(row, 1), close),
                    parseDouble(textOrNull(row, 3), close),
                    parseDouble(textOrNull(row, 4), close),
                    close,
                    (long) parseDouble(textOrNull(row, 5), 0),
                    null,
                    runningPrevClose));
            runningPrevClose = close;
        }
        return Collections.unmodifiableList(bars);
    }

    /** 当日分时：量额是累计值，需要差分；价格单点无 OHLC，四价同值。 */
    private List<Bar> fetchTrend(String qtCode, int limit) {
        String url = HOST_KLINE + "/appstock/app/minute/query?code=" + qtCode;
        JsonNode root = fetchJson(url, "分时", qtCode);
        List<Bar> bars = parseTrend(root, qtCode);
        if (bars.size() > limit) {
            return Collections.unmodifiableList(new ArrayList<>(bars.subList(bars.size() - limit, bars.size())));
        }
        return bars;
    }

    /**
     * 解析分时响应。
     *
     * <p>响应里 {@code data.{qt}.data.date} 是交易日（{@code 20260921}），
     * {@code data.data} 每项形如 {@code "0930 5.76 2177 1253952.00"}——
     * 依次为时间、价格、<b>累计</b>成交量（手）、<b>累计</b>成交额（元）。
     * 单根 K 线的量额由相邻两项差分得到，首根的量额就是累计值本身。
     *
     * <p>分时点只有单一成交价，没有真实的开高低；与东财 trends2 的处理一致
     * （其每个分时点四价同值），这里同样以价格填充四价，保证下游指标计算
     * 与前端绘图拿到的形态一致。
     *
     * <p>包级可见以便用真实响应样本直接做单测。
     */
    static List<Bar> parseTrend(JsonNode root, String qtCode) {
        JsonNode dataNode = root.path("data").path(qtCode).path("data");
        JsonNode rows = dataNode.path("data");
        String tradeDate = dataNode.path("date").asString("");
        if (!rows.isArray() || rows.isEmpty() || tradeDate.length() != 8) {
            return List.of();
        }
        String datePrefix = tradeDate.substring(0, 4) + "-" + tradeDate.substring(4, 6)
                + "-" + tradeDate.substring(6, 8);

        List<Bar> bars = new ArrayList<>(rows.size());
        long prevCumVolume = 0;
        double prevCumAmount = 0;
        Double prevPrice = null;
        for (JsonNode row : rows) {
            if (!row.isString()) {
                continue;
            }
            String[] parts = row.asString().trim().split("\\s+");
            if (parts.length < 3) {
                continue;
            }
            double price = parseDouble(parts[1], 0);
            long cumVolume = (long) parseDouble(parts[2], 0);
            double cumAmount = parts.length > 3 ? parseDouble(parts[3], 0) : 0;

            bars.add(new Bar(
                    datePrefix + " " + formatMinute(parts[0]),
                    price,
                    price,
                    price,
                    price,
                    Math.max(0, cumVolume - prevCumVolume),
                    Math.max(0, cumAmount - prevCumAmount),
                    prevPrice));
            prevCumVolume = cumVolume;
            prevCumAmount = cumAmount;
            prevPrice = price;
        }
        return Collections.unmodifiableList(bars);
    }

    // ==================================================================
    // 指数
    // ==================================================================

    @Override
    @Cacheable(cacheNames = CacheNames.QUOTE, key = "'tencent:indices'", sync = true)
    public List<IndexSnapshot> getIndices() {
        String body = fetchText(HOST_QUOTE + "/q=" + INDEX_QT_PARAM, "指数", INDEX_QT_PARAM);
        List<IndexSnapshot> result = parseIndices(body);
        if (result.isEmpty()) {
            // 全空说明上游拒绝了服务（正常情况下至少上证指数有值），
            // 抛不可用交给路由降级，而不是返回空列表假装正常
            throw new MarketDataUnavailableException("腾讯指数接口返回空数据");
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 解析指数简版响应。
     *
     * <p>每行 {@code v_s_sh000001="1~上证指数~000001~3949.91~38.04~0.97~..."}，
     * 下标 3 点位 / 4 涨跌 / 5 涨跌幅。包级可见以便单测。
     */
    static List<IndexSnapshot> parseIndices(String body) {
        List<IndexSnapshot> result = new ArrayList<>(INDEX_QT_CODES.size());
        for (Map.Entry<String, String> entry : INDEX_QT_CODES.entrySet()) {
            String payload = extractQuoted(body, "v_s_" + entry.getKey() + "=");
            if (payload == null) {
                continue;
            }
            String[] f = payload.split("~", -1);
            if (f.length < 6) {
                continue;
            }
            result.add(new IndexSnapshot(
                    entry.getValue(),
                    f[1],
                    parseDouble(f[3], 0),
                    parseDouble(f[4], 0),
                    parseDouble(f[5], 0)));
        }
        return result;
    }

    // ==================================================================
    // 板块榜单
    // ==================================================================

    /**
     * 板块榜单：按涨跌幅降序的<b>一级行业</b>。
     *
     * <h2>为什么板块榜默认以腾讯为准，而不是以东财为准</h2>
     * 东财行业板块接口（{@code m:90+t:2}）返回 <b>496</b> 个板块，把一级、二级、
     * 三级混在同一个列表里，而响应中<b>没有任何字段标明层级</b>。直接按涨跌幅
     * 排序得到的榜单是这样的（2026-10-21 实测）：
     * <pre>
     *   广告媒体 +6.85%（仅 3 只成分股）   其他数字媒体 +6.61%（2 只）
     *   电视广播Ⅲ +3.26% 与 电视广播Ⅱ +3.26% 并列出现
     *   林业Ⅲ 与 林业Ⅱ、IT服务Ⅲ 与 IT服务Ⅱ 同理
     * </pre>
     * 两个后果：榜单头部被「只有两三只票」的微板块长期占据，且同一板块以
     * 不同层级重复出现。这对「资金在往哪个方向去」没有解释力——两只票涨停
     * 就能让一个板块 +6.6%。
     *
     * <p>腾讯这里给的是 31 个一级行业（食品饮料 / 电子 / 电力设备 …），
     * 口径统一、成分股规模相当，天然可比。所以板块榜的默认优先链是
     * {@code tencent → eastmoney}（{@code app.market-data.sector-chain}）。
     * 它<b>只影响这一个操作</b>：报价、K 线、搜索仍按主链走——
     * 全局链顺序不该为一个操作的口径问题被改掉。
     *
     * <h2>上游字段</h2>
     * <pre>
     *   name  板块名 / zdf 涨跌幅 / zgb 涨跌家数（"37/122" = 涨 37 家、跌 122 家）
     *   lzg   领涨股：name 名称 / code 带市场前缀（sh605388）/ zdf 涨跌幅
     *   total 板块总数（行业板块恒为 31）
     * </pre>
     * {@code zgb} / {@code lzg} 都可能缺失，此时如实给 {@code null}，
     * 由前端渲染「—」——补 0 会得到「0 / 0」这种看起来正常其实无意义的比例。
     */
    @Override
    @Cacheable(cacheNames = CacheNames.SECTORS, key = "'tencent:' + #limit", sync = true)
    public List<Sector> getSectors(int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), SECTOR_MAX_LIMIT);
        String url = HOST_RANK + "/cgi/cgi-bin/rank/pt/getRank"
                + "?board_type=hy&sort_type=price&direct=down"
                + "&offset=0&count=" + SECTOR_FETCH_COUNT;

        JsonNode root = fetchJsonUtf8(url, "板块榜单", "hy");
        List<Sector> result = parseSectorRank(root);
        if (result.isEmpty()) {
            // 空列表意味着「上游这次没给」，抛不可用交给路由降级到下一级，
            // 而不是返回空集合让前端以为「今天没有板块数据」
            throw new MarketDataUnavailableException("腾讯板块榜单返回空数据");
        }
        return result.size() > effectiveLimit
                ? List.copyOf(result.subList(0, effectiveLimit))
                : result;
    }

    /**
     * 解析板块榜单响应并按涨跌幅降序排序。
     *
     * <p>包级可见，便于用真实响应样本直接做单测（不需要起本地 HTTP 服务）。
     *
     * <p>三处刻意的取舍：
     * <ul>
     *   <li><b>缺涨跌幅的条目直接丢弃</b>，不是当 0 参与排序——把缺数据的板块
     *       放进涨幅榜，会被读成「今天平盘」，那是编造信息；</li>
     *   <li><b>排序在这里做</b>：上游顺序与涨跌幅无关（见 {@link #SECTOR_FETCH_COUNT}），
     *       前端只是消费方，不该被迫知道「这个榜单其实没排序」；</li>
     *   <li><b>并列名次保持上游顺序</b>：{@code List#sort} 是稳定排序，
     *       涨跌幅相同时不打乱同分板块的相对位置，结果可复现。</li>
     * </ul>
     */
    static List<Sector> parseSectorRank(JsonNode root) {
        JsonNode list = root == null ? null : root.path("data").path("rank_list");
        if (list == null || !list.isArray() || list.isEmpty()) {
            return List.of();
        }
        List<Sector> parsed = new ArrayList<>(list.size());
        for (JsonNode item : list) {
            String code = textOrNull(item, "code");
            String name = textOrNull(item, "name");
            Double changePct = readNullableDouble(item, "zdf");
            if (code == null || name == null || changePct == null) {
                continue;
            }
            Integer[] breadth = parseBreadth(textOrNull(item, "zgb"));
            JsonNode leader = item.path("lzg");
            parsed.add(new Sector(
                    code,
                    name,
                    changePct,
                    breadth[0],
                    breadth[1],
                    textOrNull(leader, "name"),
                    stripMarketPrefix(textOrNull(leader, "code")),
                    readNullableDouble(leader, "zdf")));
        }
        parsed.sort(Comparator.comparingDouble(Sector::changePct).reversed());
        return Collections.unmodifiableList(parsed);
    }

    /** {@code "37/122"} → {37, 122}；形态不符时返回 {null, null}，不猜。 */
    private static Integer[] parseBreadth(String raw) {
        if (raw == null) {
            return new Integer[] {null, null};
        }
        String[] parts = raw.trim().split("/");
        if (parts.length != 2) {
            return new Integer[] {null, null};
        }
        return new Integer[] {parseNullableInt(parts[0]), parseNullableInt(parts[1])};
    }

    private static Integer parseNullableInt(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "-".equals(trimmed)) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 领涨股代码去掉市场前缀：{@code sh605388} → {@code 605388}。
     *
     * <p>只接受 6 位 A 股代码：上游偶尔给空串或 {@code "-"}，
     * 交 {@code null} 比交半截字符串安全——前端拿到半截代码会拼出一个
     * 请求不到任何东西的链接，看起来像「链接坏了」而不是「上游没给」。
     */
    static String stripMarketPrefix(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        String code = trimmed.length() > 2
                && (trimmed.startsWith("sh") || trimmed.startsWith("sz") || trimmed.startsWith("bj"))
                ? trimmed.substring(2)
                : trimmed;
        return StockCodes.isSixDigitCode(code) ? code : null;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        if (!value.isString()) {
            return null;
        }
        String raw = value.asString().trim();
        return raw.isEmpty() || "-".equals(raw) ? null : raw;
    }

    private static Double readNullableDouble(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isString()) {
            String raw = value.asString().trim();
            if (raw.isEmpty() || "-".equals(raw)) {
                return null;
            }
            try {
                return Double.valueOf(raw);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    // ==================================================================
    // 能力边界：如实声明不提供的能力
    // ==================================================================

    /**
     * 全市场搜索本源不提供。
     *
     * <p>抛「不可用」而非返回空列表：空列表意味着「搜索完了没有结果」，
     * 而真实语义是「这个源做不了搜索」，应由路由交给下一级处理。
     * 东财主源健康时它的搜索走本地全 A 清单（不请求上游），
     * 本方法只在东财也失效时才会被调用。
     */
    @Override
    public List<StockBrief> search(String keyword) {
        throw new MarketDataUnavailableException("腾讯源不提供全市场搜索");
    }

    /** 涨跌/成交额榜单同搜索：上游无此能力，交给路由降级。 */
    @Override
    public List<Mover> getTopMovers(MoverMetric metric, boolean ascending, int limit) {
        throw new MarketDataUnavailableException("腾讯源不提供市场榜单");
    }

    /** 无本地缓存需要预热。 */
    @Override
    public void warmup() {
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 6 位代码 -> 腾讯 qt 代码（{@code sh600019} 形态）。
     *
     * <p>北交所代码段腾讯行情不覆盖：这属于「本源服务不了该标的」，
     * 抛 {@link MarketDataUnavailableException} 交给降级路由，
     * <b>不能</b>抛 {@code QUOTE_NOT_FOUND}——那会把真实存在的股票误判成 404，
     * 且按路由契约「查无此股不降级」，降级链会被直接打断。
     */
    private String requireQtCode(String code) {
        StockCodes.ParsedCode parsed = StockCodes.parse(code);
        if (parsed == null) {
            throw new BizException(ErrorCode.QUOTE_NOT_FOUND, "未找到股票代码 " + code);
        }
        return switch (parsed.exchange()) {
            case SH -> "sh" + parsed.code();
            case SZ -> "sz" + parsed.code();
            case BJ -> throw new MarketDataUnavailableException(
                    "腾讯源不提供北交所行情（" + parsed.code() + "）");
        };
    }

    /**
     * 发起 GET 并以 GBK 解码响应。
     *
     * <p>显式按字节接收再解码：该上游返回 {@code text/html; charset=GBK}，
     * 任何一层消息转换器按默认字符集（ISO-8859-1）处理都会把股票名变成乱码。
     */
    private String fetchText(String url, String action, String subject) {
        return fetchText(url, action, subject, GBK);
    }

    /** 指定字符集的文本拉取。GBK 只属于 {@code qt.gtimg.cn}，不要扩散到 JSON 接口。 */
    private String fetchText(String url, String action, String subject, Charset charset) {
        return new String(fetchBytes(url, action, subject), charset);
    }

    private byte[] fetchBytes(String url, String action, String subject) {
        byte[] bytes;
        try {
            bytes = restClient.get().uri(URI.create(url)).retrieve().body(byte[].class);
        } catch (RuntimeException ex) {
            throw new MarketDataUnavailableException(
                    "行情上游请求失败（" + action + " " + subject + "）", ex);
        }
        if (bytes == null || bytes.length == 0) {
            throw new MarketDataUnavailableException("行情上游返回空响应（" + action + " " + subject + "）");
        }
        return bytes;
    }

    private JsonNode fetchJson(String url, String action, String subject) {
        String body = fetchText(url, action, subject);
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException ex) {
            throw new MarketDataUnavailableException(
                    "行情上游响应不是合法 JSON（" + action + " " + subject + "）", ex);
        }
    }

    /**
     * 按 UTF-8 拉取并解析 JSON。
     *
     * <p><b>不能复用 {@link #fetchJson}</b>：那条路径固定按 GBK 解码，
     * 因为 {@code qt.gtimg.cn} 是 GBK 文本协议；而板块接口返回的是
     * {@code Content-Type: application/json}。按 GBK 解不会报错，
     * 只会把「食品饮料」安静地变成乱码——接口照样 200，这类错误最难发现。
     */
    private JsonNode fetchJsonUtf8(String url, String action, String subject) {
        byte[] bytes = fetchBytes(url, action, subject);
        try {
            return objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            throw new MarketDataUnavailableException(
                    "行情上游响应不是合法 JSON（" + action + " " + subject + "）", ex);
        }
    }

    /**
     * 从多行文本中取出 {@code variable="..."} 的引号内容。
     *
     * <p>腾讯报价接口的响应是 JS 变量赋值文本而非 JSON，
     * 逐行找 {@code v_sh600019="} 前缀后截到下一个引号，比正则
     * 更直白，也避免了转义场景下的边界错误。
     * 找不到该变量时返回 {@code null}（调用方决定意味着什么）。
     */
    static String extractQuoted(String body, String variablePrefix) {
        if (body == null) {
            return null;
        }
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(variablePrefix)) {
                continue;
            }
            int start = trimmed.indexOf('"');
            int end = trimmed.lastIndexOf('"');
            if (start < 0 || end <= start) {
                return "";
            }
            return trimmed.substring(start + 1, end);
        }
        return null;
    }

    /** 上游时间字段（{@code 20260921161459}）转 epoch 秒；解析失败回退当前时间。 */
    private static long parseUpstreamTimestamp(String raw) {
        try {
            if (raw != null && raw.length() == 14) {
                return LocalDateTime.parse(raw, UPSTREAM_TIME)
                        .atZone(SHANGHAI)
                        .toEpochSecond();
            }
        } catch (RuntimeException ignored) {
            // 落到兜底：宁可时间戳是「现在」，也不要因为解析失败丢掉整条报价
        }
        return System.currentTimeMillis() / 1000;
    }

    /** {@code 0930} -> {@code 09:30}；无法解析时原样返回。 */
    private static String formatMinute(String raw) {
        if (raw != null && raw.length() == 4 && raw.chars().allMatch(Character::isDigit)) {
            return raw.substring(0, 2) + ":" + raw.substring(2);
        }
        return raw;
    }

    private static String textOrNull(JsonNode row, int index) {
        JsonNode item = row.get(index);
        return item != null && item.isString() ? item.asString() : null;
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "-".equals(trimmed)) {
            return fallback;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
