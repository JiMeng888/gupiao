package com.stocklab.analyst.config.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarketDataProperties} 的<b>绑定</b>测试（不是取值测试）。
 *
 * <h2>为什么需要它</h2>
 * 三条链路的名字都是「连字符 → 驼峰」的松散绑定：{@code sector-chain} → {@code sectorChain}、
 * {@code movers-chain} → {@code moversChain}。这类键写错一个字母<b>不会报错</b>：
 * 绑定器跳过它，record 的紧凑构造器再把 {@code null} 补成默认值，
 * 于是「配置了新浪优先」静默变成「还是默认顺序」——排障时会以为是代码没生效。
 *
 * <p>项目里最有说服力的先例是 2026-10-19 那次：{@code MARKET_DATA_CHAIN}
 * 根本没被传进容器，写了也白写。配置生效与否必须能测，而不是靠读代码推。
 *
 * <p>这里用 {@link Binder} 直接绑 {@code Map} 属性源：不需要启动 Spring 容器，
 * 也不依赖 {@code @SpringBootTest}（本项目没有上下文级测试，起一次要几秒）。
 */
class MarketDataPropertiesBindingTest {

    private static MarketDataProperties bind(Map<String, String> source) {
        Binder binder = new Binder(new MapConfigurationPropertySource(source));
        MarketDataProperties properties = binder
                .bind("app.market-data", Bindable.of(MarketDataProperties.class))
                .orElseThrow(() -> new AssertionError("app.market-data 绑定失败"));
        return properties;
    }

    @Test
    @DisplayName("movers-chain 能被正确绑定（键名写错会静默回落默认值）")
    void bindsMoversChain() {
        MarketDataProperties properties = bind(Map.of(
                "app.market-data.movers-chain", "sina,eastmoney"));

        assertThat(properties.moversRealChain())
                .as("配置成新浪优先时，取到的必须就是新浪优先")
                .containsExactly("sina", "eastmoney");
    }

    @Test
    @DisplayName("sector-chain 与 movers-chain 互不干扰")
    void bindsTwoIndependentChains() {
        MarketDataProperties properties = bind(Map.of(
                "app.market-data.sector-chain", "tencent,eastmoney",
                "app.market-data.movers-chain", "eastmoney,sina"));

        assertThat(properties.sectorRealChain()).containsExactly("tencent", "eastmoney");
        assertThat(properties.moversRealChain()).containsExactly("eastmoney", "sina");
    }

    @Test
    @DisplayName("不配置时两条专用链各有默认值，且都不含兜底源")
    void defaultsWhenAbsent() {
        MarketDataProperties properties = bind(Map.of("app.market-data.provider", "eastmoney"));

        assertThat(properties.sectorRealChain())
                .as("板块：腾讯一级行业在前")
                .containsExactly("tencent", "eastmoney");
        assertThat(properties.moversRealChain())
                .as("个股榜单：东财在前，新浪兜底")
                .containsExactly("eastmoney", "sina");
        assertThat(properties.moversRealChain())
                .as("mock 由装配层强制置于链尾，不允许出现在配置链里")
                .doesNotContain(MarketDataProperties.FALLBACK_PROVIDER);
    }

    @Test
    @DisplayName("配置里写 mock 会被剔除，兜底源的位置不可配")
    void mockIsStrippedFromConfiguredChains() {
        MarketDataProperties properties = bind(Map.of(
                "app.market-data.chain", "mock,eastmoney",
                "app.market-data.movers-chain", "sina,mock",
                "app.market-data.sector-chain", "mock"));

        assertThat(properties.realChain()).containsExactly("eastmoney");
        assertThat(properties.moversRealChain()).containsExactly("sina");
        assertThat(properties.sectorRealChain())
                .as("整条链只有 mock 时剔除后为空，退回默认值而不是空链")
                .isEqualTo(List.of("tencent", "eastmoney"));
    }
}
