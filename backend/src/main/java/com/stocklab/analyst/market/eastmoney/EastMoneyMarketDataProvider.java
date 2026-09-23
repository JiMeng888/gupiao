package com.stocklab.analyst.market.eastmoney;

import com.stocklab.analyst.common.constant.CacheNames;
import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.config.properties.MarketDataProperties;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.MarketDataUnavailableException;
import com.stocklab.analyst.market.StockCodes;
import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.Exchange;
import com.stocklab.analyst.market.model.IndexSnapshot;
import com.stocklab.analyst.market.model.MarketPeriod;
import com.stocklab.analyst.market.model.Mover;
import com.stocklab.analyst.market.model.MoverMetric;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.market.model.StockBrief;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 东方财富公开行情接口实现（真实数据，无需 API Key）。
 *
 * <p>选它的原因：
 * <ul>
 *   <li>返回标准 JSON / UTF-8，不像新浪 {@code hq.sinajs.cn} 返回 GBK 文本需要额外转码</li>
 *   <li>同一个服务商同时提供实时报价、多周期 K 线、分时、指数、全市场清单，
 *       不必混用多个上游、维护多套字段映射</li>
 *   <li>无鉴权、无 Cookie，字段稳定</li>
 * </ul>
 *
 * <p><b>字段码说明</b>：接口用 {@code f43} / {@code f58} 这类位置码返回数据，
 * 本项目把它们集中映射为语义字段，映射关系见各私有方法。
 * 上游若调整字段含义，改动只落在本类，业务层不受影响。
 *
 * <p><b>两个容易踩的坑，本类已处理</b>：
 * <ol>
 *   <li>{@code fltt=2} 必须显式传：不传时价格是放大后的整数
 *       （如 {@code f43=126698} 表示 1266.98），直接使用会导致价格放大 100 倍。</li>
 *   <li>停牌或字段缺失时，上游会把数值字段返回成字符串 {@code "-"}，
 *       直接 {@code asDouble()} 会得到 0 —— 看起来像「股价跌到 0」。
 *       因此所有数值读取都走 {@code readDouble}，非数字时回退到兜底值。</li>
 * </ol>
 */
public class EastMoneyMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyMarketDataProvider.class);

    private static final String HOST_PUSH2 = "https://push2.eastmoney.com";
    private static final String HOST_PUSH2HIS = "https://push2his.eastmoney.com";

    /** 实时报价字段：43 现价 / 44 最高 / 45 最低 / 46 今开 / 47 成交量(手) / 48 成交额 /
     *  57 代码 / 58 名称 / 60 昨收 / 168 换手率 / 169 涨跌额 / 170 涨跌幅 */
    private static final String QUOTE_FIELDS = "f43,f44,f45,f46,f47,f48,f57,f58,f60,f168,f169,f170";

    /** K 线字段：51 日期 / 52 开 / 53 收 / 54 高 / 55 低 / 56 成交量 / 57 成交额 / 58 振幅 */
    private static final String KLINE_FIELDS_2 = "f51,f52,f53,f54,f55,f56,f57,f58";

    /** 指数列表字段：2 点位 / 3 涨跌幅 / 4 涨跌额 / 12 代码 / 13 市场 / 14 名称 */
    private static final String INDEX_FIELDS = "f1,f2,f3,f4,f12,f13,f14";

    /** 沪深两市 + 北交所全部 A 股的筛选表达式。 */
    private static final String A_SHARE_FILTER = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048";

    /**
     * 清单接口每页返回条数。
     *
     * <p><b>不能指望一次拿完</b>：把 {@code pz} 设成 6000 时，上游仍然只返回
     * 100 条（服务端硬上限），而 {@code data.total} 是真实总数（5000+）。
     * 只发一次请求的结果是「股票搜索只能搜到前 100 只」——不报错、
     * 只是永远搜不到，属于最难发现的那类缺陷。因此必须翻页。
     */
    private static final int STOCK_LIST_PAGE_SIZE = 100;

    /** 翻页硬上限，防止上游 total 异常时无限循环（80 页 = 8000 只，覆盖全市场并留余量）。 */
    private static final int STOCK_LIST_MAX_PAGES = 80;

    /** 翻页间隔，避免对上游形成瞬时高频请求。 */
    private static final long STOCK_LIST_PAGE_INTERVAL_MS = 80L;

    /** 单页最大尝试次数（实测上游 clist 接口会偶发返回错误，重试一次能显著提高成功率）。 */
    private static final int STOCK_LIST_PAGE_ATTEMPTS = 3;

    /** 首页/指数条展示的主要指数。 */
    private static final List<String> INDEX_SEC_IDS = List.of(
            "1.000001", // 上证指数
            "0.399001", // 深证成指
            "0.399006", // 创业板指
            "1.000688", // 科创50
            "1.000300"  // 沪深300
    );

    /** 指数代码 -> 展示用带后缀代码，避免 000001 与深市平安银行同码混淆。 */
    private static final Map<String, String> INDEX_DISPLAY_CODES = Map.of(
            "000001", "000001.SH",
            "399001", "399001.SZ",
            "399006", "399006.SZ",
            "000688", "000688.SH",
            "000300", "000300.SH"
    );

    /** 榜单字段：2 现价 / 3 涨跌幅 / 4 涨跌额 / 6 成交额 / 12 代码 / 14 名称。 */
    private static final String MOVER_FIELDS = "f2,f3,f4,f6,f12,f14";

    /** 榜单条数上限：前端三个榜单各展示 10 条左右，20 已经留足余量。 */
    private static final int MAX_MOVERS_LIMIT = 20;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /** 全 A 股清单缓存（预热后填充），key 为 6 位代码。 */
    private final AtomicReference<Map<String, StockBrief>> stockListCache = new AtomicReference<>(Map.of());

    public EastMoneyMarketDataProvider(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "eastmoney";
    }

    @Override
    public boolean available() {
        return !stockListCache.get().isEmpty();
    }

    // ==================================================================
    // 实时报价
    // ==================================================================

    /**
     * 查询实时报价。
     *
     * <p>缓存 TTL 很短（默认 3 秒），作用是折叠同一秒内的并发重复请求，
     * 而不是「缓存行情」——盘中价格必须保持接近实时。
     */
    @Override
    @Cacheable(cacheNames = CacheNames.QUOTE, key = "#code", sync = true)
    public Quote getQuote(String code) {
        StockCodes.ParsedCode parsed = requireParsed(code);
        String url = HOST_PUSH2 + "/api/qt/stock/get"
                + "?secid=" + parsed.secId()
                + "&fields=" + QUOTE_FIELDS
                + "&invt=2&fltt=2";

        JsonNode root = fetchJson(url, "实时报价", parsed.secId());
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new BizException(ErrorCode.QUOTE_NOT_FOUND, "未找到股票代码 " + code);
        }

        String actualCode = text(data, "f57", parsed.code());
        String displayExchange = resolveExchange(actualCode, parsed.exchange()).display();
        double price = readDouble(data, "f43", 0);
        double prevClose = readDouble(data, "f60", price);

        return new Quote(
                actualCode,
                text(data, "f58", actualCode),
                displayExchange,
                price,
                readDouble(data, "f169", price - prevClose),
                readDouble(data, "f170", prevClose == 0 ? 0 : (price - prevClose) / prevClose * 100),
                readDouble(data, "f46", price),
                readDouble(data, "f44", price),
                readDouble(data, "f45", price),
                prevClose,
                (long) readDouble(data, "f47", 0),
                readDouble(data, "f168", 0),
                readNullableDouble(data, "f48"),
                System.currentTimeMillis() / 1000);
    }

    // ==================================================================
    // K 线
    // ==================================================================

    /**
     * 查询 K 线序列。
     *
     * <p>分时走独立端点（{@code trends2}），其余周期走 {@code kline} 端点。
     * 两条链路的字段顺序不同，但都会被规范化成同一套 {@link Bar}。
     */
    @Override
    @Cacheable(cacheNames = CacheNames.BARS, key = "#code + ':' + #period.name() + ':' + #limit", sync = true)
    public List<Bar> getBars(String code, MarketPeriod period, int limit) {
        StockCodes.ParsedCode parsed = requireParsed(code);
        return period == MarketPeriod.TREND
                ? fetchTrend(parsed, limit)
                : fetchKline(parsed, period, limit);
    }

    /** 日 / 周 / 月 / 分钟 K 线。 */
    private List<Bar> fetchKline(StockCodes.ParsedCode parsed, MarketPeriod period, int limit) {
        String url = HOST_PUSH2HIS + "/api/qt/stock/kline/get"
                + "?secid=" + parsed.secId()
                + "&fields1=f1,f2,f3,f4,f5,f6"
                + "&fields2=" + KLINE_FIELDS_2
                + "&klt=" + period.upstreamCode()
                + "&fqt=1"          // 前复权：除权后价格曲线连续，均线指标才有意义
                + "&end=20500101"
                + "&lmt=" + limit;

        JsonNode root = fetchJson(url, "K线", parsed.secId());
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new BizException(ErrorCode.QUOTE_NOT_FOUND, "未找到股票代码 " + parsed.code());
        }

        JsonNode klines = data.path("klines");
        if (!klines.isArray() || klines.isEmpty()) {
            return List.of();
        }

        // 首根 K 线的昨收由上游单独给出（preKPrice），后续每根用前一根收盘价
        double runningPrevClose = readDouble(data, "preKPrice", 0);
        List<Bar> bars = new ArrayList<>(klines.size());
        for (JsonNode item : klines) {
            String[] parts = item.asString().split(",");
            if (parts.length < 6) {
                continue;
            }
            double close = parseDouble(parts[2], 0);
            bars.add(new Bar(
                    parts[0],
                    parseDouble(parts[1], close),
                    parseDouble(parts[3], close),
                    parseDouble(parts[4], close),
                    close,
                    (long) parseDouble(parts[5], 0),
                    parts.length > 6 ? parseDouble(parts[6], 0) : null,
                    runningPrevClose));
            runningPrevClose = close;
        }
        return Collections.unmodifiableList(bars);
    }

    /** 当日分时。 */
    private List<Bar> fetchTrend(StockCodes.ParsedCode parsed, int limit) {
        String url = HOST_PUSH2HIS + "/api/qt/stock/trends2/get"
                + "?secid=" + parsed.secId()
                + "&fields1=f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13"
                + "&fields2=" + KLINE_FIELDS_2 + ",f59"   // 追加 f59 = 均价
                + "&iscr=0&ndays=1";

        JsonNode root = fetchJson(url, "分时", parsed.secId());
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new BizException(ErrorCode.QUOTE_NOT_FOUND, "未找到股票代码 " + parsed.code());
        }

        JsonNode trends = data.path("trends");
        if (!trends.isArray() || trends.isEmpty()) {
            return List.of();
        }

        double runningPrevClose = readDouble(data, "preClose", 0);
        List<Bar> bars = new ArrayList<>(trends.size());
        for (JsonNode item : trends) {
            String[] parts = item.asString().split(",");
            if (parts.length < 6) {
                continue;
            }
            double close = parseDouble(parts[2], 0);
            bars.add(new Bar(
                    parts[0],
                    parseDouble(parts[1], close),
                    parseDouble(parts[3], close),
                    parseDouble(parts[4], close),
                    close,
                    (long) parseDouble(parts[5], 0),
                    parts.length > 6 ? parseDouble(parts[6], 0) : null,
                    runningPrevClose));
            runningPrevClose = close;
        }

        // 分时点位通常远多于请求上限，取尾部保留最近的部分
        if (bars.size() > limit) {
            return Collections.unmodifiableList(new ArrayList<>(bars.subList(bars.size() - limit, bars.size())));
        }
        return Collections.unmodifiableList(bars);
    }

    // ==================================================================
    // 指数
    // ==================================================================

    @Override
    @Cacheable(cacheNames = CacheNames.QUOTE, key = "'indices'", sync = true)
    public List<IndexSnapshot> getIndices() {
        String url = HOST_PUSH2 + "/api/qt/ulist.np/get"
                + "?fltt=2&invt=2"
                + "&secids=" + String.join(",", INDEX_SEC_IDS)
                + "&fields=" + INDEX_FIELDS;

        JsonNode root = fetchJson(url, "指数", "indices");
        JsonNode diff = root.path("data").path("diff");
        if (!diff.isArray() || diff.isEmpty()) {
            return List.of();
        }

        List<IndexSnapshot> result = new ArrayList<>(diff.size());
        for (JsonNode item : diff) {
            String rawCode = text(item, "f12", "");
            if (rawCode.isEmpty()) {
                continue;
            }
            result.add(new IndexSnapshot(
                    INDEX_DISPLAY_CODES.getOrDefault(rawCode, rawCode),
                    text(item, "f14", rawCode),
                    readDouble(item, "f2", 0),
                    readDouble(item, "f4", 0),
                    readDouble(item, "f3", 0)));
        }
        return Collections.unmodifiableList(result);
    }

    // ==================================================================
    // 搜索 / 全市场清单
    // ==================================================================

    /**
     * 模糊检索。
     *
     * <p>在本地清单里过滤，而不是每次请求上游搜索接口：
     * 全市场 5000+ 条记录的清单只有几百 KB，常驻内存后过滤是微秒级，
     * 而走上游搜索接口每次要几十毫秒且会被限流。
     * 清单为空（未预热或预热失败）时，回退到上游搜索接口兜底。
     */
    @Override
    public List<StockBrief> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        Map<String, StockBrief> cache = stockListCache.get();
        if (cache.isEmpty()) {
            return searchUpstream(keyword);
        }

        String needle = keyword.trim().toLowerCase();
        return cache.values().stream()
                .filter(brief -> brief.code().contains(needle)
                        || brief.name().toLowerCase().contains(needle))
                .limit(50)
                .toList();
    }

    /** 上游搜索接口兜底（清单未加载时使用）。 */
    private List<StockBrief> searchUpstream(String keyword) {
        String url = "https://searchapi.eastmoney.com/api/suggest/get"
                + "?input=" + URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8)
                + "&type=14&token=D43BF722C8E33BDC906FB84D85E326E8&count=20";

        JsonNode root = fetchJson(url, "股票搜索", keyword);
        JsonNode data = root.path("QuotationCodeTable").path("Data");
        if (!data.isArray() || data.isEmpty()) {
            return List.of();
        }

        List<StockBrief> result = new ArrayList<>();
        for (JsonNode item : data) {
            String code = text(item, "Code", "");
            // 只保留 A 股，过滤掉基金 / 债券 / 港股等其它品种
            if (code.isEmpty() || !"AStock".equals(text(item, "Classify", ""))) {
                continue;
            }
            Exchange exchange = StockCodes.inferExchange(code);
            if (exchange == null) {
                continue;
            }
            result.add(new StockBrief(code, text(item, "Name", code), exchange.display()));
        }
        return result;
    }

    /**
     * 市场榜单：与「全 A 股清单预热」同款的 {@code clist/get} 接口，
     * 靠 {@code fid}（排序字段）+ {@code po}（方向）+ {@code pz}（条数）三个参数
     * 让上游直接返回排好序的前 N 条，而不是把 5917 条全量拉回来自己排。
     *
     * <p>三个值得记录的实测结论（2026-09-19）：
     * <ol>
     *   <li>{@code clist/get}（不带 .np）可用，而 {@code clist.np/get} 与
     *       {@code ulist.np/get + fs} 对无 Cookie 的服务端请求不可用
     *       （空响应 / {@code rc:102, data:null}）。两者只差一个 {@code .np}，
     *       不要「顺手统一」。</li>
     *   <li>{@code po=1} 是降序（取最大值一侧），{@code po=0} 是升序——
     *       不是「1=正序」的直觉含义。</li>
     *   <li>涨跌幅榜的头部是新股（如上市首日 +177%），这是真实市场行为，
     *       不是数据错误。</li>
     * </ol>
     */
    @Override
    @Cacheable(cacheNames = CacheNames.QUOTE,
            key = "'movers:' + #metric.name() + ':' + #ascending + ':' + #limit",
            sync = true)
    public List<Mover> getTopMovers(MoverMetric metric, boolean ascending, int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_MOVERS_LIMIT);
        String url = HOST_PUSH2 + "/api/qt/clist/get"
                + "?pn=1&pz=" + effectiveLimit
                + "&po=" + (ascending ? 0 : 1)
                + "&np=1&fltt=2&invt=2"
                + "&fid=" + metric.eastmoneyFid()
                + "&fs=" + A_SHARE_FILTER
                + "&fields=" + MOVER_FIELDS;

        JsonNode root = fetchJson(url, "市场榜单", metric.name());
        List<Mover> result = parseMoversResponse(root, metric.eastmoneyFid());
        if (result.isEmpty()) {
            // data:null（rc:102）或空列表都算「上游没给出榜单」，
            // 抛不可用交给路由器决定是否降级，而不是返回空集合假装正常
            throw new MarketDataUnavailableException(
                    "榜单上游返回空数据（fid=" + metric.eastmoneyFid() + "）");
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 把 {@code clist/get} 的响应体解析为榜单条目。
     *
     * <p>包级可见以便对「上游真实响应样本」直接做单元测试，
     * 不需要为它起本地 HTTP 服务。{@code root.data} 为 {@code null}
     * （上游拒绝服务时的 {@code rc:102} 形态）或 {@code diff} 为空数组时
     * 返回空列表，由调用方决定空列表意味着什么。
     *
     * @param fid 仅用于异常消息定位，方便从日志反查是哪个榜单维度出了问题
     */
    static List<Mover> parseMoversResponse(JsonNode root, String fid) {
        JsonNode diff = root == null ? null : root.path("data").path("diff");
        if (diff == null || !diff.isArray() || diff.isEmpty()) {
            return List.of();
        }
        List<Mover> result = new ArrayList<>(diff.size());
        for (JsonNode item : diff) {
            String code = text(item, "f12", "");
            if (!StockCodes.isSixDigitCode(code)) {
                continue;
            }
            result.add(new Mover(
                    code,
                    text(item, "f14", code),
                    readDouble(item, "f2", 0),
                    readDouble(item, "f4", 0),
                    readDouble(item, "f3", 0),
                    readDouble(item, "f6", 0)));
        }
        return result;
    }

    /**
     * 预热：分页拉取全 A 股清单并常驻内存。
     *
     * <p>逐页请求而不是一次性请求，原因见 {@link #STOCK_LIST_PAGE_SIZE}。
     * 终止条件有两个：已抓够上游声明的 {@code total}，或某一页返回不足
     * 一页的量（说明到末页了）。两者都不满足时由 {@link #STOCK_LIST_MAX_PAGES}
     * 兜底跳出——宁可少缓存几只，也不能因为上游 total 字段异常而卡死在循环里。
     *
     * <p>失败不抛异常：清单缺失只影响搜索走上游兜底，不应阻断应用启动。
     * 但会打印实际抓到的数量与上游声明的总量，两者不一致时能立刻从日志看出来。
     */
    @Override
    public void warmup() {
        Map<String, StockBrief> loaded = new LinkedHashMap<>(6000);
        int declaredTotal = -1;
        int fetched = 0;
        int pages = 0;

        try {
            for (int page = 1; page <= STOCK_LIST_MAX_PAGES; page++) {
                JsonNode root = fetchPageWithRetry(page);
                if (root == null) {
                    // 重试若干次仍失败：停止翻页，保留已抓到的部分
                    log.warn("行情预热：第 {} 页连续 {} 次请求失败，停止翻页", page, STOCK_LIST_PAGE_ATTEMPTS);
                    break;
                }
                JsonNode data = root.path("data");
                if (data.isMissingNode() || data.isNull()) {
                    break;
                }
                if (declaredTotal < 0) {
                    declaredTotal = (int) readDouble(data, "total", 0);
                }

                JsonNode diff = data.path("diff");
                if (!diff.isArray() || diff.isEmpty()) {
                    break;
                }
                pages++;
                fetched += diff.size();

                for (JsonNode item : diff) {
                    String code = text(item, "f12", "");
                    if (!StockCodes.isSixDigitCode(code)) {
                        continue;
                    }
                    // 上游 f13 用 1 表示沪市、0 表示深市/北交所，无法区分深市与北交所，
                    // 因此交易所改用代码段推断（见 StockCodes.inferExchange）
                    Exchange exchange = StockCodes.inferExchange(code);
                    if (exchange == null) {
                        continue;
                    }
                    loaded.put(code, new StockBrief(code, text(item, "f14", code), exchange.display()));
                }

                // 末页判定：返回条数不足一页，或已抓够总数
                if (diff.size() < STOCK_LIST_PAGE_SIZE || (declaredTotal > 0 && fetched >= declaredTotal)) {
                    break;
                }
                Thread.sleep(STOCK_LIST_PAGE_INTERVAL_MS);
            }

            stockListCache.set(Collections.unmodifiableMap(loaded));

            if (declaredTotal > 0 && loaded.size() < declaredTotal) {
                // 不静默：少缓存意味着搜索会漏掉标的，必须让人看到
                log.warn("行情预热：仅缓存 {} 只（上游声明 {} 只，抓取 {} 条，{} 页），"
                                + "搜索可能无法覆盖全部标的（数据源 {}）",
                        loaded.size(), declaredTotal, fetched, pages, name());
            } else {
                log.info("行情预热完成：已缓存 {} 只 A 股清单（{} 页，数据源 {}）",
                        loaded.size(), pages, name());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            // 被中断也必须把已抓到的部分用起来，而不是整批丢弃
            stockListCache.set(Collections.unmodifiableMap(loaded));
            log.warn("行情预热被中断，已缓存 {} 只 A 股清单", loaded.size());
        } catch (RuntimeException ex) {
            if (!loaded.isEmpty()) {
                stockListCache.set(Collections.unmodifiableMap(loaded));
            }
            log.warn("预热全 A 股清单失败（已缓存 {} 只），搜索将回退到上游接口：{}",
                    loaded.size(), ex.getMessage());
        }
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 拉取清单的第 {@code page} 页，失败时重试。
     *
     * <p>重试是必需的而不是「更稳一点」：实测这个接口会偶发直接返回错误
     * （既不是限流也不是参数问题，同一请求立刻重发就能成功）。
     * 只在预热路径上重试、且次数固定、间隔递增——不做无限重试，
     * 否则上游长时间故障时预热线程会一直占着不放。
     *
     * @return 解析后的 JSON 树；全部尝试失败时返回 {@code null}
     */
    private JsonNode fetchPageWithRetry(int page) {
        String url = HOST_PUSH2 + "/api/qt/clist/get"
                + "?pn=" + page + "&pz=" + STOCK_LIST_PAGE_SIZE
                + "&po=1&np=1&fltt=2&invt=2&fid=f3"
                + "&fs=" + A_SHARE_FILTER
                + "&fields=f12,f13,f14";

        for (int attempt = 1; attempt <= STOCK_LIST_PAGE_ATTEMPTS; attempt++) {
            try {
                return fetchJson(url, "全A股清单", "pn=" + page);
            } catch (MarketDataUnavailableException ex) {
                if (attempt == STOCK_LIST_PAGE_ATTEMPTS) {
                    log.warn("拉取清单第 {} 页失败（已尝试 {} 次）：{}",
                            page, attempt, ex.getMessage());
                    return null;
                }
                log.debug("拉取清单第 {} 页第 {} 次失败，准备重试：{}", page, attempt, ex.getMessage());
                try {
                    Thread.sleep(STOCK_LIST_PAGE_INTERVAL_MS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 发起 GET 并把响应体解析为 JSON 树。
     *
     * <p>这里把「HTTP 层异常」统一翻译为 {@link MarketDataUnavailableException}，
     * 使调用方只需区分「标的不存在」与「上游不可用」两种情况。
     * 注意不要在这里把 {@code BizException} 也吞掉——那会把 404 变成 503。
     */
    private JsonNode fetchJson(String url, String action, String subject) {
        String body;
        try {
            body = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
        } catch (RuntimeException ex) {
            throw new MarketDataUnavailableException(
                    "行情上游请求失败（" + action + " " + subject + "）", ex);
        }
        if (body == null || body.isBlank()) {
            throw new MarketDataUnavailableException("行情上游返回空响应（" + action + " " + subject + "）");
        }
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException ex) {
            throw new MarketDataUnavailableException(
                    "行情上游响应不是合法 JSON（" + action + " " + subject + "）", ex);
        }
    }

    private StockCodes.ParsedCode requireParsed(String code) {
        StockCodes.ParsedCode parsed = StockCodes.parse(code);
        if (parsed == null) {
            throw new BizException(ErrorCode.QUOTE_NOT_FOUND, "未找到股票代码 " + code);
        }
        return parsed;
    }

    private Exchange resolveExchange(String code, Exchange fallback) {
        Exchange inferred = StockCodes.inferExchange(code);
        return inferred != null ? inferred : fallback;
    }

    /**
     * 读取数值字段。
     *
     * <p>上游在停牌等场景会把数值字段返回为字符串 {@code "-"}，
     * 此时返回兜底值而不是 0，避免「跌到 0」这类错误展示。
     */
    private static double readDouble(JsonNode node, String field, double fallback) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isString()) {
            return parseDouble(value.asString(), fallback);
        }
        return fallback;
    }

    private static Double readNullableDouble(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asDouble();
        }
        return null;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : fallback;
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
