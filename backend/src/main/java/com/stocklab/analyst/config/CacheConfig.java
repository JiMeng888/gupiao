package com.stocklab.analyst.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocklab.analyst.common.constant.CacheNames;
import com.stocklab.analyst.config.properties.MarketDataProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 本地缓存装配（Caffeine）。
 *
 * <p>为什么行情需要缓存：单个个股详情页会并发发起「报价 + K 线 + 指标 + 基本面」
 * 多个请求，而指标计算本身要复用 K 线。没有缓存时，一次页面访问会向上游
 * 行情接口打十几次请求，既慢又容易被限流。缓存把同一秒内的重复请求折叠成一次。
 *
 * <p>各缓存 TTL 不同，是因为数据的新鲜度要求不同：
 * <ul>
 *   <li>报价：秒级（默认 3s），要跟上盘中跳动</li>
 *   <li>K 线：分钟级（默认 5min），日内变化不频繁</li>
 *   <li>股票清单：小时级，全市场代码几乎不变</li>
 * </ul>
 *
 * <p>容量上限是必需的：报价与 K 线的 key 含股票代码，全市场 5000+ 只
 * 都访问过之后，无上限缓存会把堆吃满。
 *
 * <p>每个缓存都调用了 {@code recordStats()}：不开启时 Micrometer 只会导出
 * {@code cache.size}，拿不到命中率——而命中率正是判断「TTL 设得合不合适」
 * 的唯一依据（命中率极低说明 TTL 太短或 key 设计有问题）。
 *
 * <p><b>已知一致性边界</b>：本地缓存不跨实例。多实例部署时不同节点的
 * 报价快照可能有几秒差异，对行情展示可接受；若换成账户余额这类数据，
 * 必须改为 Redis 共享缓存或直接读库。
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(MarketDataProperties marketDataProperties) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        // 默认策略：未单独注册的缓存走这套
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(5_000)
                .recordStats()
                .expireAfterWrite(marketDataProperties.barTtl()));

        // 股票清单：容量固定（全市场 5000+），TTL 长
        cacheManager.registerCustomCache(CacheNames.STOCK_LIST, Caffeine.newBuilder()
                .maximumSize(10)
                .recordStats()
                .expireAfterWrite(Duration.ofHours(6))
                .build());

        // 实时报价：容量大、TTL 极短
        cacheManager.registerCustomCache(CacheNames.QUOTE, Caffeine.newBuilder()
                .maximumSize(5_000)
                .recordStats()
                .expireAfterWrite(marketDataProperties.quoteTtl())
                .build());

        // K 线：单条记录较大，限制容量避免堆压力
        cacheManager.registerCustomCache(CacheNames.BARS, Caffeine.newBuilder()
                .maximumSize(1_000)
                .recordStats()
                .expireAfterWrite(marketDataProperties.barTtl())
                .build());

        // 财务数据：按季度披露，日间几乎不变。TTL 取 6 小时，
        // 同时把「一次基本面请求要打 2~3 个上游接口」收敛成一次。
        cacheManager.registerCustomCache(CacheNames.FUNDAMENTAL, Caffeine.newBuilder()
                .maximumSize(2_000)
                .recordStats()
                .expireAfterWrite(Duration.ofHours(6))
                .build());

        // 资讯：60 秒足够，与原实现保持一致
        cacheManager.registerCustomCache(CacheNames.NEWS, Caffeine.newBuilder()
                .maximumSize(50)
                .recordStats()
                .expireAfterWrite(Duration.ofSeconds(60))
                .build());

        cacheManager.setCacheNames(List.of(
                CacheNames.STOCK_LIST, CacheNames.QUOTE, CacheNames.BARS,
                CacheNames.FUNDAMENTAL, CacheNames.NEWS));

        return cacheManager;
    }
}
