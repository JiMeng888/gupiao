package com.stocklab.analyst.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * {@link TokenRevocationStore} 的 Redis 实现，用于多实例部署。
 *
 * <p>解决 {@link InMemoryTokenRevocationStore} 的两个边界：
 * 在 A 实例登出的会话，B 实例立刻同样看不到它；
 * 进程重启黑名单不丢。
 *
 * <h2>键设计</h2>
 * <pre>
 *   {prefix}:jti:{jti}   令牌级，值固定为 "1"
 *   {prefix}:sid:{sid}   会话级，值固定为 "1"
 * </pre>
 * 用两个前缀而不是一个统一命名空间：令牌级与会话级的吊销原因不同，
 * 分开后运维可以用 {@code SCAN --pattern '*:sid:*'} 单独观察登出量。
 *
 * <p>没有用 Set 集合（{@code SADD} + {@code SISMEMBER}）：Set 无法给
 * <b>单个成员</b>设置过期时间，只能给整个集合设——那样要么每次登出
 * 都续期集合导致永久驻留，要么一个成员的过期把整批成员的过期时间冲掉。
 * 一成员一键（{@code SET PX}）才能让「存活时间 = 该令牌剩余有效期」。
 *
 * <h2>故障时的行为是<b>刻意不对称</b>的</h2>
 * <ul>
 *   <li><b>写入失败 → 向上抛</b>（fail-close）。登出没能记录进黑名单，
 *       就不能返回「登出成功」——那正是原 FastAPI 版本
 *       {@code /auth/logout} 返回 {@code {"ok": true}} 却什么都没做的老毛病。
 *       抛出去让调用方看到 5xx，用户知道要重试。</li>
 *   <li><b>读取失败 → 视为未吊销</b>（fail-open）+ ERROR 日志 + 打点。
 *       读失败时若按「已吊销」处理，Redis 一抖动就是全站所有人被登出——
 *       用一个短暂的可利用窗口换整站不可用，不划算。
 *       代价是 Redis 不可用期间已登出的令牌能继续用，因此必须让
 *       {@code app.security.revocation.check_failed} 这个指标可告警。</li>
 * </ul>
 * 高安全场景（如资金操作）应把读取也改为 fail-close，并接受相应的可用性代价。
 */
public class RedisTokenRevocationStore implements TokenRevocationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenRevocationStore.class);

    private static final String TOKEN_PREFIX = ":jti:";
    private static final String SESSION_PREFIX = ":sid:";
    private static final String PRESENT = "1";

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final MeterRegistry meterRegistry;

    public RedisTokenRevocationStore(StringRedisTemplate redisTemplate,
                                    String keyPrefix,
                                    MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "stockanalyst:revoked" : keyPrefix;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void revoke(String tokenId, Instant expiresAt) {
        write(TOKEN_PREFIX, tokenId, expiresAt, "令牌");
    }

    @Override
    public void revokeSession(String sessionId, Instant expiresAt) {
        write(SESSION_PREFIX, sessionId, expiresAt, "会话");
    }

    @Override
    public boolean isRevoked(String tokenId) {
        return exists(TOKEN_PREFIX, tokenId);
    }

    @Override
    public boolean isSessionRevoked(String sessionId) {
        return exists(SESSION_PREFIX, sessionId);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>本实现返回 {@code -1}</b>：Redis 没有「按前缀计数」的原生命令，
     * 只能用 {@code SCAN} 遍历——那是 O(N) 操作，放在监控端点上会把
     * 一次健康检查变成整个键空间的遍历，本身就成了故障源。
     * 需要数量时应看 {@code INFO keyspace} 或在本类上另接一个自增计数器。
     */
    @Override
    public long size() {
        return -1L;
    }

    // ------------------------------------------------------------------

    private void write(String prefix, String rawId, Instant expiresAt, String kind) {
        if (rawId == null || rawId.isBlank() || expiresAt == null || "null".equals(rawId)) {
            return;
        }
        long ttlMillis = Duration.between(Instant.now(), expiresAt).toMillis();
        if (ttlMillis <= 0) {
            // 已过期的令牌无需拉黑，它本来就过不了签名校验
            return;
        }

        String key = keyPrefix + prefix + rawId;
        try {
            redisTemplate.opsForValue().set(key, PRESENT, Duration.ofMillis(ttlMillis));
        } catch (RuntimeException ex) {
            // 关键区别：写失败必须让调用方知道。返回成功会让用户以为已经登出，
            // 而实际上窗口期内那张令牌仍然可用——这正是本类要修掉的那类「假登出」。
            log.error("写入吊销记录失败，登出未能生效 kind={} id={} ttl={}ms",
                    kind, rawId, ttlMillis, ex);
            meterRegistry.counter("app.security.revocation.write_failed",
                    "kind", kind).increment();
            throw ex;
        }
    }

    /**
     * 查询吊销记录。
     *
     * <p>参数形状刻意与 {@link #write} 保持一致（前缀 + 原始标识），
     * 两边都自己拼完整键。曾经写成「调用方拼好键传进来」，
     * 结果读路径漏了 {@code keyPrefix}、写路径带了——读的键和写的键不是同一个，
     * <b>吊销写进去了但永远查不到，登出 100% 静默失效</b>，而且日志里一行异常都没有。
     * 两条路径同一个形状，就不容易只改对一边。
     */
    private boolean exists(String prefix, String rawId) {
        // 与内存实现保持同样的语义：缺失/空白/null 的标识一律视为「未吊销」，
        // 否则 sid 机制上线前的老令牌会在发版瞬间全部掉线
        if (rawId == null || rawId.isBlank() || "null".equals(rawId)) {
            return false;
        }
        String key = keyPrefix + prefix + rawId;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (RuntimeException ex) {
            log.error("查询吊销记录失败，本次按「未吊销」处理（fail-open）key={}", key, ex);
            meterRegistry.counter("app.security.revocation.check_failed").increment();
            return false;
        }
    }
}
