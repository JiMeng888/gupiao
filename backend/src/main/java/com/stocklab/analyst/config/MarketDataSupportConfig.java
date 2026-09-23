package com.stocklab.analyst.config;

import com.stocklab.analyst.config.properties.MarketDataProperties;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.ResilientMarketDataProvider;
import com.stocklab.analyst.market.eastmoney.EastMoneyMarketDataProvider;
import com.stocklab.analyst.market.mock.MockMarketDataProvider;
import com.stocklab.analyst.market.tencent.TencentMarketDataProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 行情数据源装配。
 *
 * <p>四个 Bean 的职责分离：
 * <ul>
 *   <li>{@link MockMarketDataProvider} —— 始终注册，作为链尾兜底</li>
 *   <li>{@link EastMoneyMarketDataProvider} —— 主数据源（字段最全：搜索/榜单/全市场清单）</li>
 *   <li>{@link TencentMarketDataProvider} —— 备用真实数据源。东财有 IP 级风控
 *       （服务端直接断连，2026-09-19 / 2026-09-21 两次实测），一旦触发，
 *       原先「东财 → mock」两级链会让所有非内置标的直接不可用；
 *       插入腾讯这一级后，报价/K 线/指数仍由真实数据服务，
 *       只有搜索与榜单（腾讯无此能力）才落演示数据。</li>
 *   <li>{@link ResilientMarketDataProvider} —— 对外唯一暴露的 {@code @Primary} Bean，
 *       包装三级降级链。业务层注入 {@code MarketDataProvider} 时拿到的就是它。</li>
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
     * 对外暴露的行情数据源：{@code eastmoney → tencent → mock} 三级降级链。
     *
     * <p>标记 {@code @Primary}：容器里同时存在多个 {@code MarketDataProvider} 实现，
     * 业务层按接口注入时必须有一个默认候选，否则启动即报
     * {@code NoUniqueBeanDefinitionException}。
     *
     * <p>两个配置开关的行为：
     * <ul>
     *   <li>{@code provider=mock}：链退化为单级（mock 为主源），
     *       不再注册真实源——离线演示是显式选择，不是降级；</li>
     *   <li>{@code fallback-to-mock=false}：路由内部把链截断为只用主源，
     *       任何一级（包括腾讯）都不许替它服务——让自动化环境看到真实的上游可用性。</li>
     * </ul>
     */
    @Bean
    @Primary
    public MarketDataProvider marketDataProvider(
            MarketDataProperties properties,
            MockMarketDataProvider mockProvider,
            EastMoneyMarketDataProvider eastMoneyProvider,
            TencentMarketDataProvider tencentProvider,
            MeterRegistry meterRegistry) {

        long cooldownMillis = properties.upstreamCooldown().toMillis();
        boolean preferReal = !"mock".equalsIgnoreCase(properties.provider());

        MarketDataProvider router;
        if (preferReal) {
            router = new ResilientMarketDataProvider(
                    List.of(eastMoneyProvider, tencentProvider, mockProvider),
                    properties.fallbackToMock(), cooldownMillis, meterRegistry);
            log.info("行情数据源：主源={} 备用真实源=tencent 兜底=mock fallbackToMock={} 冷却={}s",
                    eastMoneyProvider.name(), properties.fallbackToMock(), cooldownMillis / 1000);
        } else {
            router = new ResilientMarketDataProvider(
                    List.of(mockProvider), properties.fallbackToMock(), cooldownMillis, meterRegistry);
            log.info("行情数据源：mock（离线演示配置，不注册真实源）");
        }
        return router;
    }
}
