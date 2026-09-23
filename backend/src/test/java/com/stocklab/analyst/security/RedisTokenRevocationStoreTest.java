package com.stocklab.analyst.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RedisTokenRevocationStore} 的 Java 侧契约测试。
 *
 * <h2>能证明什么、不能证明什么</h2>
 * <b>能证明</b>：键怎么拼、TTL 怎么算、空标识怎么挡、写入失败与读取失败
 * 各走哪条路、指标有没有打。<b>不能证明</b>跨实例可见性——
 * 那需要两个真实客户端连同一个 Redis，在
 * {@code RedisTokenBucketRateLimiterRedisTest} 的同类里由 CI 验证。
 *
 * <h2>本类最该钉住的是「故障时的不对称」</h2>
 * 写入失败必须向上抛（否则就是假登出），读取失败必须放行（否则 Redis 一抖动
 * 全站被登出）。这两条写反都不会报错，只会让「登出没生效」或
 * 「Redis 抖一次全站掉线」以线上事故的形式被发现。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("会话吊销存储（Redis 版）")
class RedisTokenRevocationStoreTest {

    private static final String PREFIX = "test:revoked";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private RedisTokenRevocationStore store() {
        return new RedisTokenRevocationStore(redisTemplate, PREFIX, registry);
    }

    private RedisTokenRevocationStore storeWithPrefix(String prefix) {
        return new RedisTokenRevocationStore(redisTemplate, prefix, registry);
    }

    /** 捕获写入用的 (键, TTL)。 */
    private record Written(String key, Duration ttl) {
    }

    private Written capturedWrite() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(keyCaptor.capture(), anyString(), ttlCaptor.capture());
        return new Written(keyCaptor.getValue(), ttlCaptor.getValue());
    }

    private double metric(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    // ==================================================================
    @Nested
    @DisplayName("键与 TTL")
    class KeyAndTtl {

        @Test
        @DisplayName("令牌级键为「前缀:jti:令牌ID」")
        void tokenKeyFormat() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            Instant expiry = Instant.now().plus(Duration.ofMinutes(30));

            store().revoke("jti-1", expiry);

            assertThat(capturedWrite().key()).isEqualTo(PREFIX + ":jti:jti-1");
        }

        /**
         * 会话级与令牌级必须落在不同键上。
         *
         * <p>共用一个命名空间会撞键：登出时写入的 sessionId 若和某个 jti
         * 字符串相同，就会把无关的令牌一起吊销，或者反过来漏掉。
         */
        @Test
        @DisplayName("会话级键为「前缀:sid:会话ID」，与令牌级不撞键")
        void sessionKeyFormat() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            Instant expiry = Instant.now().plus(Duration.ofDays(30));

            store().revokeSession("sid-1", expiry);

            assertThat(capturedWrite().key()).isEqualTo(PREFIX + ":sid:sid-1");
        }

        @Test
        @DisplayName("TTL 等于令牌自身剩余有效期")
        void ttlMatchesRemainingLifetime() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            Instant expiry = Instant.now().plus(Duration.ofMinutes(10));

            store().revoke("jti-1", expiry);

            // 允许一点执行耗时误差：TTL 是「此刻到过期的剩余量」，不是固定值
            assertThat(capturedWrite().ttl())
                    .isBetween(Duration.ofMinutes(9).plusSeconds(50), Duration.ofMinutes(10));
        }

        @Test
        @DisplayName("键前缀跟着配置走")
        void honoursConfiguredPrefix() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            storeWithPrefix("my-app:revoked")
                    .revoke("jti-1", Instant.now().plus(Duration.ofMinutes(5)));

            assertThat(capturedWrite().key()).startsWith("my-app:revoked:jti:");
        }

        @Test
        @DisplayName("前缀缺省时有兜底值，不会拼出「:jti:xxx」这种裸键")
        void blankPrefixFallsBack() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            storeWithPrefix("  ")
                    .revoke("jti-1", Instant.now().plus(Duration.ofMinutes(5)));

            assertThat(capturedWrite().key()).isEqualTo("stockanalyst:revoked:jti:jti-1");
        }
    }

    // ==================================================================
    /**
     * 与内存实现保持同样的「不写」条件。
     *
     * <p>这些边界看起来琐碎，但漏掉任何一条的后果都是静默多写一条
     * 永不失效的键：{@code expiresAt} 为 null 时会 NPE 崩在登出路径上；
     * 已过期的令牌本来过不了签名校验，写进去纯属占内存；
     * 标识为空白时写入的键会被所有此类请求共用。
     */
    @Nested
    @DisplayName("不该写入的情况")
    class NoWriteCases {

        @Test
        @DisplayName("令牌 ID 为 null / 空白 / 字面 null 时不写")
        void skipsBlankIds() {
            RedisTokenRevocationStore store = store();
            Instant expiry = Instant.now().plus(Duration.ofMinutes(5));

            store.revoke(null, expiry);
            store.revoke("   ", expiry);
            store.revoke("null", expiry);
            store.revokeSession(null, expiry);
            store.revokeSession("", expiry);

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("过期时间为 null 时不写")
        void skipsNullExpiry() {
            store().revoke("jti-1", null);

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("已经过期的令牌不写")
        void skipsAlreadyExpired() {
            store().revoke("jti-1", Instant.now().minus(Duration.ofMinutes(1)));

            verifyNoInteractions(redisTemplate);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("查询")
    class Lookup {

        @Test
        @DisplayName("键存在时视为已吊销")
        void existingKeyMeansRevoked() {
            when(redisTemplate.hasKey(PREFIX + ":jti:jti-1")).thenReturn(true);

            assertThat(store().isRevoked("jti-1")).isTrue();
        }

        @Test
        @DisplayName("键不存在时视为未吊销")
        void missingKeyMeansNotRevoked() {
            when(redisTemplate.hasKey(PREFIX + ":jti:jti-9")).thenReturn(false);

            assertThat(store().isRevoked("jti-9")).isFalse();
        }

        @Test
        @DisplayName("Redis 返回 null（而非 false）时视为未吊销")
        void nullReplyMeansNotRevoked() {
            when(redisTemplate.hasKey(PREFIX + ":jti:jti-1")).thenReturn(null);

            assertThat(store().isRevoked("jti-1")).isFalse();
        }

        @Test
        @DisplayName("会话键查的是 sid 命名空间")
        void sessionLookupUsesSessionNamespace() {
            when(redisTemplate.hasKey(PREFIX + ":sid:sid-1")).thenReturn(true);

            assertThat(store().isSessionRevoked("sid-1")).isTrue();
        }

        /**
         * sid 机制上线前签发的老令牌不含 {@code sid} 声明。
         * 若把「缺失 sid」当成「已吊销」，发版瞬间所有存量会话一起掉线。
         */
        @Test
        @DisplayName("sid 为 null / 空白 / 字面 null 时一律视为未吊销（老令牌兼容）")
        void blankSessionIdIsNotRevoked() {
            RedisTokenRevocationStore store = store();

            assertThat(store.isSessionRevoked(null)).isFalse();
            assertThat(store.isSessionRevoked("   ")).isFalse();
            assertThat(store.isSessionRevoked("null")).isFalse();

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("size() 返回 -1，表示本实现不提供精确计数")
        void sizeIsUnavailable() {
            assertThat(store().size())
                    .as("Redis 没有按前缀计数的原生命令，用 SCAN 会让监控端点变成 O(N) 遍历")
                    .isEqualTo(-1L);
        }
    }

    // ==================================================================
    /**
     * 故障处理刻意不对称：写失败上抛，读失败放行。
     *
     * <p>这是本类唯一「写反了也不会报错」的地方，因此逐条钉住。
     */
    @Nested
    @DisplayName("Redis 故障")
    class FailureHandling {

        @Test
        @DisplayName("写入失败必须上抛：登出没能记录就不能报成功")
        void writeFailurePropagates() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            // ValueOperations.set(K, V, Duration) 返回 void，只能用 doThrow 打桩——
            // 写成 when(...) 会在编译期报「此处不允许使用 '空' 类型」
            doThrow(new RedisConnectionFailureException("连接被拒绝"))
                    .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

            RedisTokenRevocationStore store = store();

            assertThatThrownBy(() -> store.revoke("jti-1", Instant.now().plus(Duration.ofMinutes(5))))
                    .as("吞掉写失败就是原 FastAPI 版本那种「返回 ok 但什么都没做」的假登出")
                    .isInstanceOf(RedisConnectionFailureException.class);
        }

        @Test
        @DisplayName("写入失败打点并带 kind 标签，便于区分登出与令牌轮转")
        void writeFailureIsCounted() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            // ValueOperations.set(K, V, Duration) 返回 void，只能用 doThrow 打桩——
            // 写成 when(...) 会在编译期报「此处不允许使用 '空' 类型」
            doThrow(new RedisConnectionFailureException("连接被拒绝"))
                    .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

            RedisTokenRevocationStore store = store();
            try {
                store.revokeSession("sid-1", Instant.now().plus(Duration.ofMinutes(5)));
            } catch (RuntimeException ignored) {
                // 已由上一组断言覆盖，这里只关心指标
            }

            assertThat(metric("app.security.revocation.write_failed", "kind", "会话")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("读取失败时按「未吊销」处理（fail-open），不抛异常")
        void readFailureFailsOpen() {
            when(redisTemplate.hasKey(anyString()))
                    .thenThrow(new RedisConnectionFailureException("连接被拒绝"));

            assertThat(store().isRevoked("jti-1"))
                    .as("读失败按已吊销处理，等于 Redis 一抖动全站所有人被登出")
                    .isFalse();
        }

        @Test
        @DisplayName("读取失败打点，且指标可用来告警")
        void readFailureIsCounted() {
            when(redisTemplate.hasKey(anyString()))
                    .thenThrow(new RedisConnectionFailureException("连接被拒绝"));

            store().isRevoked("jti-1");
            store().isSessionRevoked("sid-1");

            assertThat(metric("app.security.revocation.check_failed")).isEqualTo(2.0);
        }

        @Test
        @DisplayName("成功路径不产生任何故障指标")
        void successCountsNoFailures() {
            when(redisTemplate.hasKey(anyString())).thenReturn(false);

            store().isRevoked("jti-1");

            assertThat(metric("app.security.revocation.check_failed")).isZero();
            assertThat(metric("app.security.revocation.write_failed", "kind", "令牌")).isZero();
        }
    }
}
