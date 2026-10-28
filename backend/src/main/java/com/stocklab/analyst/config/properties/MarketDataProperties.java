package com.stocklab.analyst.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * 行情数据源配置。
 *
 * @param provider        数据源实现：{@code eastmoney}（真实行情）/ {@code mock}（离线演示）
 * @param chain           降级链中各真实源的<b>优先级顺序</b>，默认
 *                        {@code [eastmoney, tencent, tushare]}。兜底源（mock）不需要写，
 *                        由装配代码强制放在链尾，见 {@link #realChain()}。
 * @param timeout         单次行情请求超时（连接 + 读取）
 * @param quoteTtl        实时报价本地缓存有效期，用于折叠同一秒内的并发重复请求
 * @param barTtl          K 线本地缓存有效期
 * @param warmupOnBoot    启动时是否预热全 A 股清单（搜索依赖）
 * @param fallbackToMock  主数据源不可用时是否降级到内置演示数据
 * @param upstreamCooldown 真实数据源请求失败后的冷却时长：冷却期内的请求跳过该源
 *                         直接使用降级链的下一级，到期自动放行一次探测。
 *                         用于避免「上游被风控/宕机时，每个请求都先在死源上白等一次失败」。
 *                         设为 0 禁用冷却。仅作用于真实源，mock 兜底不受影响
 * @param quoteDelayMinutes 对外<b>声明</b>的行情延迟分钟数，0 表示实时。
 *                          合规要求：使用非实时行情时必须在价格附近明示延迟时长。
 *                          本项目默认接的公开接口在交易时段是实时的，故默认 0；
 *                          若换成明确延迟的行情源，必须在这里改，前端会据此渲染标注。
 *                          注意它是声明值不是实测值——「数据是否新鲜」由 {@code ts} 判断，
 *                          两者互不替代
 * @param sectorChain      <b>板块榜专用</b>的真实源优先链，默认 {@code [tencent, eastmoney]}。
 *                         与 {@code chain} 分开，是因为两个源给出的板块口径不同：
 *                         腾讯是 31 个一级行业，东财的行业板块接口把一级/二级/三级
 *                         混在 496 个条目里且没有层级字段——后者的涨幅榜会被
 *                         「只有两三只成分股」的微板块和 Ⅱ/Ⅲ 重复条目占据。
 *                         而报价、K 线、搜索仍应按 {@code chain} 走（东财最全），
 *                         所以这里必须是<b>按操作</b>的独立顺序，不是全局顺序
 * @param moversChain      <b>个股榜单专用</b>的真实源优先链，默认 {@code [eastmoney, sina]}。
 *                         与 {@code chain} 分开的原因不是口径而是<b>能力边界</b>：
 *                         榜单只有东财与新浪提供（腾讯的个股榜接口实测返回空列表），
 *                         而新浪在本项目里<b>只</b>支持榜单，放进主链会让每次
 *                         报价/K 线降级都多走一次注定失败的调用。
 *                         排在东财之后是因为后者能直接在上游排序、
 *                         且字段口径与项目其它地方（代码/价格/成交额单位）完全一致
 */
@ConfigurationProperties(prefix = "app.market-data")
public record MarketDataProperties(
        String provider,
        List<String> chain,
        Duration timeout,
        Duration quoteTtl,
        Duration barTtl,
        boolean warmupOnBoot,
        boolean fallbackToMock,
        Duration upstreamCooldown,
        int quoteDelayMinutes,
        List<String> sectorChain,
        List<String> moversChain
) {

    /** 兜底源名字。它不由配置决定，而是装配时强制置于链尾。 */
    public static final String FALLBACK_PROVIDER = "mock";

    /** 默认降级链：字段最全的主源 → 无风控的备用真实源 → 有授权协议的补充源。 */
    private static final List<String> DEFAULT_CHAIN = List.of("eastmoney", "tencent", "tushare");

    /**
     * 板块榜默认优先链：腾讯在前。
     *
     * <p>理由见 {@code sectorChain} 参数的说明——这是「口径」问题而不是
     * 「可用性」问题：东财 496 个混层级板块里，涨幅头部长期是
     * 只含两三只成分股的微板块，且 Ⅱ/Ⅲ 层级重复出现，没有解释力。
     */
    private static final List<String> DEFAULT_SECTOR_CHAIN = List.of("tencent", "eastmoney");

    /**
     * 个股榜单默认优先链：东财在前，新浪兜底。
     *
     * <p>与板块链相反的顺序，原因是两者的差异点不同：板块是「口径」问题
     * （东财混层级），个股榜单是「可用性」问题（东财被风控时才需要备用源）。
     * 东财能直接在上游排序、字段与项目其它地方口径一致，所以正常情况下用它；
     * 新浪只在它不可用时接管，避免「两个源给出的上榜名单不同」造成的日常抖动。
     */
    private static final List<String> DEFAULT_MOVERS_CHAIN = List.of("eastmoney", "sina");

    /** 当前装配支持的数据源名字，用于校验配置里的拼写错误。 */
    public static final List<String> SUPPORTED_PROVIDERS =
            List.of("eastmoney", "tencent", "sina", "tushare", FALLBACK_PROVIDER);

    public MarketDataProperties {
        if (provider == null || provider.isBlank()) {
            provider = "eastmoney";
        }
        provider = provider.trim().toLowerCase(Locale.ROOT);
        if (chain == null || chain.isEmpty()) {
            chain = DEFAULT_CHAIN;
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(6);
        }
        if (quoteTtl == null) {
            quoteTtl = Duration.ofSeconds(3);
        }
        if (barTtl == null) {
            barTtl = Duration.ofMinutes(5);
        }
        if (upstreamCooldown == null) {
            upstreamCooldown = Duration.ofSeconds(30);
        }
        if (sectorChain == null || sectorChain.isEmpty()) {
            sectorChain = DEFAULT_SECTOR_CHAIN;
        }
        if (moversChain == null || moversChain.isEmpty()) {
            moversChain = DEFAULT_MOVERS_CHAIN;
        }
    }

    /** 是否以真实源为主数据源（{@code provider=mock} 表示离线演示配置）。 */
    public boolean preferRealProvider() {
        return !FALLBACK_PROVIDER.equals(provider);
    }

    /**
     * 解析出配置的<b>真实源</b>优先级链（已去掉兜底源）。
     *
     * <p><b>为什么兜底源不允许出现在这里</b>：mock 是「最后的保障」，
     * 它的位置由装配代码强制钉在链尾。若允许配置，一个
     * {@code chain: mock,eastmoney} 会让所有请求先吃演示数据、
     * 真实源永远排在后面——系统看起来「一直有数据」，但全是编造的。
     * 配置里写了 mock 会被忽略（并打 WARN），而不是照单执行。
     *
     * <p>顺序可配的实际价值：东财存在 IP 级风控（README 记录过实测），
     * 触发后最需要的能力是「把腾讯提为主源」。硬编码时这件事必须改代码重新发版，
     * 而这恰恰是项目最可能遇到的故障场景。
     */
    public List<String> realChain() {
        return normalizeRealChain(chain, DEFAULT_CHAIN);
    }

    /**
     * 解析出板块榜使用的真实源优先链（同样剔除兜底源）。
     *
     * <p>与 {@link #realChain()} 使用同一套归一规则，只是默认值不同——
     * 「名字合法性」的校验在装配层统一做（写错名字必须启动失败），
     * 这里只负责去空白、去兜底源、去重。
     */
    public List<String> sectorRealChain() {
        return normalizeRealChain(sectorChain, DEFAULT_SECTOR_CHAIN);
    }

    /**
     * 解析出个股榜单使用的真实源优先链（同样剔除兜底源）。
     *
     * <p>归一规则与另外两条链完全一致，只有默认值不同。
     */
    public List<String> moversRealChain() {
        return normalizeRealChain(moversChain, DEFAULT_MOVERS_CHAIN);
    }

    private static List<String> normalizeRealChain(List<String> raw, List<String> fallback) {
        List<String> normalized = raw.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .filter(name -> !FALLBACK_PROVIDER.equals(name))
                .distinct()
                .toList();
        return normalized.isEmpty() ? fallback : normalized;
    }
}
