package com.stocklab.analyst.config;

import com.stocklab.analyst.config.properties.MarketDataProperties;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.MarketDataUnavailableException;
import com.stocklab.analyst.market.eastmoney.EastMoneyMarketDataProvider;
import com.stocklab.analyst.market.mock.MockMarketDataProvider;
import com.stocklab.analyst.market.model.Mover;
import com.stocklab.analyst.market.model.MoverMetric;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.market.sina.SinaMarketDataProvider;
import com.stocklab.analyst.market.tencent.TencentMarketDataProvider;
import com.stocklab.analyst.market.tushare.TushareMarketDataProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 行情降级链装配的单测。
 *
 * <h2>为什么链的顺序值得专门测</h2>
 * 链的顺序在过去是硬编码的。改成可配置之后，它从「一行看一眼就知道」的代码
 * 变成了「要读配置文件才知道」的行为，出错方式也随之变化：
 * <ul>
 *   <li><b>写错源名</b>：静默忽略会让「四级冗余」悄悄变成「一级」——
 *       冗余正是这次装配的全部意义，少了它必须当场失败，不能等到真故障时才发现；</li>
 *   <li><b>把 mock 排到前面</b>：系统会「一直有数据」，但全是编造的，
 *       而且不报任何错。所以兜底源的位置不允许配置。</li>
 * </ul>
 * 因此这里断言的是「配置是否被忠实执行」以及「非法配置是否被拒绝」，
 * 而不是某个具体顺序——顺序本身已经是配置了。
 */
@ExtendWith(MockitoExtension.class)
class MarketDataSupportConfigChainTest {

    @Mock
    private MockMarketDataProvider mockProvider;
    @Mock
    private EastMoneyMarketDataProvider eastMoneyProvider;
    @Mock
    private TencentMarketDataProvider tencentProvider;
    @Mock
    private SinaMarketDataProvider sinaProvider;
    @Mock
    private TushareMarketDataProvider tushareProvider;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        // 名字用于断言链头是谁；Mockito 默认返回 null，会让断言失去意义
        lenient().when(mockProvider.name()).thenReturn("mock");
        lenient().when(eastMoneyProvider.name()).thenReturn("eastmoney");
        lenient().when(tencentProvider.name()).thenReturn("tencent");
        lenient().when(sinaProvider.name()).thenReturn("sina");
        lenient().when(tushareProvider.name()).thenReturn("tushare");
        // Tushare 默认「已配置 Token」，否则会被装配逻辑跳过
        lenient().when(tushareProvider.available()).thenReturn(true);
        // acceptableAsFallback 的接口默认实现返回 true（只有 mock 源对榜单返回 false）。
        // Mockito 对 Mock 的 boolean 方法默认返回 false，等价于「拒绝提供演示数据」，
        // 而路由对链上非链头的源会据此直接跳过——不显式还原这个默认值，
        // 「链中第二级真实源接管」的断言会全部变成假失败。
        lenient().when(mockProvider.acceptableAsFallback(anyString())).thenReturn(true);
        lenient().when(eastMoneyProvider.acceptableAsFallback(anyString())).thenReturn(true);
        lenient().when(tencentProvider.acceptableAsFallback(anyString())).thenReturn(true);
        lenient().when(sinaProvider.acceptableAsFallback(anyString())).thenReturn(true);
        lenient().when(tushareProvider.acceptableAsFallback(anyString())).thenReturn(true);
    }

    private MarketDataProvider build(MarketDataProperties properties) {
        return new MarketDataSupportConfig().marketDataProvider(
                properties, mockProvider, eastMoneyProvider, tencentProvider,
                sinaProvider, tushareProvider, meterRegistry);
    }

    private static MarketDataProperties properties(String provider, List<String> chain) {
        return new MarketDataProperties(
                provider, chain, Duration.ofSeconds(6), Duration.ofSeconds(3),
                Duration.ofMinutes(5), false, true, Duration.ofSeconds(30), 0, null, null);
    }

    @Test
    @DisplayName("配置的顺序就是链的顺序：把腾讯提到第一位后它成为主源")
    void configuredOrderIsHonoured() {
        MarketDataProvider router = build(properties("eastmoney", List.of("tencent", "eastmoney", "tushare")));

        assertThat(router.name()).isEqualTo("tencent");
    }

    @Test
    @DisplayName("默认链是 eastmoney 优先（不配置时行为与改造前一致）")
    void defaultChainKeepsEastmoneyFirst() {
        MarketDataProvider router = build(properties("eastmoney", null));

        assertThat(router.name()).isEqualTo("eastmoney");
    }

    @Test
    @DisplayName("板块榜默认走腾讯一级行业，且只影响该操作")
    void sectorChainPrefersTencentWithoutTouchingOtherActions() {
        MarketDataProvider router = build(properties("eastmoney", null));
        // Mockito 对未打桩的对象返回值是 null，而路由会给报价盖一次延迟声明，
        // 因此这里必须给一个真实对象，否则测的就不是「链头是谁」而是 NPE
        when(eastMoneyProvider.getQuote("600519")).thenReturn(new Quote(
                "600519", "贵州茅台", "SH", 1700.0, 12.0, 0.71,
                1690.0, 1710.0, 1685.0, 1688.0, 12345L, 0.35, 1.2e8, 1_700_000_000L));

        router.getSectors(10);
        router.getQuote("600519");

        assertThat(router.servingByAction())
                .as("板块榜口径与主链不同，必须是按操作的独立顺序")
                .containsEntry("getSectors", "tencent")
                .containsEntry("getQuote", "eastmoney");
    }

    @Test
    @DisplayName("板块链可通过 sector-chain 改回东财优先")
    void sectorChainIsConfigurable() {
        MarketDataProvider router = build(new MarketDataProperties(
                "eastmoney", null, Duration.ofSeconds(6), Duration.ofSeconds(3),
                Duration.ofMinutes(5), false, true, Duration.ofSeconds(30), 0,
                List.of("eastmoney", "tencent"), null));

        router.getSectors(10);

        assertThat(router.servingByAction()).containsEntry("getSectors", "eastmoney");
    }

    @Test
    @DisplayName("个股榜单默认走东财，且与主链顺序相互独立")
    void moversChainIsIndependentFromMainChain() {
        // 主链把腾讯提到第一位（东财被风控时的应急配置），榜单仍应走东财：
        // 榜单的差异点是「有没有这个能力」，不是「谁更全」
        MarketDataProvider router = build(properties("eastmoney", List.of("tencent", "eastmoney")));
        when(eastMoneyProvider.getTopMovers(any(), anyBoolean(), anyInt()))
                .thenReturn(List.of(new Mover("600519", "贵州茅台", 1700.0, 12.0, 0.71, 1e8)));
        when(tencentProvider.getQuote("600519")).thenReturn(new Quote(
                "600519", "贵州茅台", "SH", 1700.0, 12.0, 0.71,
                1690.0, 1710.0, 1685.0, 1688.0, 12345L, 0.35, 1.2e8, 1_700_000_000L));

        router.getTopMovers(MoverMetric.CHANGE_PCT, false, 10);
        router.getQuote("600519");

        assertThat(router.servingByAction())
                .containsEntry("getTopMovers", "eastmoney")
                .containsEntry("getQuote", "tencent");
    }

    @Test
    @DisplayName("东财榜单不可用时新浪接管，且不算「降级到演示数据」")
    void sinaTakesOverWhenEastmoneyMoversFail() {
        MarketDataProvider router = build(properties("eastmoney", null));
        when(eastMoneyProvider.getTopMovers(any(), anyBoolean(), anyInt()))
                .thenThrow(new MarketDataUnavailableException("东财被风控"));
        when(sinaProvider.getTopMovers(any(), anyBoolean(), anyInt()))
                .thenReturn(List.of(new Mover("000001", "平安银行", 11.4, 0.14, 1.2, 5e8)));

        List<Mover> movers = router.getTopMovers(MoverMetric.CHANGE_PCT, false, 10);

        assertThat(movers).extracting(Mover::code).containsExactly("000001");
        assertThat(router.servingByAction()).containsEntry("getTopMovers", "sina");
        assertThat(router.degraded())
                .as("备用真实源接管不是「降级」——前端不该因此打上「演示数据」标记")
                .isFalse();
    }

    @Test
    @DisplayName("榜单链可通过 movers-chain 调整，例如新浪优先")
    void moversChainIsConfigurable() {
        MarketDataProvider router = build(new MarketDataProperties(
                "eastmoney", null, Duration.ofSeconds(6), Duration.ofSeconds(3),
                Duration.ofMinutes(5), false, true, Duration.ofSeconds(30), 0,
                null, List.of("sina", "eastmoney")));
        when(sinaProvider.getTopMovers(any(), anyBoolean(), anyInt()))
                .thenReturn(List.of(new Mover("000001", "平安银行", 11.4, 0.14, 1.2, 5e8)));

        router.getTopMovers(MoverMetric.CHANGE_PCT, false, 10);

        assertThat(router.servingByAction()).containsEntry("getTopMovers", "sina");
    }

    @Test
    @DisplayName("写错源名直接启动失败，并列出可用值——静默跳过会让冗余悄悄消失")
    void unknownProviderNameFailsFast() {
        assertThatThrownBy(() -> build(properties("eastmoney", List.of("eastmony", "tencent"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eastmony")
                .hasMessageContaining("tencent");
    }

    @Test
    @DisplayName("配置里写 mock 会被忽略，兜底源仍由装配强制放在链尾")
    void mockInConfigIsIgnoredAndKeptAtTail() {
        // 若照单执行，mock 会排在第一位，所有请求先吃演示数据——系统看起来「一直有数据」
        MarketDataProvider router = build(properties("eastmoney", List.of("mock", "eastmoney")));

        assertThat(router.name()).as("mock 不允许被配置成主源").isEqualTo("eastmoney");

        // 真实源全部不可用时兜底仍然生效，证明 mock 确实还在链上（且在链尾）
        Quote fallbackQuote = new Quote("600519", "演示数据", "SH", 1.0, 0.0, 0.0,
                1.0, 1.0, 1.0, 1.0, 0L, 0.0, 0.0, 1700000000L);
        when(eastMoneyProvider.getQuote("600519"))
                .thenThrow(new MarketDataUnavailableException("上游断连"));
        when(mockProvider.getQuote("600519")).thenReturn(fallbackQuote);

        // 用 isEqualTo 而不是 isSameAs：getQuote 出口会给报价盖一次延迟声明
        //（withDelayMinutes），返回一个新的 record 实例。这里要证明的是
        //「数据确实由兜底源提供」，比对内容即可，比对对象身份反而会掩盖意图。
        assertThat(router.getQuote("600519")).isEqualTo(fallbackQuote);
    }

    @Test
    @DisplayName("未配置 Tushare Token 时它被跳过：链退化为 mock 兜底，而不是走到一个注定失败的源")
    void tushareWithoutTokenIsSkipped() {
        when(tushareProvider.available()).thenReturn(false);

        MarketDataProvider router = build(properties("eastmoney", List.of("tushare")));

        assertThat(router.name())
                .as("唯一的真实源不可用时，链上只剩链尾兜底源")
                .isEqualTo("mock");
    }

    @Test
    @DisplayName("provider=mock 时退化为单级链，不注册任何真实源")
    void offlineMockModeRegistersNoRealSource() {
        MarketDataProvider router = build(properties("mock", null));

        assertThat(router.name()).isEqualTo("mock");
        assertThat(router.degraded()).as("主源本身就是演示数据，不存在「降级」概念").isFalse();
    }

    @Test
    @DisplayName("链里出现重复源名时去重，不会把同一个源接两次")
    void duplicateNamesAreDeduplicated() {
        MarketDataProvider router = build(
                properties("eastmoney", List.of("tencent", "tencent", "eastmoney")));

        assertThat(router.name()).isEqualTo("tencent");
        // 去重后链为 tencent → eastmoney → mock：东财失败仍应由兜底接住
        Quote fallbackQuote = new Quote("600519", "演示数据", "SH", 1.0, 0.0, 0.0,
                1.0, 1.0, 1.0, 1.0, 0L, 0.0, 0.0, 1700000000L);
        when(tencentProvider.getQuote("600519"))
                .thenThrow(new MarketDataUnavailableException("腾讯断连"));
        when(eastMoneyProvider.getQuote("600519"))
                .thenThrow(new MarketDataUnavailableException("东财断连"));
        when(mockProvider.getQuote("600519")).thenReturn(fallbackQuote);

        assertThat(router.getQuote("600519")).isEqualTo(fallbackQuote);
    }
}
