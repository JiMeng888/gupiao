package com.stocklab.analyst.modules.fundamental.service;

import com.stocklab.analyst.config.CacheConfig;
import com.stocklab.analyst.config.properties.MarketDataProperties;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.fundamental.EastMoneyFundamentalClient;
import com.stocklab.analyst.market.fundamental.RawFundamental;
import com.stocklab.analyst.market.mock.MockMarketDataProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 财务概览缓存的<b>回归测试</b>：钉住「缓存对内部调用也生效」。
 *
 * <h2>它防的是什么</h2>
 * 曾经的写法是 {@code FundamentalService.getOverview} 打 {@code @Cacheable}，
 * 而同一个类里的杜邦分析与同行对比直接 {@code getOverview(code)} 调它。
 * Spring 的缓存注解建立在 AOP 代理上，<b>同类内部调用不经过代理</b>，
 * 于是注解完全失效：{@code /fundamental/compare} 一次请求最多放大成
 * 12~18 次上游调用，而接口注释还写着「上限是为了避免放大成数十次上游调用」。
 *
 * <p>这个 bug 的可怕之处在于<b>它在常规测试与手工验证里都不可见</b>：
 * 接口返回的数据完全正确，只是慢一点、多打几次上游。
 * 所以断言不能是「返回值对不对」，必须是<b>上游被调用的次数</b>。
 *
 * <h2>为什么要起一个真实容器</h2>
 * 缓存失效的根因是「没走代理」，因此必须有真实的代理才测得到。
 * 用 Mockito 直接 new 出 {@code FundamentalService} 的话，
 * 无论代码写成按接口调用还是 {@code this.} 调用，测试都会通过——
 * 那正是这个 bug 当初能溜进来的原因。
 * 这里用 {@link AnnotationConfigApplicationContext} 起一个最小容器
 * （只要 {@code @EnableCaching} + Caffeine + 三个 Bean），
 * 不起 Spring Boot、不连数据库、不触网。
 */
class FundamentalOverviewCacheTest {

    /**
     * 最小装配。
     *
     * <p>缓存管理器复用生产用的 {@link CacheConfig}，而不是在测试里另写一份：
     * 否则「生产把 fundamental 缓存的 TTL 配成 0」这类改动不会让测试失败。
     */
    @Configuration
    @EnableCaching
    static class MinimalConfig {

        @Bean
        CacheManager cacheManager() {
            // provider=mock 只是为了满足配置对象；本测试不依赖真实数据源
            MarketDataProperties properties = new MarketDataProperties(
                    "mock", null, null, null, null, false, true, null, 0, null, null);
            return new CacheConfig().cacheManager(properties);
        }

        @Bean
        EastMoneyFundamentalClient fundamentalClient() {
            return mock(EastMoneyFundamentalClient.class);
        }

        @Bean
        MarketDataProvider marketDataProvider() {
            // 名称解析走真实实现即可：它不触网，且本测试不关心名称
            return new MockMarketDataProvider();
        }

        @Bean
        FundamentalOverviewProvider overviewProvider(EastMoneyFundamentalClient client,
                                                     MarketDataProvider provider) {
            return new FundamentalOverviewProvider(client, provider);
        }

        @Bean
        FundamentalService fundamentalService(FundamentalOverviewProvider overviewProvider,
                                              EastMoneyFundamentalClient client) {
            return new FundamentalService(overviewProvider, client);
        }
    }

    private AnnotationConfigApplicationContext context;
    private EastMoneyFundamentalClient client;
    private FundamentalService service;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(MinimalConfig.class);
        client = context.getBean(EastMoneyFundamentalClient.class);
        service = context.getBean(FundamentalService.class);

        when(client.fetchFundamental(anyString())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0, String.class);
            return Optional.of(new RawFundamental(
                    code, "股票" + code, "测试行业",
                    20.0, 21.0, 19.5, 3.2,
                    18.5, 45.0, 22.0, 35.0,
                    1.0e10, 2.0e9, 12.5, 15.5, 25.0, 2.5));
        });
        // 行业补充信息取不到：不影响主体指标，也让每次概览的上游调用次数稳定为 2
        when(client.fetchFinancialPeriods(anyString(), anyInt())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    @DisplayName("同行对比：同一组标的第二次对比全部命中缓存，不重复打上游")
    void compareHitsCacheOnSecondCall() {
        String codes = "600519,000858,300750";

        service.compare(codes);
        // 第一轮：3 只标的各打一次概览接口。这里就把「一轮只打 3 次」钉住，
        // 额外调用（如每只又查行业与板块）会让数字变大，同样是回归。
        verify(client, times(3)).fetchFundamental(anyString());

        service.compare(codes);

        // 第二轮若仍是 3 次 → 缓存生效；变成 6 次 → 自调用绕过了代理（本测试要防的 bug）
        verify(client, times(3)).fetchFundamental(anyString());
    }

    @Test
    @DisplayName("杜邦分析与概览共用同一份缓存，不各打一次上游")
    void dupontSharesCacheWithOverview() {
        service.getOverview("600519");
        service.getDupont("600519");

        // 杜邦内部读概览若绕过代理，这里会是 2 次
        verify(client, times(1)).fetchFundamental("600519");
    }

    @Test
    @DisplayName("同一标的重复读取概览只打一次上游（外部接口路径）")
    void repeatedOverviewLoadsHitCache() {
        service.getOverview("600519");
        service.getOverview("600519");
        service.getOverview("600519");

        verify(client, times(1)).fetchFundamental("600519");
    }

    @Test
    @DisplayName("不同标的各自缓存，不会互相顶掉")
    void differentCodesHaveSeparateEntries() {
        service.getOverview("600519");
        service.getOverview("000858");
        // 回到第一只：仍应命中第一次的缓存
        service.getOverview("600519");

        verify(client, times(1)).fetchFundamental("600519");
        verify(client, times(1)).fetchFundamental("000858");
    }

    @Test
    @DisplayName("缓存里存的是完整概览：第二次读到的是同一份内容")
    void cachedValueIsComplete() {
        var first = service.getOverview("600519");
        var second = service.getOverview("600519");

        // 若缓存 key 设计错误导致读到别的标的的数据，这里会不一致
        assertThat(second.code()).isEqualTo(first.code());
        assertThat(second.name()).isEqualTo(first.name());
        assertThat(second.dataSource()).isEqualTo(first.dataSource());
    }
}
