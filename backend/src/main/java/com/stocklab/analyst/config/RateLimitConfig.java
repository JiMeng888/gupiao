package com.stocklab.analyst.config;

import com.stocklab.analyst.common.ratelimit.InMemoryTokenBucketRateLimiter;
import com.stocklab.analyst.common.ratelimit.RateLimiter;
import com.stocklab.analyst.common.ratelimit.RedisTokenBucketRateLimiter;
import com.stocklab.analyst.config.properties.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 限流器装配：按 {@code app.rate-limit.store} 选择单机或分布式实现。
 *
 * <p>用显式的 {@code @Bean} 工厂方法而不是两个 {@code @Component} +
 * {@code @ConditionalOnProperty}，是为了让「选哪一种」这件事集中在一处，
 * 并且能在启动日志里明确打印所选实现——运维排查限流问题时，
 * 第一件要确认的就是「现在跑的是单机桶还是 Redis 桶」。
 */
@Configuration
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    @Bean
    public RateLimiter rateLimiter(RateLimitProperties properties,
                                   org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisTemplateProvider) {

        if ("redis".equalsIgnoreCase(properties.store())) {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate != null) {
                log.info("限流器：Redis 分布式令牌桶（多实例共享配额）");
                return new RedisTokenBucketRateLimiter(redisTemplate, properties);
            }
            log.warn("app.rate-limit.store=redis 但未装配 StringRedisTemplate，"
                    + "已回退为单机内存令牌桶。请检查 spring.data.redis 配置。");
        }

        log.info("限流器：单机内存令牌桶（严格 {} 次/分，宽松 {} 次/分）",
                properties.strictRatePerMin(), properties.looseRatePerMin());
        return new InMemoryTokenBucketRateLimiter(properties);
    }
}
