package com.stocklab.analyst.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 行情数据源配置。
 *
 * @param provider        数据源实现：{@code eastmoney}（真实行情）/ {@code mock}（离线演示）
 * @param timeout         单次行情请求超时（连接 + 读取）
 * @param quoteTtl        实时报价本地缓存有效期，用于折叠同一秒内的并发重复请求
 * @param barTtl          K 线本地缓存有效期
 * @param warmupOnBoot    启动时是否预热全 A 股清单（搜索依赖）
 * @param fallbackToMock  主数据源不可用时是否降级到内置演示数据
 * @param upstreamCooldown 真实数据源请求失败后的冷却时长：冷却期内的请求跳过该源
 *                         直接使用降级链的下一级，到期自动放行一次探测。
 *                         用于避免「上游被风控/宕机时，每个请求都先在死源上白等一次失败」。
 *                         设为 0 禁用冷却。仅作用于真实源，mock 兜底不受影响
 */
@ConfigurationProperties(prefix = "app.market-data")
public record MarketDataProperties(
        String provider,
        Duration timeout,
        Duration quoteTtl,
        Duration barTtl,
        boolean warmupOnBoot,
        boolean fallbackToMock,
        Duration upstreamCooldown
) {
    public MarketDataProperties {
        if (provider == null || provider.isBlank()) {
            provider = "eastmoney";
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
    }
}
