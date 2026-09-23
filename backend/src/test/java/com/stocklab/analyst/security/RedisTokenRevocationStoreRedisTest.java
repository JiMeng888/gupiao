package com.stocklab.analyst.security;

import com.stocklab.analyst.support.RedisTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对着<b>真 Redis</b> 验证 {@link RedisTokenRevocationStore} 的跨实例可见性。
 *
 * <h2>这个测试回答的是「分布式会话到底成不成立」</h2>
 * {@code RedisTokenRevocationStoreTest} 用替身验证了键怎么拼、失败怎么处理，
 * 但它<b>证明不了</b>「在 A 实例登出、B 实例也看不到那张令牌」——
 * 而那正是换成 Redis 实现的全部理由。内存实现同样能通过所有替身测试，
 * 却在这个场景下彻底失效。所以这一条只能靠真 Redis。
 *
 * <p>验证方式是造两个各自独立的 {@link RedisTokenRevocationStore} 对象
 * （模拟两个应用副本）。它们之间没有任何共享的 Java 对象，
 * 唯一共同点是同一个 Redis——所以只要能互相看到对方写的吊销记录，
 * 就说明状态确实落在 Redis 而不是进程内存里。
 *
 * <h2>为什么这次没有用两个 Redis 客户端</h2>
 * 要证的是「状态在 Redis 而不在对象里」，两个 store 对象已经足够了。
 * 真实副本之间用的是各自的连接，那是网络层的差别，不影响本测试的结论。
 */
@DisplayName("会话吊销存储（真 Redis / 跨实例）")
class RedisTokenRevocationStoreRedisTest extends RedisTestSupport {

    /** 模拟一个应用副本。 */
    private RedisTokenRevocationStore instance(String tag) {
        return new RedisTokenRevocationStore(
                redisTemplate(), namespace(tag), new SimpleMeterRegistry());
    }

    // ==================================================================
    /**
     * 本类最重要的一组。内存实现在这里必然失败。
     */
    @Nested
    @DisplayName("跨实例可见性")
    class CrossInstance {

        @Test
        @DisplayName("A 实例拉黑的会话，B 实例立刻看得到")
        void sessionRevocationIsVisibleAcrossInstances() {
            RedisTokenRevocationStore instanceA = instance("cross-sid");
            RedisTokenRevocationStore instanceB = instance("cross-sid");
            String sid = "sid-" + System.nanoTime();

            assertThat(instanceB.isSessionRevoked(sid))
                    .as("基线：还没登出时 B 应该认为是有效的")
                    .isFalse();

            instanceA.revokeSession(sid, Instant.now().plus(Duration.ofMinutes(30)));

            assertThat(instanceB.isSessionRevoked(sid))
                    .as("B 看不到 A 的登出，就是「多副本下登出无效」——内存实现的固有问题")
                    .isTrue();
        }

        @Test
        @DisplayName("A 实例拉黑的令牌，B 实例立刻看得到")
        void tokenRevocationIsVisibleAcrossInstances() {
            RedisTokenRevocationStore instanceA = instance("cross-jti");
            RedisTokenRevocationStore instanceB = instance("cross-jti");
            String jti = "jti-" + System.nanoTime();

            instanceA.revoke(jti, Instant.now().plus(Duration.ofMinutes(5)));

            assertThat(instanceB.isRevoked(jti)).isTrue();
        }

        /**
         * 令牌级与会话级必须互不干扰。
         *
         * <p>读的时候用了同一个标识串，若不区分命名空间，
         * 只登出一个令牌会连带吊销整个会话——症状是「换了台设备登录，
         * 另一台莫名其妙掉了」。
         */
        @Test
        @DisplayName("同一标识串下，令牌级与会话级互不干扰")
        void tokenAndSessionNamespacesAreIndependent() {
            RedisTokenRevocationStore store = instance("ns-isolate");
            String sameId = "same-" + System.nanoTime();

            store.revoke(sameId, Instant.now().plus(Duration.ofMinutes(5)));

            assertThat(store.isRevoked(sameId)).isTrue();
            assertThat(store.isSessionRevoked(sameId))
                    .as("拉黑一个令牌不该顺带把同名会话也吊销掉")
                    .isFalse();
        }

        @Test
        @DisplayName("不同前缀的实例互不可见（前缀即隔离边界）")
        void differentPrefixesAreIsolated() {
            RedisTokenRevocationStore storeA = instance("prefix-a");
            RedisTokenRevocationStore storeB = instance("prefix-b");
            String sid = "sid-" + System.nanoTime();

            storeA.revokeSession(sid, Instant.now().plus(Duration.ofMinutes(30)));

            assertThat(storeB.isSessionRevoked(sid)).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("过期")
    class Expiry {

        @Test
        @DisplayName("写入的键带 TTL，过期后自动消失不再吊销")
        void revocationExpiresOnItsOwn() throws InterruptedException {
            RedisTokenRevocationStore store = instance("ttl-expiry");
            String sid = "sid-" + System.nanoTime();

            store.revokeSession(sid, Instant.now().plusMillis(1_500));
            assertThat(store.isSessionRevoked(sid)).isTrue();

            // 等 TTL 过去。这里必须真的等：过期是 Redis 侧的行为，
            // 没有可控时钟可以注入；用 Thread.sleep 换来的是对真实 TTL 语义的验证
            Thread.sleep(2_000L);

            assertThat(store.isSessionRevoked(sid))
                    .as("没有 TTL 的黑名单会永久驻留：客户端标识来自 IP，键空间无上限")
                    .isFalse();
        }

        @Test
        @DisplayName("已经过期的令牌不写入任何键")
        void alreadyExpiredTokenIsNotWritten() {
            RedisTokenRevocationStore store = instance("already-expired");
            String sid = "sid-" + System.nanoTime();

            store.revokeSession(sid, Instant.now().minus(Duration.ofMinutes(1)));

            assertThat(store.isSessionRevoked(sid)).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("登出流程的端到端形状")
    class LogoutShape {

        /**
         * 复刻 {@code AuthService#logout} 实际会做的两件事：
         * 按 sid 吊销整个会话，同时按 jti 拉黑那张 refresh token。
         *
         * <p>只做前一半时，尚未过期的 Access Token 还能继续用；
         * 只做后一半时，登出只黑掉一张令牌、同会话的其他令牌仍在。
         * 两半都必须在 Redis 里留下痕迹，并且另一个实例都看得到。
         */
        @Test
        @DisplayName("按 sid 与会话内令牌同时吊销，另一实例两项都看得到")
        void bothRevocationsAreRecorded() {
            RedisTokenRevocationStore writer = instance("logout-flow");
            RedisTokenRevocationStore reader = instance("logout-flow");
            String sid = "sid-" + System.nanoTime();
            String jti = "jti-" + System.nanoTime();
            Instant sessionExpiry = Instant.now().plus(Duration.ofDays(30));

            writer.revokeSession(sid, sessionExpiry);
            writer.revoke(jti, Instant.now().plus(Duration.ofDays(30)));

            assertThat(reader.isSessionRevoked(sid)).isTrue();
            assertThat(reader.isRevoked(jti)).isTrue();
        }

        @Test
        @DisplayName("其他会话不受影响：登出一个会话不该动到同用户的另一个会话")
        void otherSessionsSurvive() {
            RedisTokenRevocationStore store = instance("multi-session");
            String loggedOut = "sid-out-" + System.nanoTime();
            String stillActive = "sid-in-" + System.nanoTime();
            Instant expiry = Instant.now().plus(Duration.ofDays(30));

            store.revokeSession(loggedOut, expiry);

            assertThat(store.isSessionRevoked(loggedOut)).isTrue();
            assertThat(store.isSessionRevoked(stillActive))
                    .as("「手机上登出，电脑上也被踢」通常就是这里退化成按用户维度吊销了")
                    .isFalse();
        }
    }
}
