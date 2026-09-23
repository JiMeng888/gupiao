package com.stocklab.analyst.common.ratelimit;

/**
 * 限流器抽象。
 *
 * <p>两种实现：
 * <ul>
 *   <li>{@link InMemoryTokenBucketRateLimiter} —— 单机内存，零外部依赖（默认）</li>
 *   <li>{@link RedisTokenBucketRateLimiter} —— Redis + Lua，多实例共享配额</li>
 * </ul>
 *
 * <p>多实例部署时若用内存实现，实际配额会随实例数线性放大
 * （N 个实例 = N 倍配额），这是必须显式切换到 Redis 实现的原因。
 */
public interface RateLimiter {

    /**
     * 尝试获取一个令牌。
     *
     * @param key   限流维度标识，通常为「客户端 IP」
     * @param scope 限流档位
     */
    RateLimitResult acquire(String key, RateLimitScope scope);
}
