package com.stocklab.analyst.config;

import com.stocklab.analyst.config.properties.MarketDataProperties;
import com.stocklab.analyst.config.properties.TushareProperties;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.ResilientMarketDataProvider;
import com.stocklab.analyst.market.eastmoney.EastMoneyMarketDataProvider;
import com.stocklab.analyst.market.mock.MockMarketDataProvider;
import com.stocklab.analyst.market.sina.SinaMarketDataProvider;
import com.stocklab.analyst.market.tencent.TencentMarketDataProvider;
import com.stocklab.analyst.market.tushare.TushareMarketDataProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行情数据源装配。
 *
 * <p>五个 Bean 的职责分离：
 * <ul>
 *   <li>{@link MockMarketDataProvider} —— 始终注册，作为链尾兜底</li>
 *   <li>{@link EastMoneyMarketDataProvider} —— 主数据源（字段最全：搜索/榜单/全市场清单）</li>
 *   <li>{@link TencentMarketDataProvider} —— 备用真实数据源。东财有 IP 级风控
 *       （服务端直接断连，2026-09-19 / 2026-09-21 两次实测），一旦触发，
 *       原先「东财 → mock」两级链会让所有非内置标的直接不可用；
 *       插入腾讯这一级后，报价/K 线/指数/板块榜仍由真实数据服务，
 *       只有搜索与个股榜单才需要再找别的源。</li>
 *   <li>{@link SinaMarketDataProvider} —— 个股榜单的备用真实源。<b>只</b>覆盖榜单，
 *       因此不参与主链，只挂在 {@code getTopMovers} 的专用链上。</li>
 *   <li>{@link ResilientMarketDataProvider} —— 对外唯一暴露的 {@code @Primary} Bean，
 *       包装多级降级链。业务层注入 {@code MarketDataProvider} 时拿到的就是它。</li>
 * </ul>
 *
 * <p>把「mock 始终存在」作为设计前提，而不是只在配置成 mock 时才创建：
 * 降级能力应该是运行期随时可用的，不能依赖启动时的开关。
 */
@Configuration
public class MarketDataSupportConfig {

    private static final Logger log = LoggerFactory.getLogger(MarketDataSupportConfig.class);

    @Bean
    public MockMarketDataProvider mockMarketDataProvider() {
        return new MockMarketDataProvider();
    }

    @Bean
    public EastMoneyMarketDataProvider eastMoneyMarketDataProvider(
            @Qualifier("marketRestClient") RestClient marketRestClient,
            ObjectMapper objectMapper) {
        return new EastMoneyMarketDataProvider(marketRestClient, objectMapper);
    }

    @Bean
    public TencentMarketDataProvider tencentMarketDataProvider(
            @Qualifier("tencentRestClient") RestClient tencentRestClient,
            ObjectMapper objectMapper) {
        return new TencentMarketDataProvider(tencentRestClient, objectMapper);
    }

    /**
     * 新浪行情源：<b>只</b>提供个股榜单（涨跌幅榜 / 成交额榜）。
     *
     * <p>它不进主链——报价、K 线、指数、搜索这四个方法都会抛「不可用」，
     * 放进主链只会让每次降级多走一次注定失败的调用。它的位置由
     * {@code app.market-data.movers-chain} 决定（见
     * {@link SinaMarketDataProvider} 的类注释）。
     */
    @Bean
    public SinaMarketDataProvider sinaMarketDataProvider(
            @Qualifier("sinaRestClient") RestClient sinaRestClient,
            ObjectMapper objectMapper) {
        return new SinaMarketDataProvider(sinaRestClient, objectMapper);
    }

    @Bean
    public TushareMarketDataProvider tushareMarketDataProvider(
            @Qualifier("tushareRestClient") RestClient tushareRestClient,
            ObjectMapper objectMapper,
            TushareProperties tushareProperties) {
        return new TushareMarketDataProvider(tushareRestClient, objectMapper, tushareProperties);
    }

    /**
     * 对外暴露的行情数据源，默认降级链
     * {@code eastmoney → tencent → tushare → mock}（可由 {@code app.market-data.chain} 调整）。
     *
     * <p>Tushare 是「有授权协议」的真实数据源，但免费档不提供盘中实时报价与榜单，
     * 只覆盖日/周/月 K 线、指数与股票搜索。因此它默认排在腾讯之后、mock 之前：
     * 东财与腾讯都被风控时，日线 K 线与指数仍由 Tushare 提供真实数据，
     * 而不是直接落到演示数据。Tushare 未配置 Token 时（{@code enabled=false}）
     * 不加入链，避免每次调用都白走一次「未配置」的失败分支。
     *
     * <p><b>顺序为什么做成可配置</b>：东财存在 IP 级风控（README 记录过
     * 2026-09-19 / 09-21 两次实测），触发后最需要的能力是「把腾讯提为主源」。
     * 硬编码时这件事必须改代码、重新打包、重新发版，而这恰恰是
     * 项目最可能遇到的故障场景——最需要快速反应的时候，流程最长。
     *
     * <p>两条不可配置的约束（刻意不做成开关）：
     * <ul>
     *   <li><b>兜底源（mock）恒定在链尾</b>。写进 {@code chain} 会被忽略并打 WARN。
     *       允许把 mock 排到前面，等于允许配出「一直有数据但全是编造的」——
     *       这种配置不会报错，只会安静地误导用户。</li>
     *   <li><b>未知源名直接启动失败</b>。写错一个字母时，静默跳过会让
     *       「三级冗余」悄悄变成「一级」——冗余是这次装配的全部意义，
     *       少了它必须让人当场知道，而不是等到真的故障时才发现没有下一级。</li>
     * </ul>
     *
     * <p>标记 {@code @Primary}：容器里同时存在多个 {@code MarketDataProvider} 实现，
     * 业务层按接口注入时必须有一个默认候选，否则启动即报
     * {@code NoUniqueBeanDefinitionException}。
     *
     * <p><b>两条按操作的独立链</b>（只影响各自的操作，不改全局顺序）：
     * <ul>
     *   <li>{@code getSectors} ← {@code sector-chain}（默认 {@code tencent → eastmoney}）：
     *       口径问题，腾讯的 31 个一级行业比东财 496 个混层级板块更适合做榜单；</li>
     *   <li>{@code getTopMovers} ← {@code movers-chain}（默认 {@code eastmoney → sina}）：
     *       能力边界问题，新浪只提供榜单，因此不能进主链，但必须在这里顶住
     *       东财被风控时的空白。</li>
     * </ul>
     *
     * <p>配置开关：
     * <ul>
     *   <li>{@code provider=mock}：链退化为单级（mock 为主源），不注册真实源；</li>
     *   <li>{@code fallback-to-mock=false}：路由内部把链截断为只用主源。</li>
     * </ul>
     */
    @Bean
    @Primary
    public MarketDataProvider marketDataProvider(
            MarketDataProperties properties,
            MockMarketDataProvider mockProvider,
            EastMoneyMarketDataProvider eastMoneyProvider,
            TencentMarketDataProvider tencentProvider,
            SinaMarketDataProvider sinaProvider,
            TushareMarketDataProvider tushareProvider,
            MeterRegistry meterRegistry) {

        long cooldownMillis = properties.upstreamCooldown().toMillis();
        boolean preferReal = properties.preferRealProvider();

        MarketDataProvider router;
        if (preferReal) {
            List<MarketDataProvider> chain = new ArrayList<>(5);
            List<String> assembled = new ArrayList<>(5);
            appendRealProviders(properties.realChain(), chain, assembled,
                    eastMoneyProvider, tencentProvider, sinaProvider, tushareProvider);
            // 兜底源恒定在链尾，不由配置决定
            chain.add(mockProvider);
            assembled.add(MarketDataProperties.FALLBACK_PROVIDER);

            // 按操作的独立链。只有顺序与主链不同才登记覆盖，
            // 否则等于给同一条件写两份配置，日后改一处忘一处就会不一致。
            Map<String, List<MarketDataProvider>> actionChains = new LinkedHashMap<>(2);
            List<String> assembledSector = registerActionChain(
                    "getSectors", properties.sectorRealChain(), assembled, actionChains, mockProvider,
                    eastMoneyProvider, tencentProvider, sinaProvider, tushareProvider);
            List<String> assembledMovers = registerActionChain(
                    "getTopMovers", properties.moversRealChain(), assembled, actionChains, mockProvider,
                    eastMoneyProvider, tencentProvider, sinaProvider, tushareProvider);

            router = new ResilientMarketDataProvider(
                    List.copyOf(chain), properties.fallbackToMock(), cooldownMillis, meterRegistry,
                    properties.quoteDelayMinutes(), actionChains);
            log.info("行情数据源降级链：{} fallbackToMock={} 冷却={}s 声明延迟={}分钟",
                    String.join(" → ", assembled), properties.fallbackToMock(),
                    cooldownMillis / 1000, properties.quoteDelayMinutes());
            if (assembledSector != null) {
                // 单独打一行：这两个操作的链与主链顺序不同，是排障时最容易看错的一点
                log.info("板块榜专用降级链：{}（只影响 getSectors）",
                        String.join(" → ", assembledSector));
            }
            if (assembledMovers != null) {
                log.info("个股榜单专用降级链：{}（只影响 getTopMovers）",
                        String.join(" → ", assembledMovers));
            }
        } else {
            router = new ResilientMarketDataProvider(
                    List.of(mockProvider), properties.fallbackToMock(), cooldownMillis, meterRegistry,
                    properties.quoteDelayMinutes());
            log.info("行情数据源：mock（离线演示配置，不注册真实源）");
        }
        return router;
    }

    /** 按配置顺序把真实源追加进 {@code target}，并把实际加入的名字记到 {@code names}。 */
    private void appendRealProviders(List<String> configured,
                                     List<MarketDataProvider> target,
                                     List<String> names,
                                     EastMoneyMarketDataProvider eastMoneyProvider,
                                     TencentMarketDataProvider tencentProvider,
                                     SinaMarketDataProvider sinaProvider,
                                     TushareMarketDataProvider tushareProvider) {
        for (String name : configured) {
            MarketDataProvider provider = resolveRealProvider(
                    name, eastMoneyProvider, tencentProvider, sinaProvider, tushareProvider);
            if (provider == null) {
                continue;
            }
            target.add(provider);
            names.add(name);
        }
    }

    /**
     * 为一个操作登记专用链（末尾自动补兜底源）。
     *
     * @return 该链实际装配出的源名顺序；与主链完全相同时<b>不登记</b>并返回 {@code null}
     *         ——两份一模一样的配置迟早会有一份忘记改
     */
    private List<String> registerActionChain(String action,
                                             List<String> configured,
                                             List<String> mainAssembled,
                                             Map<String, List<MarketDataProvider>> actionChains,
                                             MockMarketDataProvider mockProvider,
                                             EastMoneyMarketDataProvider eastMoneyProvider,
                                             TencentMarketDataProvider tencentProvider,
                                             SinaMarketDataProvider sinaProvider,
                                             TushareMarketDataProvider tushareProvider) {
        List<MarketDataProvider> actionChain = new ArrayList<>(4);
        List<String> assembled = new ArrayList<>(4);
        appendRealProviders(configured, actionChain, assembled,
                eastMoneyProvider, tencentProvider, sinaProvider, tushareProvider);
        actionChain.add(mockProvider);
        assembled.add(MarketDataProperties.FALLBACK_PROVIDER);
        if (assembled.equals(mainAssembled)) {
            return null;
        }
        actionChains.put(action, List.copyOf(actionChain));
        return assembled;
    }

    /**
     * 把配置里的源名字解析成 Bean。
     *
     * @return 对应的 Provider；该源当前不可用（如 Tushare 未配 Token）时返回 {@code null}，表示跳过
     * @throws IllegalStateException 名字不在支持列表内——配置错误必须让启动失败而不是被吞掉
     */
    private MarketDataProvider resolveRealProvider(String name,
                                                  EastMoneyMarketDataProvider eastMoneyProvider,
                                                  TencentMarketDataProvider tencentProvider,
                                                  SinaMarketDataProvider sinaProvider,
                                                  TushareMarketDataProvider tushareProvider) {
        return switch (name) {
            case "eastmoney" -> eastMoneyProvider;
            case "tencent" -> tencentProvider;
            case "sina" -> sinaProvider;
            case "tushare" -> {
                if (!tushareProvider.available()) {
                    // 未配置 Token 时加入链只会让每次降级都多一次注定失败的调用
                    log.warn("降级链中配置了 tushare，但未提供 TUSHARE_TOKEN，本次不加入链");
                    yield null;
                }
                yield tushareProvider;
            }
            default -> throw new IllegalStateException(
                    "降级链配置中的「" + name + "」不是受支持的数据源；可选："
                            + MarketDataProperties.SUPPORTED_PROVIDERS
                            + "（其中 mock 为兜底源，恒定在链尾，无需配置）");
        };
    }
}
