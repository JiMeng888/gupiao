package com.stocklab.analyst.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link TokenRevocationStore} 的单进程内存实现（默认）。
 *
 * <p><b>已知边界（多实例部署前必须读）</b>：
 * <ol>
 *   <li>黑名单在<b>本进程内存</b>中。多实例部署时，在 A 实例登出的会话
 *       拿到 B 实例仍可用。</li>
 *   <li>进程重启后黑名单丢失，此前登出的令牌会短暂恢复可用。</li>
 * </ol>
 * 两条都由 {@link RedisTokenRevocationStore} 解决；把
 * {@code app.session.store} 设为 {@code redis} 即可切换，
 * 业务代码无需改动。
 *
 * <p>条目按业务传入的过期时间自动清理：过期的令牌本来就无法通过签名校验，
 * 再留在黑名单里纯属浪费内存。这里用 Caffeine 的自定义过期策略
 * 让「存活时间 = 业务指定的剩余有效期」，而不是统一 TTL。
 */
public class InMemoryTokenRevocationStore implements TokenRevocationStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTokenRevocationStore.class);

    /** 容量上限：防止异常流量把黑名单撑爆内存。 */
    private static final long MAX_ENTRIES = 100_000L;

    /**
     * 两类条目共用同一个 Caffeine 实例，用前缀区分命名空间。
     *
     * <p>分成两个实例也可以，但那样容量上限要各自设一份，
     * 而实际压力总是偏向其中一边，另一边的配额就被浪费了。
     * 前缀方案让两类条目共享同一份容量预算，也便于 {@link #size()} 统一观测。
     */
    private static final String TOKEN_PREFIX = "j:";
    private static final String SESSION_PREFIX = "s:";

    private final Cache<String, Instant> revoked;
    private final AtomicLong revokedCounter = new AtomicLong();

    public InMemoryTokenRevocationStore() {
        this.revoked = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfter(new Expiry<String, Instant>() {
                    @Override
                    public long expireAfterCreate(String key, Instant expiresAt, long currentTime) {
                        return remainingNanos(expiresAt);
                    }

                    @Override
                    public long expireAfterUpdate(String key, Instant expiresAt, long currentTime,
                                                  long currentDuration) {
                        return remainingNanos(expiresAt);
                    }

                    @Override
                    public long expireAfterRead(String key, Instant expiresAt, long currentTime,
                                                long currentDuration) {
                        // 读取不续期：条目存活时间由令牌自身有效期决定，访问频繁不应延长它
                        return currentDuration;
                    }

                    private long remainingNanos(Instant expiresAt) {
                        long nanos = Duration.between(Instant.now(), expiresAt).toNanos();
                        // 已过期或时钟偏差导致为负时给一个极小正值，
                        // Caffeine 要求返回值非负
                        return Math.max(1L, nanos);
                    }
                })
                .build();
    }

    @Override
    public void revoke(String tokenId, Instant expiresAt) {
        put(TOKEN_PREFIX + tokenId, tokenId, expiresAt, "令牌");
    }

    @Override
    public void revokeSession(String sessionId, Instant expiresAt) {
        put(SESSION_PREFIX + sessionId, sessionId, expiresAt, "会话");
    }

    @Override
    public boolean isRevoked(String tokenId) {
        return contains(TOKEN_PREFIX + tokenId);
    }

    @Override
    public boolean isSessionRevoked(String sessionId) {
        return contains(SESSION_PREFIX + sessionId);
    }

    @Override
    public long size() {
        return revoked.estimatedSize();
    }

    // ------------------------------------------------------------------

    private void put(String key, String rawId, Instant expiresAt, String kind) {
        if (rawId == null || rawId.isBlank() || expiresAt == null) {
            return;
        }
        if (expiresAt.isBefore(Instant.now())) {
            // 已过期的令牌无需拉黑
            return;
        }
        revoked.put(key, expiresAt);
        long total = revokedCounter.incrementAndGet();
        if (total % 1000 == 0) {
            log.info("累计吊销 {} 个{}，当前黑名单条目 {} 个", total, kind, revoked.estimatedSize());
        }
    }

    private boolean contains(String key) {
        // 前缀字符本身非空，这里只需要挡住 null 拼接出的 "j:null"
        if (key.length() <= 2) {
            return false;
        }
        String raw = key.substring(2);
        if (raw.isBlank() || "null".equals(raw)) {
            return false;
        }
        return revoked.getIfPresent(key) != null;
    }
}
