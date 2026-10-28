package com.stocklab.analyst.market.sina;

import com.stocklab.analyst.common.constant.CacheNames;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.MarketDataUnavailableException;
import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.IndexSnapshot;
import com.stocklab.analyst.market.model.MarketPeriod;
import com.stocklab.analyst.market.model.Mover;
import com.stocklab.analyst.market.model.MoverMetric;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.market.model.StockBrief;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 新浪财经公开接口实现：<b>目前只提供个股榜单</b>（涨跌幅榜 / 成交额榜）。
 *
 * <h2>为什么需要它</h2>
 * 个股榜单此前<b>只有东财一家真实源</b>：腾讯内部接口的个股榜（
 * {@code rank/pt/getRank?board_type=hs}）实测返回
 * {@code {"rank_list":[],"total":0}}，即没有这条能力。于是榜单的降级链实际是
 * {@code eastmoney → mock}，而 mock 对榜单是<b>策略性拒绝</b>
 * （名次携带判断，编造的排序比乱码更危险，见
 * {@link com.stocklab.analyst.market.mock.MockMarketDataProvider#acceptableAsFallback}）。
 * 结果就是：东财一被风控，首页「今日涨幅榜」整块变成
 * 「市场榜单暂不可用：上游数据源故障，且该类数据不使用内置演示数据替代」。
 *
 * <p>东财的 IP 级风控不是偶发事件（README 记录过 2026-09-19 / 09-21 两次，
 * 2026-10-21 复核时 {@code push2}、{@code 1.push2}、{@code 82.push2}、
 * {@code push2delay} 四个域名同时断连），所以「只有一家源」等同于
 * 「这个面板经常性的整块不可用」。Sina 这一级把它变成「换一家继续给真实数据」。
 *
 * <h2>上游接口</h2>
 * <pre>
 * GET https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php
 *     /Market_Center.getHQNodeData
 *     ?page=1&amp;num=20&amp;sort=changepercent&amp;asc=0&amp;node=hs_a&amp;symbol=&amp;_s_r_a=page
 * </pre>
 * <ul>
 *   <li>{@code node=hs_a}：沪深 A 股（含北交所）。与东财的
 *       {@code m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048}
 *       覆盖同样五个板块，因此两家的榜单<b>可以直接互换</b>，
 *       不会出现「换个源就多出/少掉一批股票」；</li>
 *   <li>{@code sort} 取 {@code changepercent} 或 {@code amount}；
 *       {@code asc=0} 降序、{@code asc=1} 升序（实测）;</li>
 *   <li>响应是 <b>JSON 数组</b>（不是 {@code {"data":...}} 包裹的对象），
 *       元素字段：{@code code / name / trade / pricechange / changepercent / amount}。</li>
 * </ul>
 *
 * <h2>能力边界（如实声明）</h2>
 * 本实现<b>只</b>覆盖榜单。报价、K 线、指数、搜索都抛
 * {@link MarketDataUnavailableException}——不是「懒得实现」，而是
 * 这套接口只提供榜单所需字段（没有 open/high/low、没有 K 线序列、
 * 没有模糊检索）。因此它<b>不进入主链</b>，只通过
 * {@code app.market-data.movers-chain} 挂到 {@code getTopMovers} 的专用链上
 * （默认 {@code eastmoney → sina → mock}）。
 * 把它放进主链只会让报价、K 线的每次降级都多走一次注定失败的调用。
 *
 * <h2>两处刻意的处理</h2>
 * <ol>
 *   <li><b>本地重新排序</b>：{@code sort} / {@code asc} 的行为没有文档保证，
 *       接口契约（「按该维度排序的前 N 条」）不能寄托在对方的实现细节上。
 *       拉回的条数只有 20 上下，重排成本可以忽略，换来的是
 *       「跌幅榜第一行确实是最跌的那只」这条可断言的性质；</li>
 *   <li><b>丢弃价格为 0 的行</b>：停牌 / 未开盘的标的上游给
 *       {@code trade="0.000"}，放进榜单会被读成「股价归零」。为抵消丢弃，
 *       多取 {@link #FETCH_PADDING} 条再截断。</li>
 * </ol>
 */
public class SinaMarketDataProvider implements MarketDataProvider {

    private static final String HOST = "https://vip.stock.finance.sina.com.cn";

    private static final String MOVERS_PATH =
            "/quotes_service/api/json_v2.php/Market_Center.getHQNodeData";

    /** 榜单对外条数上限，与东财、腾讯保持一致（前端最多展示 20 条）。 */
    private static final int MAX_MOVERS_LIMIT = 20;

    /**
     * 额外多取的条数，用于抵消解析时被丢弃的行（停牌 0 价、缺字段）。
     *
     * <p>不能只按 {@code limit} 取：涨幅榜头部长期有新上市个股（上市首日
     * +100% 以上），这类行本身是合法的，但一旦中间夹着停牌行被丢弃，
     * 返回条数就会比请求的少。多取几条比「多请求一次」便宜得多。
     */
    private static final int FETCH_PADDING = 5;

    /**
     * 上游声明的编码。
     *
     * <p>响应头是 {@code application/json; charset=gbk}，但正文实际是
     * <b>纯 ASCII + {@code \\uXXXX} 转义</b>（中文名以转义形式返回）。
     * 见 {@link #decode(byte[])}：不依赖这个声明，先严格按 UTF-8 解，
     * 失败再退回 GBK。
     */
    private static final Charset GBK = Charset.forName("GBK");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public SinaMarketDataProvider(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "sina";
    }

    /**
     * 个股榜单。
     *
     * <p>缓存键带源前缀（{@code 'sina:movers:'}）：东财的同名方法也用
     * {@link CacheNames#QUOTE} 这组缓存，两家口径与条数都不同，
     * 共用裸键会让「东财恢复后命中新浪的列表」——板块榜踩过这个坑
     * （见 {@code CacheNames#SECTORS} 的注释），这里从一开始就分开。
     */
    @Override
    @Cacheable(cacheNames = CacheNames.QUOTE,
            key = "'sina:movers:' + #metric.name() + ':' + #ascending + ':' + #limit",
            sync = true)
    public List<Mover> getTopMovers(MoverMetric metric, boolean ascending, int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_MOVERS_LIMIT);
        String sort = sinaSortField(metric);
        String url = HOST + MOVERS_PATH
                + "?page=1&num=" + (effectiveLimit + FETCH_PADDING)
                + "&sort=" + sort
                + "&asc=" + (ascending ? 1 : 0)
                + "&node=hs_a&symbol=&_s_r_a=page";

        JsonNode root = fetchJson(url, "市场榜单", metric.name());
        List<Mover> result = parseMovers(root, metric, ascending);
        if (result.isEmpty()) {
            // 空数组 / 字面量 null 都算「上游这次没给」，抛不可用交给路由降级，
            // 而不是返回空集合让前端以为「今天没有上涨的股票」
            throw new MarketDataUnavailableException(
                    "新浪榜单返回空数据（sort=" + sort + " asc=" + ascending + "）");
        }
        return result.size() > effectiveLimit
                ? List.copyOf(result.subList(0, effectiveLimit))
                : result;
    }

    /**
     * 排序维度 → 新浪参数。
     *
     * <p>为什么不挂到 {@link MoverMetric} 上（那里的 {@code eastmoneyFid()}
     * 就是这么做的）：那个字段的含义是「东财的字段码」，是单一厂商的参数。
     * 再给枚举加一个厂商方法，枚举就会变成「各家参数的拼接表」，
     * 每接一家源都要改模型层——那才是真正的耦合。厂商参数留在厂商实现里。
     */
    private static String sinaSortField(MoverMetric metric) {
        return switch (metric) {
            case CHANGE_PCT -> "changepercent";
            case AMOUNT -> "amount";
        };
    }

    /**
     * 解析榜单数组，并<b>按请求的方向重新排序</b>。
     *
     * <p>包级可见，便于用真实响应样本直接做单测（不需要起本地 HTTP 服务）。
     *
     * <p>三条取舍：
     * <ul>
     *   <li><b>缺价格或价格 ≤ 0 的行丢弃</b>：停牌时 {@code trade} 是 {@code "0.000"}，
     *       上榜会被读成「股价归零」，那是编造信息；</li>
     *   <li><b>非 6 位数字代码丢弃</b>：接口偶尔带上指数或板块行，
     *       它们点不进个股页（前端按 6 位代码跳转）；</li>
     *   <li><b>排序用稳定排序</b>：同分标的保持上游顺序，结果可复现。</li>
     * </ul>
     */
    static List<Mover> parseMovers(JsonNode root, MoverMetric metric, boolean ascending) {
        if (root == null || !root.isArray() || root.isEmpty()) {
            return List.of();
        }
        List<Mover> parsed = new ArrayList<>(root.size());
        for (JsonNode item : root) {
            String code = textOrNull(item, "code");
            String name = textOrNull(item, "name");
            if (code == null || name == null || !isSixDigitCode(code)) {
                continue;
            }
            Double price = readNullableDouble(item, "trade");
            if (price == null || price <= 0) {
                continue;
            }
            parsed.add(new Mover(
                    code,
                    name,
                    price,
                    // 涨跌额 / 涨跌幅 / 成交额缺失时给 0：这三项缺失只是「不显示该项」，
                    // 与「价格缺失」不同——后者没法展示这一行，所以上面直接跳过
                    doubleOrZero(item, "pricechange"),
                    doubleOrZero(item, "changepercent"),
                    doubleOrZero(item, "amount")));
        }

        Comparator<Mover> byMetric = switch (metric) {
            case CHANGE_PCT -> Comparator.comparingDouble(Mover::changePct);
            case AMOUNT -> Comparator.comparingDouble(Mover::amount);
        };
        parsed.sort(ascending ? byMetric : byMetric.reversed());
        return Collections.unmodifiableList(parsed);
    }

    // ==================================================================
    // 能力边界：如实声明不提供的能力
    // ==================================================================

    @Override
    public Quote getQuote(String code) {
        throw new MarketDataUnavailableException("新浪源在本项目中只提供榜单，不支持个股报价");
    }

    @Override
    public List<Bar> getBars(String code, MarketPeriod period, int limit) {
        throw new MarketDataUnavailableException("新浪源在本项目中只提供榜单，不支持 K 线");
    }

    @Override
    public List<IndexSnapshot> getIndices() {
        throw new MarketDataUnavailableException("新浪源在本项目中只提供榜单，不支持大盘指数");
    }

    @Override
    public List<StockBrief> search(String keyword) {
        throw new MarketDataUnavailableException("新浪源在本项目中只提供榜单，不支持股票搜索");
    }

    // ==================================================================
    // HTTP 与解析辅助
    // ==================================================================

    private JsonNode fetchJson(String url, String action, String subject) {
        byte[] bytes;
        try {
            bytes = restClient.get().uri(URI.create(url)).retrieve().body(byte[].class);
        } catch (RuntimeException ex) {
            throw new MarketDataUnavailableException(
                    "行情上游请求失败（" + action + " " + subject + "）", ex);
        }
        if (bytes == null || bytes.length == 0) {
            throw new MarketDataUnavailableException(
                    "行情上游返回空响应（" + action + " " + subject + "）");
        }
        try {
            return objectMapper.readTree(decode(bytes));
        } catch (RuntimeException ex) {
            throw new MarketDataUnavailableException(
                    "行情上游响应不是合法 JSON（" + action + " " + subject + "）", ex);
        }
    }

    /**
     * 字节 → 文本。
     *
     * <p>不盲信响应头里的 {@code charset=gbk}：实测正文是纯 ASCII 的
     * {@code \\uXXXX} 转义，两种解码结果相同；但万一上游哪天改成 UTF-8 原文，
     * 按 GBK 解会得到一串乱码股票名，而且<b>不报错</b>——这种静默错乱最难查。
     * 所以先按 UTF-8 严格解码，抛 {@link CharacterCodingException} 才退回 GBK。
     * 反过来（真 GBK 原文）UTF-8 严格模式一定会失败，因此两条路都能覆盖。
     */
    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            return new String(bytes, GBK);
        }
    }

    private static boolean isSixDigitCode(String code) {
        if (code.length() != 6) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            return null;
        }
        String raw = value.asString().trim();
        return raw.isEmpty() ? null : raw;
    }

    /** 缺失即 0 的数值字段，用于「不显示该项」不影响整行展示的字段。 */
    private static double doubleOrZero(JsonNode node, String field) {
        Double value = readNullableDouble(node, field);
        return value == null ? 0 : value;
    }

    /**
     * 读取数值字段。
     *
     * <p>必须同时接受数字与「数字形式的字符串」：{@code trade} 是
     * {@code "36.300"}（字符串），而 {@code changepercent} 是 {@code 217.031}
     * （数字）——<b>同一个响应里两种形态混用</b>，只处理一种会静默丢掉字段。
     */
    private static Double readNullableDouble(JsonNode node, String field) {
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
}
