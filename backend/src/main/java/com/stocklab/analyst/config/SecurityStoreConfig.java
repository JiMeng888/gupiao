package com.stocklab.analyst.config;

import com.stocklab.analyst.config.properties.SessionProperties;
import com.stocklab.analyst.security.InMemoryTokenRevocationStore;
import com.stocklab.analyst.security.RedisTokenRevocationStore;
import com.stocklab.analyst.security.TokenRevocationStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 会话吊销存储装配：按 {@code app.session.store} 选择单机或分布式实现。
 *
 * <p>与 {@code RateLimitConfig} 用同一套模式（显式的 {@code @Bean} 工厂
 * 而不是两个 {@code @Component} + {@code @ConditionalOnProperty}）：
 * 让「选哪一种」集中在一处，并在启动日志里明确打印所选实现。
 * 排查「在 A 机器登出、B 机器还能用」这类问题时，
 * 第一件要确认的就是「现在跑的是内存黑名单还是 Redis 黑名单」。
 */
@Configuration
public class SecurityStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityStoreConfig.class);

    @Bean
    public TokenRevocationStore tokenRevocationStore(SessionProperties properties,
                                                     ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                                     MeterRegistry meterRegistry) {

        if ("redis".equalsIgnoreCase(properties.store())) {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate != null) {
                log.info("会话吊销存储：Redis（多实例共享，登出跨实例生效）");
                return new RedisTokenRevocationStore(redisTemplate, properties.keyPrefix(), meterRegistry);
            }
            log.warn("app.session.store=redis 但未装配 StringRedisTemplate，"
                    + "已回退为单机内存黑名单。请检查 spring.data.redis 配置。");
        }

        // 单机内存实现是默认值，而它有一个普通人不会主动想到的后果：
        // 多副本部署时在 A 实例登出，B 实例仍然认那张令牌。
        // 默认配置下没人会去看注释，所以这里主动 WARN 出来。
        log.warn("会话吊销存储：单机内存（仅适用于单副本部署）。"
                + "多副本部署时登出不会跨实例生效，请设置 app.session.store=redis。");
        return new InMemoryTokenRevocationStore();
    }
}
