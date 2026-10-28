package com.stocklab.analyst.market.tushare;

import com.stocklab.analyst.common.constant.CacheNames;
import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.config.properties.TushareProperties;
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
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tushare Pro 数据源实现（<b>有授权协议</b>的真实行情）。
 *
 * <h2>与东财/腾讯的本质区别</h2>
 * 东财与腾讯是「财经网站给自家网页用的内部接口」，未获数据方授权，且存在
 * IP 级风控（服务端直接断连）。Tushare Pro 是<b>注册授权制</b>的数据服务：
 * 凭个人 Token 访问，接口行为与字段有文档约束，是走向合规数据源的正路。
 * 详见 {@link TushareProperties}。
 *
 * <h2>能力边界（如实声明，不假装完整）</h2>
 * Tushare 的免费/低积分档<b>不提供盘中实时报价</b>与<b>全市场榜单</b>：
 * <ul>
 *   <li>{@code daily} 是<b>收盘后</b>的日线数据（当日 15:00 后可用），
 *       不存在「此刻的最新价」；</li>
 *   <li>实时报价（{@code realtime_quote}）与榜单需要高积分，本工程按「免费档」定位。</li>
 * </ul>
 * 因此本源的定位是<b>日线 K 线 + 指数 + 股票搜索</b>这三类「非实时」能力：
 * 它们恰好是东财/腾讯被风控后最需要兜底、且数据质量有保障的部分。
 * {@link #getQuote} 与 {@link #getTopMovers} 抛 {@link MarketDataUnavailableException}，
 * 由降级路由交给下一级——这与腾讯源对「搜索/榜单」的处理哲学一致：不做假能力。
 *
 * <h2>接口协议</h2>
 * HTTP POST {@code {baseUrl}}，JSON body {@code {"api_name":..., "token":..., "params":{...}, "fields":"..."}}。
 * 响应 {@code {"code":0, "msg":"", "data":{"fields":[...], "items":[[...],...]}}}。
 * {@code code != 0} 是业务错误（Token 无效、积分不足、参数错误），必须区分对待。
 */
public class TushareMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(TushareMarketDataProvider.class);

    /** 首页展示的五个主要指数：Tushare 指数代码 -> 展示用带后缀代码。 */
    private static final Map<String, String> INDEX_TS_CODES;
    private static final List<String> INDEX_TS_PARAM;

    static {
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("000001.SH", "000001.SH");
        ordered.put("399001.SZ", "399001.SZ");
        ordered.put("399006.SZ", "399006.SZ");
        ordered.put("000688.SH", "000688.SH");
        ordered.put("000300.SH", "000300.SH");
        INDEX_TS_CODES = Collections.unmodifiableMap(ordered);
        INDEX_TS_PARAM = List.copyOf(ordered.keySet());
    }

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final TushareProperties properties;

    /** 全 A 股清单缓存（预热后填充），key 为 6 位代码。 */
    private final AtomicReference<Map<String, StockBrief>> stockListCache =
            new AtomicReference<>(Map.of());

    public TushareMarketDataProvider(RestClient restClient,
                                     ObjectMapper objectMapper,
                                     TushareProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        log.info("Tushare 数据源：enabled={} token={} baseUrl={} timeout={}s",
                properties.enabled(), properties.maskedToken(),
                properties.baseUrl(), properties.timeout().toSeconds());
    }

    @Override
    public String name() {
        return "tushare";
    }

    @Override
    public boolean available() {
        return properties.enabled();
    }

    // ==================================================================
    // K 线（日/周/月，收盘后数据）
    // ==================================================================

    @Override
    @Cacheable(cacheNames = CacheNames.BARS, key = "#code + ':' + #period.name() + ':' + #limit", sync = true)
    public List<Bar> getBars(String code, MarketPeriod period, int limit) {
        StockCodes.ParsedCode parsed = requireParsed(code);
        String tsCode = toTsCode(parsed);

        return switch (period) {
            case DAILY -> fetchDailyBars(tsCode, "daily", limit);
            case WEEKLY -> fetchDailyBars(tsCode, "weekly", limit);
            case MONTHLY -> fetchDailyBars(tsCode, "monthly", limit);
            // 免费档不提供分钟级与分时。与腾讯源对分钟级的处理一致：如实不可用，交路由降级
            default -> throw new MarketDataUnavailableException(
                    "Tushare 免费档不提供分钟级/分时行情（period=" + period.name() + " " + code + "）");
        };
    }

    /**
     * 拉取日/周/月 K 线并转为 {@link Bar}。
     *
     * <p>{@code daily} 返回 {@code trade_date/open/high/low/close/pre_close/change/pct_chg/vol/amount}，
     * 按 {@code trade_date} 升序（Tushare 默认降序返回，这里显式按日期正序重组）。
     * {@code pre_close} 由上游直接给出，满足 {@link Bar} 对「逐根昨收」的契约，
     * 无需像东财/腾讯那样用前一根收盘价回填。
     */
    private List<Bar> fetchDailyBars(String tsCode, String apiName, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ts_code", tsCode);
        params.put("limit", Math.min(Math.max(limit, 1), 5000));

        JsonNode data = callApi(apiName, params,
                "trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount");
        List<Bar> bars = parseBars(data, tsCode);
        if (bars.isEmpty()) {
            throw new BizException(ErrorCode.QUOTE_NOT_FOUND, "未找到股票代码 " + tsCode);
        }
        // daily 默认按 trade_date 降序，Bar 契约要求升序（时间正序）
        Collections.reverse(bars);
        return Collections.unmodifiableList(bars);
    }

    /**
     * 解析 Tushare 日线响应为 {@link Bar} 列表。
     *
     * <p>包级可见以便用真实响应样本做单测。字段顺序由 {@code fields} 参数固定，
     * 通过 {@code data.fields} 反查列下标，而不是硬编码——上游增删字段时更健壮。
     */
    static List<Bar> parseBars(JsonNode data, String tsCode) {
        JsonNode fields = data.path("fields");
        JsonNode items = data.path("items");
        if (!fields.isArray() || !items.isArray()) {
            return List.of();
        }
        // 建立「字段名 -> 下标」映射
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            col.put(fields.get(i).asString(), i);
        }

        List<Bar> bars = new ArrayList<>(items.size());
        Double runningPrevClose = null;
        for (JsonNode row : items) {
            if (!row.isArray() || row.size() < fields.size()) {
                continue;
            }
            double open = num(row, col, "open", 0);
            double high = num(row, col, "high", 0);
            double low = num(row, col, "low", 0);
            double close = num(row, col, "close", 0);
            Double preClose = nullableNum(row, col, "pre_close");
            long volume = (long) num(row, col, "vol", 0);
            Double amount = nullableNum(row, col, "amount");

            bars.add(new Bar(
                    text(row, col, "trade_date"),
                    open, high, low, close, volume, amount,
                    preClose != null ? preClose : runningPrevClose));
            runningPrevClose = close;
        }
        return bars;
    }

    // ==================================================================
    // 指数（最新收盘点位）
    // ==================================================================

    @Override
    @Cacheable(cacheNames = CacheNames.QUOTE, key = "'tushare:indices'", sync = true)
    public List<IndexSnapshot> getIndices() {
        // 逐个指数取最新一条日线（index_daily 支持 ts_code 批量查询，但返回体大；
        // 五个指数分开取，任一失败不影响其余，逐路容错更符合「缺项是常态」的约定）
        List<IndexSnapshot> result = new ArrayList<>(INDEX_TS_PARAM.size());
        for (String tsCode : INDEX_TS_PARAM) {
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("ts_code", tsCode);
                params.put("limit", 1);
                JsonNode data = callApi("index_daily", params,
                        "trade_date,close,pre_close,pct_chg");
                IndexSnapshot snapshot = parseLatestIndex(data, tsCode);
                if (snapshot != null) {
                    result.add(snapshot);
                }
            } catch (MarketDataUnavailableException ex) {
                log.debug("Tushare 指数 {} 获取失败：{}", tsCode, ex.getMessage());
            }
        }
        if (result.isEmpty()) {
            throw new MarketDataUnavailableException("Tushare 指数接口返回空数据");
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 从 {@code index_daily} 最新一条解析为 {@link IndexSnapshot}。
     *
     * <p>包级可见以便单测。涨跌额由「收盘 - 昨收」推导（Tushare 指数日线不含 change 额字段，
     * 只有 pct_chg 涨跌幅）。
     */
    static IndexSnapshot parseLatestIndex(JsonNode data, String tsCode) {
        JsonNode fields = data.path("fields");
        JsonNode items = data.path("items");
        if (!fields.isArray() || !items.isArray() || items.isEmpty()) {
            return null;
        }
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            col.put(fields.get(i).asString(), i);
        }
        JsonNode row = items.get(0);
        double close = num(row, col, "close", 0);
        Double preClose = nullableNum(row, col, "pre_close");
        double change = preClose == null ? 0 : close - preClose;
        double pctChg = num(row, col, "pct_chg", preClose == null || preClose == 0 ? 0 : change / preClose * 100);

        String displayCode = INDEX_TS_CODES.getOrDefault(tsCode, tsCode);
        String name = INDEX_NAMES.getOrDefault(tsCode, tsCode);
        return new IndexSnapshot(displayCode, name, close, change, pctChg);
    }

    private static final Map<String, String> INDEX_NAMES = Map.of(
            "000001.SH", "上证指数",
            "399001.SZ", "深证成指",
            "399006.SZ", "创业板指",
            "000688.SH", "科创50",
            "000300.SH", "沪深300"
    );

    // ==================================================================
    // 搜索 / 全市场清单
    // ==================================================================

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

    /** 清单未加载时上游兜底：Tushare 无模糊搜索接口，只能按代码/名称精确匹配。 */
    private List<StockBrief> searchUpstream(String keyword) {
        // Tushare 的 stock_basic 支持按 ts_code 精确查，不支持模糊。这里退化为
        // 「代码精确命中」，否则返回空——不做假模糊搜索。
        StockCodes.ParsedCode parsed = StockCodes.parse(keyword);
        if (parsed == null) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ts_code", toTsCode(parsed));
        params.put("list_status", "L");
        JsonNode data = callApi("stock_basic", params, "ts_code,symbol,name");
        List<StockBrief> result = new ArrayList<>(1);
        for (StockBrief brief : parseStockBriefs(data)) {
            result.add(brief);
        }
        return result;
    }

    /** 预热：加载全 A 股清单到内存（搜索依赖）。失败不阻断启动。 */
    @Override
    public void warmup() {
        if (!properties.enabled()) {
            log.info("Tushare 未配置 Token，跳过预热");
            return;
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("list_status", "L");
            JsonNode data = callApi("stock_basic", params, "ts_code,symbol,name");
            List<StockBrief> briefs = parseStockBriefs(data);

            Map<String, StockBrief> loaded = new LinkedHashMap<>(briefs.size() * 2);
            for (StockBrief brief : briefs) {
                loaded.put(brief.code(), brief);
            }
            stockListCache.set(Collections.unmodifiableMap(loaded));
            log.info("Tushare 预热完成：已缓存 {} 只 A 股清单", loaded.size());
        } catch (RuntimeException ex) {
            log.warn("Tushare 预热全 A 股清单失败，搜索将回退到上游精确查询：{}", ex.getMessage());
        }
    }

    /**
     * 解析 {@code stock_basic} 响应为 {@link StockBrief} 列表。
     *
     * <p>包级可见以便单测。{@code ts_code} 形如 {@code 600519.SH}，去掉后缀取 6 位代码，
     * 交易所由代码段推断（与东财预热一致）。
     */
    static List<StockBrief> parseStockBriefs(JsonNode data) {
        JsonNode fields = data.path("fields");
        JsonNode items = data.path("items");
        if (!fields.isArray() || !items.isArray()) {
            return List.of();
        }
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            col.put(fields.get(i).asString(), i);
        }

        List<StockBrief> result = new ArrayList<>(items.size());
        for (JsonNode row : items) {
            if (!row.isArray() || row.size() < fields.size()) {
                continue;
            }
            String tsCode = text(row, col, "ts_code");
            StockCodes.ParsedCode parsed = StockCodes.parse(tsCode);
            if (parsed == null) {
                continue;
            }
            result.add(new StockBrief(parsed.code(), text(row, col, "name"), parsed.exchange().display()));
        }
        return result;
    }

    // ==================================================================
    // 能力边界：免费档不提供的操作
    // ==================================================================

    /**
     * 实时报价 Tushare 免费档不提供。
     *
     * <p>{@code daily} 是收盘后数据，没有「此刻的最新价」。抛「不可用」交由
     * 降级路由（东财/腾讯仍提供实时报价），而不是用昨天的收盘价冒充现价——
     * 那会让用户把陈旧价格当成实时行情，比「不可用」更危险。
     */
    @Override
    public Quote getQuote(String code) {
        requireParsed(code);
        throw new MarketDataUnavailableException(
                "Tushare 免费档不提供盘中实时报价（" + code + "）");
    }

    /** 市场榜单同实时报价：免费档无此能力，交路由降级。 */
    @Override
    public List<Mover> getTopMovers(MoverMetric metric, boolean ascending, int limit) {
        throw new MarketDataUnavailableException("Tushare 免费档不提供市场榜单");
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private StockCodes.ParsedCode requireParsed(String code) {
        StockCodes.ParsedCode parsed = StockCodes.parse(code);
        if (parsed == null) {
            throw new BizException(ErrorCode.QUOTE_NOT_FOUND, "未找到股票代码 " + code);
        }
        return parsed;
    }

    /** 6 位代码 -> Tushare {@code ts_code}（{@code 600519.SH} 形态）。 */
    private static String toTsCode(StockCodes.ParsedCode parsed) {
        return parsed.code() + "." + parsed.exchange().display();
    }

    /**
     * 发起一次 Tushare API 调用并返回 {@code data} 节点。
     *
     * <p>错误分三类处理：
     * <ul>
     *   <li>HTTP/网络异常 → {@link MarketDataUnavailableException}（503，可降级）；</li>
     *   <li>{@code code != 0}（Token 无效/积分不足/参数错）→ 同样是「不可用」，
     *       因为这对调用方是上游服务问题而非标的缺失；</li>
     *   <li>{@code data} 为空 → 返回空 JsonNode，由各解析方法决定「空」的含义。</li>
     * </ul>
     */
    private JsonNode callApi(String apiName, Map<String, Object> params, String fields) {
        if (!properties.enabled()) {
            throw new MarketDataUnavailableException("Tushare 未配置 Token");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("api_name", apiName);
        payload.put("token", properties.token());
        payload.put("params", params);
        payload.put("fields", fields);

        String body;
        try {
            body = restClient.post()
                    .uri(URI.create(properties.baseUrl()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException ex) {
            throw new MarketDataUnavailableException(
                    "Tushare 请求失败（" + apiName + "）", ex);
        }
        if (body == null || body.isBlank()) {
            throw new MarketDataUnavailableException("Tushare 返回空响应（" + apiName + "）");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (RuntimeException ex) {
            throw new MarketDataUnavailableException("Tushare 响应不是合法 JSON（" + apiName + "）", ex);
        }

        int code = root.path("code").asInt(-1);
        if (code != 0) {
            String msg = root.path("msg").asString("未知错误");
            throw new MarketDataUnavailableException("Tushare 接口错误（" + apiName + "）: " + msg);
        }
        return root.path("data");
    }

    /** 按字段名从行中读取文本；缺列返回空串。 */
    private static String text(JsonNode row, Map<String, Integer> col, String field) {
        Integer idx = col.get(field);
        if (idx == null || idx >= row.size()) {
            return "";
        }
        JsonNode node = row.get(idx);
        return node.isNull() || node.isMissingNode() ? "" : node.asString();
    }

    /** 按字段名从行中读取数值；缺列或非数值返回兜底值。 */
    private static double num(JsonNode row, Map<String, Integer> col, String field, double fallback) {
        Integer idx = col.get(field);
        if (idx == null || idx >= row.size()) {
            return fallback;
        }
        JsonNode node = row.get(idx);
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isString()) {
            String s = node.asString().trim();
            if (!s.isEmpty() && !"-".equals(s)) {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException ignored) {
                    // 落到兜底
                }
            }
        }
        return fallback;
    }

    /** 按字段名从行中读取可空数值；缺列返回 null。 */
    private static Double nullableNum(JsonNode row, Map<String, Integer> col, String field) {
        Integer idx = col.get(field);
        if (idx == null || idx >= row.size()) {
            return null;
        }
        JsonNode node = row.get(idx);
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isString()) {
            String s = node.asString().trim();
            if (!s.isEmpty() && !"-".equals(s)) {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException ignored) {
                    // 落到 null
                }
            }
        }
        return null;
    }
}
