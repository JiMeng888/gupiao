package com.stocklab.analyst.common.ratelimit;

import com.stocklab.analyst.config.properties.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机内存令牌桶限流器（默认实现，零外部依赖）。
 *
 * <p>算法：每个 (客户端, 档位) 维护一个桶，容量 {@code burst}，
 * 以 {@code ratePerMin / 60} 个令牌每秒匀速补充；请求消耗 1 个令牌，
 * 不足则拒绝并给出建议重试等待时间。
 *
 * <p>选令牌桶而非固定窗口计数器的原因：令牌桶允许合理的突发流量
 * （桶容量吸收），同时保证长期平均速率；固定窗口在窗口边界会出现
 * 「两倍配额」的突刺。
 *
 * <p><b>局限（多实例部署必读）</b>：桶状态在本进程内存中，
 * 部署 N 个实例时实际配额放大约 N 倍。多实例场景请把
 * {@code app.rate-limit.store} 设为 {@code redis} 使用
 * {@link RedisTokenBucketRateLimiter}。
 *
 * <p>另一个必须处理的工程问题：桶的 key 来自客户端 IP，
 * 公网环境下 key 数量无上界，因此需要定期清理空闲桶，
 * 否则是一处内存泄漏（原 FastAPI 版本用全局字典，存在同样问题）。
 */
public class InMemoryTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTokenBucketRateLimiter.class);

    /** 空闲超过该时长的桶视为可回收：桶在空闲这么久后早已补满，重建等价。 */
    private static final long IDLE_EVICT_NANOS = 10 * 60 * 1_000_000_000L;

    private final RateLimitProperties properties;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public InMemoryTokenBucketRateLimiter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    public RateLimitResult acquire(String key, RateLimitScope scope) {
        BucketConfig config = configOf(scope);
        Bucket bucket = buckets.computeIfAbsent(key + '|' + scope.name(),
                ignored -> new Bucket(config.capacity()));
        return bucket.tryAcquire(config.ratePerSecond(), config.capacity());
    }

    /** 回收空闲桶，避免 key 数量随访问来源增长而无限膨胀。 */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void evictIdleBuckets() {
        long threshold = System.nanoTime() - IDLE_EVICT_NANOS;
        int before = buckets.size();
        buckets.entrySet().removeIf(entry -> entry.getValue().lastAccessNanos() < threshold);
        int removed = before - buckets.size();
        if (removed > 0) {
            log.debug("限流器回收空闲桶 {} 个，剩余 {} 个", removed, buckets.size());
        }
    }

    /** 当前活跃桶数量（测试与监控用）。 */
    public int activeBucketCount() {
        return buckets.size();
    }

    private BucketConfig configOf(RateLimitScope scope) {
        return switch (scope) {
            case STRICT -> new BucketConfig(
                    properties.strictRatePerMin() / 60.0,
                    properties.strictBurst());
            case LOOSE -> new BucketConfig(
                    properties.looseRatePerMin() / 60.0,
                    properties.looseBurst());
        };
    }

    private record BucketConfig(double ratePerSecond, double capacity) {
    }

    /**
     * 单个令牌桶。
     *
     * <p>用 {@code synchronized} 而非 CAS 自旋：临界区只有几次浮点运算，
     * 锁竞争窗口极短，且能天然保证「补充 + 扣减」的原子性，避免 ABA 类问题。
     */
    private static final class Bucket {

        private double tokens;
        private long lastRefillNanos;
        private volatile long lastAccessNanos;

        Bucket(double capacity) {
            this.tokens = capacity;
            long now = System.nanoTime();
            this.lastRefillNanos = now;
            this.lastAccessNanos = now;
        }

        synchronized RateLimitResult tryAcquire(double ratePerSecond, double capacity) {
            // 用 nanoTime 而非 currentTimeMillis：后者受 NTP 校时影响可能回拨，
            // 会导致 elapsed 为负、令牌被错误地大量补充。
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            tokens = Math.min(capacity, tokens + elapsedSeconds * ratePerSecond);
            lastRefillNanos = now;
            lastAccessNanos = now;

            double cost = 1.0;
            if (tokens >= cost) {
                tokens -= cost;
                return RateLimitResult.pass(tokens);
            }
            double deficit = cost - tokens;
            long retryAfterSeconds = (long) Math.ceil(deficit / ratePerSecond);
            return RateLimitResult.reject(tokens, retryAfterSeconds);
        }

        long lastAccessNanos() {
            return lastAccessNanos;
        }
    }

    /** 供监控端点读取内部状态。 */
    public Map<String, Object> stats() {
        return Map.of(
                "store", "memory",
                "activeBuckets", buckets.size(),
                "strictRatePerMin", properties.strictRatePerMin(),
                "looseRatePerMin", properties.looseRatePerMin());
    }
}
