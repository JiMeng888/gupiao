package com.stocklab.analyst.market;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.mock.MockMarketDataProvider;
import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.IndexSnapshot;
import com.stocklab.analyst.market.model.MarketPeriod;
import com.stocklab.analyst.market.model.Mover;
import com.stocklab.analyst.market.model.MoverMetric;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.market.model.StockBrief;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link ResilientMarketDataProvider} 单测。
 *
 * <p><b>为什么这个类最该测：它是全工程唯一会「悄悄换掉数据来源」的地方。</b>
 * 主数据源挂掉时它不报错，而是返回演示数据。这意味着
 * <u>它出问题的时候，接口全都返回 200，日志里一行 ERROR 都没有</u>，
 * 用户看到的是一串看起来完全正常的假价格。
 *
 * <p>本类固化的核心契约有四条，每一条失效都不会让测试之外的人察觉：
 * <ol>
 *   <li><b>「查无此股」绝不能降级。</b>上游明确说没有这个标的（404）
 *       必须原样上抛；一旦降级成「给你编一个」，用户会拿到不存在的股票的价格。
 *       区分 {@code QUOTE_NOT_FOUND} 与 {@code MARKET_DATA_UNAVAILABLE}
 *       是整个错误模型的分界线。</li>
 *   <li><b>降级状态必须能自动归位。</b>实现只记「最近一次由谁服务」，
 *       主源恢复后 {@code degraded()} 必须回到 false。写成「曾经降级过就永久置位」
 *       会让界面在一次网络抖动后永远挂着「演示数据」警告。</li>
 *   <li><b>降级是逐操作的。</b>真实场景里完全可能「K 线可用、报价被风控拦」，
 *       所以状态按 action 分开记录，不能用一个全局布尔表示。</li>
 *   <li><b>降级必须有痕迹。</b>每次降级都要累加
 *       {@code app.market_data.fallback} 指标——告警配置依赖它。</li>
 * </ol>
 *
 * <p>这里用真实的可编程假数据源而不是 Mockito：{@code name()} 会被
 * {@code degraded()}、{@code markServed()}、日志多处调用，用 mock 就得在
 * 每条用例里打桩，未用到的又会触发严格桩校验，反而把「路由器到底调了谁」
 * 这件正事淹没在桩噪声里。假数据源还能顺手记录调用次数，
 * 让「降级路径有没有走」变成可直接断言的事实。
 */
@DisplayName("带降级的行情路由器")
class ResilientMarketDataProviderTest {

    private static final String PRIMARY_NAME = "eastmoney";
    private static final String FALLBACK_NAME = "mock";

    private static final String ACTION_QUOTE = "getQuote";
    private static final String ACTION_BARS = "getBars";
    private static final String ACTION_INDICES = "getIndices";
    private static final String ACTION_SEARCH = "search";

    private SimpleMeterRegistry registry;
    private FakeProvider primary;
    private FakeProvider fallback;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        primary = new FakeProvider(PRIMARY_NAME);
        fallback = new FakeProvider(FALLBACK_NAME);
    }

    private ResilientMarketDataProvider router(boolean fallbackEnabled) {
        return new ResilientMarketDataProvider(primary, fallback, fallbackEnabled, registry);
    }

    /** 降级计数器当前值；指标尚未创建时返回 {@code null}。 */
    private Double fallbackMetric(String action) {
        return metric("app.market_data.fallback", action);
    }

    /** 上游不可用计数器当前值（关闭降级时走这条）。 */
    private Double unavailableMetric(String action) {
        return metric("app.market_data.unavailable", action);
    }

    private Double metric(String name, String action) {
        Counter counter = registry.find(name).tag("action", action).counter();
        return counter == null ? null : counter.count();
    }

    // ==================================================================
    @Nested
    @DisplayName("主数据源正常")
    class PrimaryHealthy {

        @Test
        @DisplayName("报价直接来自主数据源")
        void returnsQuoteFromPrimary() {
            Quote quote = router(true).getQuote("600519");

            assertThat(quote.name()).isEqualTo(PRIMARY_NAME + "-600519");
        }

        @Test
        @DisplayName("主源成功时不碰降级源")
        void doesNotTouchFallback() {
            ResilientMarketDataProvider router = router(true);

            router.getQuote("600519");
            router.getBars("600519", MarketPeriod.DAILY, 60);
            router.getIndices();
            router.search("茅台");

            assertThat(fallback.totalCalls())
                    .as("主源健康时调用降级源说明路由判断反了")
                    .isZero();
        }

        @Test
        @DisplayName("按操作记录服务方为主数据源")
        void marksActionServedByPrimary() {
            ResilientMarketDataProvider router = router(true);

            router.getQuote("600519");

            assertThat(router.servingByAction()).containsEntry(ACTION_QUOTE, PRIMARY_NAME);
        }

        @Test
        @DisplayName("成功后不处于降级状态")
        void notDegradedAfterSuccess() {
            ResilientMarketDataProvider router = router(true);

            router.getQuote("600519");

            assertThat(router.degraded()).isFalse();
        }

        @Test
        @DisplayName("K 线 / 指数 / 检索都走主源")
        void otherOperationsUsePrimary() {
            ResilientMarketDataProvider router = router(true);

            assertThat(router.getBars("600519", MarketPeriod.DAILY, 60).get(0).date())
                    .isEqualTo(PRIMARY_NAME + "-bar");
            assertThat(router.getIndices().get(0).code()).isEqualTo(PRIMARY_NAME + "-idx");
            assertThat(router.search("600").get(0).code()).isEqualTo(PRIMARY_NAME + "-brief");

            assertThat(primary.callCount(ACTION_BARS)).isEqualTo(1);
            assertThat(primary.callCount(ACTION_INDICES)).isEqualTo(1);
            assertThat(primary.callCount(ACTION_SEARCH)).isEqualTo(1);
        }

        @Test
        @DisplayName("周期与条数原样透传给主源")
        void passesBarsArgumentsThrough() {
            router(true).getBars("600519", MarketPeriod.MIN15, 240);

            assertThat(primary.lastBarsCall()).isEqualTo("600519|MIN15|240");
        }

        @Test
        @DisplayName("成功路径不创建降级指标")
        void successCreatesNoFallbackMetric() {
            router(true).getQuote("600519");

            assertThat(fallbackMetric(ACTION_QUOTE))
                    .as("没降级却建了指标，会让基于该指标的告警长期误报")
                    .isNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("降级")
    class Fallback {

        @Test
        @DisplayName("主源不可用时改用降级源取报价")
        void fallsBackForQuote() {
            primary.failAlways(unavailable("连接超时"));

            Quote quote = router(true).getQuote("600519");

            assertThat(quote.name()).isEqualTo(FALLBACK_NAME + "-600519");
            assertThat(fallback.callCount(ACTION_QUOTE)).isEqualTo(1);
        }

        @Test
        @DisplayName("K 线 / 指数 / 检索同样降级")
        void fallsBackForEveryOperation() {
            primary.failAlways(unavailable("上游拒连"));
            ResilientMarketDataProvider router = router(true);

            assertThat(router.getBars("600519", MarketPeriod.DAILY, 60).get(0).date())
                    .isEqualTo(FALLBACK_NAME + "-bar");
            assertThat(router.getIndices().get(0).code()).isEqualTo(FALLBACK_NAME + "-idx");
            assertThat(router.search("600").get(0).code()).isEqualTo(FALLBACK_NAME + "-brief");

            assertThat(fallback.totalCalls()).isEqualTo(3);
        }

        @Test
        @DisplayName("降级后按操作标记服务方为降级源")
        void marksActionServedByFallback() {
            primary.failAlways(unavailable("连接超时"));
            ResilientMarketDataProvider router = router(true);

            router.getQuote("600519");

            assertThat(router.servingByAction()).containsEntry(ACTION_QUOTE, FALLBACK_NAME);
        }

        @Test
        @DisplayName("降级后 degraded() 为 true")
        void reportsDegraded() {
            primary.failAlways(unavailable("连接超时"));
            ResilientMarketDataProvider router = router(true);

            router.getQuote("600519");

            assertThat(router.degraded()).isTrue();
        }

        @Test
        @DisplayName("每次降级都累加指标，且带 action / primary 标签")
        void countsEachFallbackOccurrence() {
            primary.failAlways(unavailable("连接超时"));
            ResilientMarketDataProvider router = router(true);

            router.getQuote("600519");
            router.getQuote("000001");
            router.getQuote("300750");

            assertThat(fallbackMetric(ACTION_QUOTE)).isEqualTo(3.0);
            assertThat(registry.find("app.market_data.fallback")
                    .tag("action", ACTION_QUOTE)
                    .tag("primary", PRIMARY_NAME)
                    .counter())
                    .as("告警依赖 primary 标签区分是哪个上游挂了")
                    .isNotNull();
        }

        /**
         * 这一条固化实现注释里最容易被忽略的那句话：
         * 「只记录最近一次，不累积历史」。
         *
         * <p>如果哪天有人把 {@code servingByAction} 改成「降级过就永久置位」，
         * 用户界面会在一次网络抖动后永远挂着「演示数据」警告，而且
         * 没人会报 bug——因为看起来只是「标记有点多余」。
         */
        @Test
        @DisplayName("主源恢复后降级状态自动归位，不会永久置位")
        void recoversAfterPrimaryHeals() {
            primary.failAlways(unavailable("连接超时"));
            ResilientMarketDataProvider router = router(true);

            router.getQuote("600519");
            assertThat(router.degraded()).isTrue();

            primary.stopFailing();
            Quote quote = router.getQuote("600519");

            assertThat(quote.name()).isEqualTo(PRIMARY_NAME + "-600519");
            assertThat(router.degraded())
                    .as("降级状态必须反映「当前」，不是「曾经」")
                    .isFalse();
            assertThat(router.servingByAction()).containsEntry(ACTION_QUOTE, PRIMARY_NAME);
        }

        /**
         * 逐操作隔离：真实场景里「K 线接口可用、实时报价接口被风控拦」很常见。
         *
         * <p>用一个全局布尔描述状态，无论填什么都会误导一半的调用方。
         */
        @Test
        @DisplayName("降级状态按操作隔离，报价降级不影响 K 线")
        void degradationIsTrackedPerAction() {
            primary.failOnly(ACTION_QUOTE, unavailable("报价接口被风控拦截"));
            ResilientMarketDataProvider router = router(true);

            router.getQuote("600519");
            router.getBars("600519", MarketPeriod.DAILY, 60);

            assertThat(router.servingByAction())
                    .containsEntry(ACTION_QUOTE, FALLBACK_NAME)
                    .containsEntry(ACTION_BARS, PRIMARY_NAME);
            assertThat(primary.callCount(ACTION_BARS)).isEqualTo(1);
        }

        @Test
        @DisplayName("只有 MarketDataUnavailableException 触发降级")
        void onlyUnavailableExceptionTriggersFallback() {
            primary.failAlways(new IllegalStateException("代码 bug"));

            assertThatThrownBy(() -> router(true).getQuote("600519"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fallback.callCount(ACTION_QUOTE))
                    .as("把自身 bug 也降级会把真实故障掩盖成「上游抖动」")
                    .isZero();
        }
    }

    // ==================================================================
    /**
     * 本类最重要的一组。
     *
     * <p>{@code QUOTE_NOT_FOUND} 表示「上游正常工作，但没有这个标的」，
     * 是用户的输入问题（404）；{@code MARKET_DATA_UNAVAILABLE} 表示
     * 「上游炸了」，是我们的服务问题（503）。把前者降级成「给你编一个」，
     * 用户会拿着一个不存在的代码看到一串像模像样的价格。
     */
    @Nested
    @DisplayName("查无此股：绝不降级")
    class NotFoundIsNotUnavailable {

        private BizException notFound() {
            return new BizException(ErrorCode.QUOTE_NOT_FOUND, "未找到标的 999999");
        }

        @Test
        @DisplayName("主源的 QUOTE_NOT_FOUND 原样上抛")
        void propagatesNotFound() {
            primary.failAlways(notFound());

            BizException ex = catchThrowableOfType(
                    () -> router(true).getQuote("999999"), BizException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getMessage()).isEqualTo("未找到标的 999999");
        }

        @Test
        @DisplayName("错误码保持 QUOTE_NOT_FOUND，不被改写成 503")
        void keepsNotFoundErrorCode() {
            primary.failAlways(notFound());

            BizException ex = catchThrowableOfType(
                    () -> router(true).getQuote("999999"), BizException.class);

            assertThat(ex.getCode()).isEqualTo(ErrorCode.QUOTE_NOT_FOUND.name());
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("查无此股时不触碰降级源")
        void doesNotTouchFallback() {
            primary.failAlways(notFound());

            catchThrowableOfType(() -> router(true).getQuote("999999"), BizException.class);

            assertThat(fallback.callCount(ACTION_QUOTE))
                    .as("降级源会把未知代码编出一份数据，必须完全绕开")
                    .isZero();
        }

        @Test
        @DisplayName("查无此股不把状态标记为降级")
        void doesNotMarkDegraded() {
            primary.failAlways(notFound());
            ResilientMarketDataProvider router = router(true);

            catchThrowableOfType(() -> router.getQuote("999999"), BizException.class);

            assertThat(router.degraded()).isFalse();
            assertThat(router.servingByAction()).isEmpty();
        }

        @Test
        @DisplayName("查无此股不创建降级指标")
        void doesNotCreateFallbackMetric() {
            primary.failAlways(notFound());

            catchThrowableOfType(() -> router(true).getQuote("999999"), BizException.class);

            assertThat(fallbackMetric(ACTION_QUOTE))
                    .as("误计会让告警持续指向「上游挂了」，而真实原因是用户敲错了代码")
                    .isNull();
        }

        @Test
        @DisplayName("K 线的查无此股同样上抛")
        void propagatesNotFoundForBars() {
            primary.failAlways(notFound());

            BizException ex = catchThrowableOfType(
                    () -> router(true).getBars("999999", MarketPeriod.DAILY, 60),
                    BizException.class);

            assertThat(ex.getCode()).isEqualTo(ErrorCode.QUOTE_NOT_FOUND.name());
            assertThat(fallback.callCount(ACTION_BARS)).isZero();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("关闭降级")
    class FallbackDisabled {

        @Test
        @DisplayName("主源不可用时抛 MARKET_DATA_UNAVAILABLE，而不是返回演示数据")
        void throwsInsteadOfFabricating() {
            primary.failAlways(unavailable("连接被拒绝"));

            MarketDataUnavailableException ex = catchThrowableOfType(
                    () -> router(false).getQuote("600519"), MarketDataUnavailableException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getCode()).isEqualTo(ErrorCode.MARKET_DATA_UNAVAILABLE.name());
        }

        @Test
        @DisplayName("关闭降级时不调用降级源")
        void doesNotCallFallback() {
            primary.failAlways(unavailable("连接被拒绝"));

            catchThrowableOfType(() -> router(false).getQuote("600519"),
                    MarketDataUnavailableException.class);

            assertThat(fallback.callCount(ACTION_QUOTE)).isZero();
        }

        /**
         * 关闭降级是为了让自动化测试/压测看到**真实**的上游可用性，
         * 所以「上游确实挂了」这件事必须仍然被记下来，
         * 否则关掉降级等于顺带关掉了监控。
         *
         * <p>但它必须记在「不可用」这个指标上，而不是「降级」上：
         * 告警若按 {@code app.market_data.fallback} 配置，
         * 记错指标会让「降级兜住了」和「一个请求都没兜住」看起来一模一样。
         */
        @Test
        @DisplayName("关闭降级时记录上游不可用，不记录降级")
        void stillRecordsUpstreamUnavailable() {
            primary.failAlways(unavailable("连接被拒绝"));

            catchThrowableOfType(() -> router(false).getQuote("600519"),
                    MarketDataUnavailableException.class);

            assertThat(unavailableMetric(ACTION_QUOTE)).isEqualTo(1.0);
            assertThat(fallbackMetric(ACTION_QUOTE))
                    .as("没有发生降级，fallback 指标不该被累加")
                    .isNull();
        }

        @Test
        @DisplayName("关闭降级时保留上游异常为 cause，不丢因果链")
        void keepsCauseChain() {
            MarketDataUnavailableException upstream = unavailable("连接被拒绝");
            primary.failAlways(upstream);

            MarketDataUnavailableException ex = catchThrowableOfType(
                    () -> router(false).getQuote("600519"), MarketDataUnavailableException.class);

            assertThat(ex.getCause())
                    .as("丢掉 cause 后，排障时只能看到「已关闭降级」这句无信息的兜底文案")
                    .isSameAs(upstream);
        }

        /**
         * 【正确语义】关闭降级时不应对外报告「正在用演示数据」。
         *
         * <p>此时没有任何请求被降级源服务过，响应是 503、前端拿不到任何报价。
         * 如果 {@code degraded()} 返回 true，前端会给页面打上「演示数据」标记
         * （见 {@code QuoteService.sourceStatus()}），而实际上根本没有数据可展示——
         * 这比不报状态更糟：它会让人以为「降级兜住了」。
         */
        @Test
        @DisplayName("关闭降级时不报告处于降级状态")
        void doesNotReportDegraded() {
            primary.failAlways(unavailable("连接被拒绝"));
            ResilientMarketDataProvider router = router(false);

            catchThrowableOfType(() -> router.getQuote("600519"),
                    MarketDataUnavailableException.class);

            assertThat(router.servingByAction())
                    .as("没有任何请求由 %s 服务过，不该把它记成服务方", FALLBACK_NAME)
                    .isEmpty();
            assertThat(router.degraded()).isFalse();
        }
    }

    // ==================================================================
    /**
     * 用真实的 {@link MockMarketDataProvider} 当降级源，验证端到端的
     * 「降级也不会编造不存在的股票」。
     *
     * <p>前面几组用假数据源证明了路由器<b>不会</b>把 QUOTE_NOT_FOUND
     * 当成不可用来降级；这一组证明即使降级真的发生了，
     * 降级源自身对未知代码也会抛 QUOTE_NOT_FOUND——两道防线都要有。
     */
    @Nested
    @DisplayName("与真实演示数据源协作")
    class WithRealFallbackProvider {

        @Test
        @DisplayName("降级源对未知代码抛 QUOTE_NOT_FOUND，不编造价格")
        void realFallbackReportsNotFound() {
            MockMarketDataProvider realFallback = new MockMarketDataProvider();
            primary.failAlways(unavailable("上游不可达"));
            ResilientMarketDataProvider router =
                    new ResilientMarketDataProvider(primary, realFallback, true, registry);

            BizException ex = catchThrowableOfType(
                    () -> router.getQuote("999999"), BizException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getCode()).isEqualTo(ErrorCode.QUOTE_NOT_FOUND.name());
        }

        @Test
        @DisplayName("降级源认得的内置标的能正常返回，且状态标记为降级")
        void realFallbackServesKnownCode() {
            MockMarketDataProvider realFallback = new MockMarketDataProvider();
            // mock 源的 search("") 返回空列表（见其实现），所以内置代码写死
            String knownCode = "600519";

            primary.failAlways(unavailable("上游不可达"));
            ResilientMarketDataProvider router =
                    new ResilientMarketDataProvider(primary, realFallback, true, registry);

            Quote quote = router.getQuote(knownCode);

            assertThat(quote.code()).isEqualTo(knownCode);
            assertThat(quote.name()).isNotBlank();
            assertThat(router.servingByAction()).containsEntry(ACTION_QUOTE, realFallback.name());
            assertThat(router.degraded()).isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("对外元信息")
    class Metadata {

        @Test
        @DisplayName("name() 返回主数据源标识")
        void nameComesFromPrimary() {
            assertThat(router(true).name()).isEqualTo(PRIMARY_NAME);
        }

        @Test
        @DisplayName("available() 透传主数据源")
        void availabilityComesFromPrimary() {
            assertThat(router(true).available()).isTrue();

            primary.setAvailable(false);
            assertThat(router(true).available()).isFalse();
        }

        @Test
        @DisplayName("servingByAction() 返回不可变快照")
        void servingByActionIsImmutableSnapshot() {
            primary.failAlways(unavailable("连接超时"));
            ResilientMarketDataProvider router = router(true);
            router.getQuote("600519");

            Map<String, String> snapshot = router.servingByAction();

            assertThatThrownBy(() -> snapshot.put("getQuote", PRIMARY_NAME))
                    .as("返回内部 Map 会让调用方绕过路由器篡改状态")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("从未调用过任何操作时不处于降级状态")
        void freshRouterIsNotDegraded() {
            ResilientMarketDataProvider router = router(true);

            assertThat(router.degraded()).isFalse();
            assertThat(router.servingByAction()).isEmpty();
        }
    }

    // ==================================================================
    /**
     * 市场榜单（getTopMovers）与其它能力走同一条降级规则：
     * 只有 {@link MarketDataUnavailableException} 降级、降级有痕迹、
     * 关闭降级时如实报错不摆「已降级」的乌龙。榜单是新能力，
     * 三条规则各验一条，防止它成为「绕过路由器契约」的旁门。
     */
    @Nested
    @DisplayName("市场榜单")
    class TopMovers {

        private static final String ACTION_MOVERS = "getTopMovers";

        @Test
        @DisplayName("主源正常时榜单来自主源，并记录服务方")
        void servesFromPrimary() {
            List<Mover> movers = router(true).getTopMovers(MoverMetric.CHANGE_PCT, false, 10);

            assertThat(movers).hasSize(1);
            assertThat(movers.get(0).name()).isEqualTo(PRIMARY_NAME + "-mover");
            assertThat(router(true).servingByAction()).doesNotContainKey(ACTION_MOVERS);
        }

        @Test
        @DisplayName("主源不可用时榜单降级到演示源，且降级有痕迹")
        void degradesToFallback() {
            primary.failOnly(ACTION_MOVERS, unavailable("榜单接口被上游拒绝"));
            ResilientMarketDataProvider router = router(true);

            List<Mover> movers = router.getTopMovers(MoverMetric.AMOUNT, false, 10);

            assertThat(movers.get(0).name()).isEqualTo(FALLBACK_NAME + "-mover");
            assertThat(router.servingByAction()).containsEntry(ACTION_MOVERS, FALLBACK_NAME);
            assertThat(router.degraded()).isTrue();
            assertThat(fallbackMetric(ACTION_MOVERS)).isNotNull();
        }

        @Test
        @DisplayName("关闭降级时榜单如实报错，不记降级状态")
        void failsHonestlyWhenFallbackDisabled() {
            primary.failOnly(ACTION_MOVERS, unavailable("榜单接口被上游拒绝"));
            ResilientMarketDataProvider router = router(false);

            catchThrowableOfType(() -> router.getTopMovers(MoverMetric.CHANGE_PCT, true, 5),
                    MarketDataUnavailableException.class);

            assertThat(router.servingByAction()).isEmpty();
            assertThat(router.degraded()).isFalse();
            assertThat(unavailableMetric(ACTION_MOVERS)).isNotNull();
        }
    }

    // ==================================================================
    /**
     * 多级降级链（{@code eastmoney → tencent → mock}）的专测。
     *
     * <p>主源被东财风控掐断后，备用真实源（腾讯）应当接管——
     * 此时的数据是<b>真实行情</b>，前端不能挂「演示数据」警示，
     * 所以 {@code degraded()} 必须为 false；只有 mock 兜底接管才算降级。
     * 同时验证冷却（circuit breaker）：失败的源在冷却期内被跳过，
     * 不让每个请求都在死源上白等一次。
     */
    @Nested
    @DisplayName("多级降级链与冷却")
    class ChainedFallback {

        private static final String MIDDLE_NAME = "tencent";
        private static final String ACTION_QUOTE = "getQuote";
        private static final String ACTION_BARS = "getBars";

        private FakeProvider middle;

        @BeforeEach
        void setUpMiddle() {
            middle = new FakeProvider(MIDDLE_NAME);
        }

        /** 三级链路由：eastmoney → tencent → mock。 */
        private ResilientMarketDataProvider chainedRouter(boolean fallbackEnabled, long cooldownMillis) {
            return new ResilientMarketDataProvider(
                    List.of(primary, middle, fallback), fallbackEnabled, cooldownMillis, registry);
        }

        @Test
        @DisplayName("主源挂掉时由中间真实源接管，数据可用且不算演示降级")
        void middleProviderTakesOver() {
            primary.failAlways(unavailable("东财被风控断连"));

            Quote quote = chainedRouter(true, 0).getQuote("600019");

            assertThat(quote.name()).isEqualTo(MIDDLE_NAME + "-600019");
            assertThat(fallback.callCount(ACTION_QUOTE))
                    .as("腾讯能服务时绝不该消耗 mock 兜底")
                    .isZero();
        }

        @Test
        @DisplayName("中间源接管时 servingByAction 如实记录 tencent，degraded 为 false")
        void middleTakeoverIsNotDemoDegraded() {
            primary.failAlways(unavailable("东财被风控断连"));
            ResilientMarketDataProvider router = chainedRouter(true, 0);

            router.getQuote("600019");

            assertThat(router.servingByAction()).containsEntry(ACTION_QUOTE, MIDDLE_NAME);
            assertThat(router.degraded())
                    .as("腾讯行情是真实数据，degraded=true 会让前端错误地挂「演示数据」警示")
                    .isFalse();
        }

        @Test
        @DisplayName("中间源也挂掉时落到 mock 兜底，此时 degraded 为 true")
        void demoFallbackOnlyWhenMockServes() {
            primary.failAlways(unavailable("东财被风控断连"));
            middle.failAlways(unavailable("腾讯接口异常"));

            Quote quote = chainedRouter(true, 0).getQuote("600019");

            assertThat(quote.name()).isEqualTo(FALLBACK_NAME + "-600019");
            assertThat(fallback.callCount(ACTION_QUOTE)).isEqualTo(1);
            assertThat(fallbackMetric(ACTION_QUOTE)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("逐级失败的痕迹都可追溯：fallback 指标带 from 标签")
        void everyLevelFailureIsTraceable() {
            primary.failAlways(unavailable("东财被风控断连"));
            middle.failAlways(unavailable("腾讯接口异常"));

            chainedRouter(true, 0).getQuote("600019");

            assertThat(registry.find("app.market_data.fallback")
                    .tag("action", ACTION_QUOTE)
                    .tag("from", PRIMARY_NAME).counter()).isNotNull();
            assertThat(registry.find("app.market_data.fallback")
                    .tag("action", ACTION_QUOTE)
                    .tag("from", MIDDLE_NAME).counter()).isNotNull();
        }

        @Test
        @DisplayName("主源恢复后自动回到主源服务，servingByAction 归位")
        void recoversToPrimaryAfterHeal() {
            primary.failAlways(unavailable("东财被风控断连"));
            ResilientMarketDataProvider router = chainedRouter(true, 0);
            router.getQuote("600019");

            primary.stopFailing();
            Quote quote = router.getQuote("600019");

            assertThat(quote.name()).isEqualTo(PRIMARY_NAME + "-600019");
            assertThat(router.servingByAction()).containsEntry(ACTION_QUOTE, PRIMARY_NAME);
            assertThat(router.degraded()).isFalse();
        }

        @Test
        @DisplayName("冷却期内跳过失败的主源，不让每个请求都白试一次")
        void cooldownSkipsFailingProvider() {
            primary.failAlways(unavailable("东财被风控断连"));
            ResilientMarketDataProvider router = chainedRouter(true, 60_000);

            router.getQuote("600019");
            assertThat(primary.callCount(ACTION_QUOTE)).isEqualTo(1);

            // 冷却期内第二次调用：主源应被跳过，不再产生新的调用
            router.getQuote("000001");
            assertThat(primary.callCount(ACTION_QUOTE))
                    .as("冷却期内仍重试死源，WebSocket 每 3 秒推送会把等待时间翻倍")
                    .isEqualTo(1);
            assertThat(registry.find("app.market_data.circuit_skip")
                    .tag("provider", PRIMARY_NAME).counter()).isNotNull();
        }

        @Test
        @DisplayName("冷却到期后放行探测，主源恢复即解除冷却")
        void cooldownExpiresAndProbeSucceeds() throws InterruptedException {
            primary.failAlways(unavailable("东财被风控断连"));
            ResilientMarketDataProvider router = chainedRouter(true, 100);
            router.getQuote("600019");

            primary.stopFailing();
            Thread.sleep(150);

            Quote quote = router.getQuote("600019");
            assertThat(quote.name()).isEqualTo(PRIMARY_NAME + "-600019");
            assertThat(primary.callCount(ACTION_QUOTE)).isEqualTo(2);
        }

        @Test
        @DisplayName("查无此股在链中任意一级都直接上抛，不打断降级语义")
        void notFoundPropagatesThroughChain() {
            primary.failAlways(notFound());

            BizException ex = catchThrowableOfType(
                    () -> chainedRouter(true, 0).getQuote("999999"), BizException.class);

            assertThat(ex.getCode()).isEqualTo(ErrorCode.QUOTE_NOT_FOUND.name());
            assertThat(middle.callCount(ACTION_QUOTE))
                    .as("上游明确说没有的标的，不该换一个源再问一遍")
                    .isZero();
            assertThat(fallback.callCount(ACTION_QUOTE)).isZero();
        }

        @Test
        @DisplayName("关闭降级时整条链只用主源")
        void fallbackDisabledUsesOnlyPrimary() {
            primary.failAlways(unavailable("东财被风控断连"));

            catchThrowableOfType(() -> chainedRouter(false, 0).getQuote("600019"),
                    MarketDataUnavailableException.class);

            assertThat(middle.callCount(ACTION_QUOTE)).isZero();
            assertThat(fallback.callCount(ACTION_QUOTE)).isZero();
        }

        @Test
        @DisplayName("K 线与其它操作遵循同样的多级路由")
        void barsFollowSameChain() {
            primary.failAlways(unavailable("东财被风控断连"));
            ResilientMarketDataProvider router = chainedRouter(true, 0);

            assertThat(router.getBars("600019", MarketPeriod.DAILY, 60).get(0).date())
                    .isEqualTo(MIDDLE_NAME + "-bar");
            assertThat(router.degraded()).isFalse();
        }

        private BizException notFound() {
            return new BizException(ErrorCode.QUOTE_NOT_FOUND, "未找到标的 999999");
        }
    }

    // ==================================================================
    // 辅助

    private static MarketDataUnavailableException unavailable(String message) {
        return new MarketDataUnavailableException(message);
    }

    /**
     * 可编程的假数据源。
     *
     * <p>返回值里带上自己的 {@code name}，这样断言「这条数据是谁给的」
     * 只需要看字符串前缀，不必再引入额外标记。
     */
    private static final class FakeProvider implements MarketDataProvider {

        private final String name;
        private boolean available = true;
        private RuntimeException failure;
        private final Map<String, RuntimeException> perActionFailure = new java.util.HashMap<>();
        private final Map<String, Integer> callCounts = new java.util.LinkedHashMap<>();
        private String lastBarsCall;

        FakeProvider(String name) {
            this.name = name;
        }

        /** 让所有操作一律抛指定异常，用于模拟主源整体不可用。 */
        void failAlways(RuntimeException ex) {
            this.failure = ex;
        }

        /**
         * 只让某个操作失败。
         *
         * <p>真实故障几乎从来不是「整个上游全挂」——「K 线接口可用、
         * 实时报价接口被风控拦」才是常态，而「逐操作隔离」这条契约
         * 只有在两种状态同时存在时才检验得出来。
         */
        void failOnly(String action, RuntimeException ex) {
            perActionFailure.put(action, ex);
        }

        void stopFailing() {
            this.failure = null;
            perActionFailure.clear();
        }

        void setAvailable(boolean available) {
            this.available = available;
        }

        int callCount(String action) {
            return callCounts.getOrDefault(action, 0);
        }

        int totalCalls() {
            return callCounts.values().stream().mapToInt(Integer::intValue).sum();
        }

        String lastBarsCall() {
            return lastBarsCall;
        }

        private void record(String action) {
            callCounts.merge(action, 1, Integer::sum);
        }

        private void maybeFail(String action) {
            if (failure != null) {
                throw failure;
            }
            RuntimeException specific = perActionFailure.get(action);
            if (specific != null) {
                throw specific;
            }
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Quote getQuote(String code) {
            record("getQuote");
            maybeFail("getQuote");
            return new Quote(code, name + "-" + code, "SH", 1700.0, 12.0, 0.71,
                    1690.0, 1710.0, 1685.0, 1688.0, 12345L, 0.35, 1.2e8, 1_700_000_000L);
        }

        @Override
        public List<Bar> getBars(String code, MarketPeriod period, int limit) {
            record("getBars");
            lastBarsCall = code + "|" + period.name() + "|" + limit;
            maybeFail("getBars");
            return List.of(new Bar(name + "-bar", 1.0, 2.0, 0.5, 1.5, 100L, 1.0e5, 0.9));
        }

        @Override
        public List<IndexSnapshot> getIndices() {
            record("getIndices");
            maybeFail("getIndices");
            return List.of(new IndexSnapshot(name + "-idx", "上证指数", 3200.0, 5.0, 0.16));
        }

        @Override
        public List<StockBrief> search(String keyword) {
            record("search");
            maybeFail("search");
            return List.of(new StockBrief(name + "-brief", "贵州茅台", "SH"));
        }

        @Override
        public List<Mover> getTopMovers(MoverMetric metric, boolean ascending, int limit) {
            record("getTopMovers");
            maybeFail("getTopMovers");
            // 价格带数据源名前缀，让「这条榜单来自谁」可以直接断言
            return List.of(new Mover("600519", name + "-mover",
                    1700.0, 12.0, 0.71, 1.2e8));
        }

        @Override
        public void warmup() {
            record("warmup");
        }
    }
}
