package com.stocklab.analyst.common.ratelimit;

import com.stocklab.analyst.config.properties.RateLimitProperties;
import com.stocklab.analyst.support.RedisTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对着<b>真 Redis</b> 跑 {@link RedisTokenBucketRateLimiter} 的 Lua 脚本。
 *
 * <h2>为什么必须有这个测试</h2>
 * {@code RedisTokenBucketRateLimiterTest} 把 {@code StringRedisTemplate}
 * 换成了替身，证明的只是 Java 侧的换算与解析——<b>Lua 脚本一行都没执行过</b>。
 * 而这件事恰恰是整个分布式限流唯一不可替代的部分：脚本在 Redis 里
 * 原子完成「读 → 补 → 判 → 写」，用 Java 侧的任何替身都验证不了。
 * 没有真 Redis 时，这个类是「代码在但未验证」。
 *
 * <h2>为什么不用 Testcontainers</h2>
 * 它需要 Docker 守护进程，而本机与部分 CI 环境都没有；更关键的是
 * 它会引入一整棵新依赖树，把「离线可构建」这个前提打破。
 * 用「一个环境变量 + 一个 CI service container」能达到同样的验证强度，
 * 依赖增量为零。连接与跳过的公共逻辑见 {@link RedisTestSupport}。
 */
@DisplayName("Redis 令牌桶限流器（真 Redis / Lua）")
class RedisTokenBucketRateLimiterRedisTest extends RedisTestSupport {

    /** 每个用例用独立的键前缀，桶之间天然隔离。 */
    private static RedisTokenBucketRateLimiter limiter(String bucket,
                                                       int strictPerMin, int strictBurst,
                                                       int loosePerMin, int looseBurst) {
        RateLimitProperties properties = new RateLimitProperties(
                Boolean.TRUE, "redis", namespace(bucket),
                strictPerMin, strictBurst, loosePerMin, looseBurst, false, "X-Forwarded-For");
        return new RedisTokenBucketRateLimiter(redisTemplate(), properties);
    }

    // ==================================================================
    /**
     * 这一组是「Lua 到底在不在扣令牌」的直接证据。
     */
    @Nested
    @DisplayName("令牌扣减")
    class TokenAccounting {

        @Test
        @DisplayName("突发容量内的请求全部放行，第 capacity+1 次被拒")
        void exhaustsBurstThenRejects() {
            // 速率设得极低（1 次/分），把「补充」这个变量从测试里排除掉，
            // 于是桶里有多少令牌完全由突发容量决定
            RedisTokenBucketRateLimiter limiter =
                    limiter("exhaust", 1, 5, 1, 5);

            int allowed = 0;
            for (int i = 0; i < 8; i++) {
                if (limiter.acquire("client-a", RateLimitScope.STRICT).allowed()) {
                    allowed++;
                }
            }

            assertThat(allowed)
                    .as("Lua 脚本必须在 Redis 侧原子扣减；扣不动就等于限流是装饰")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("剩余令牌随消耗逐次递减")
        void remainingDecreases() {
            RedisTokenBucketRateLimiter limiter = limiter("decrease", 1, 10, 1, 10);

            double first = limiter.acquire("client-b", RateLimitScope.STRICT).remainingTokens();
            double second = limiter.acquire("client-b", RateLimitScope.STRICT).remainingTokens();
            double third = limiter.acquire("client-b", RateLimitScope.STRICT).remainingTokens();

            assertThat(second).isLessThan(first);
            assertThat(third).isLessThan(second);
        }

        @Test
        @DisplayName("被拒时桶内令牌确实不足以支付本次请求")
        void rejectionImpliesInsufficientTokens() {
            RedisTokenBucketRateLimiter limiter = limiter("insufficient", 1, 3, 1, 3);

            for (int i = 0; i < 3; i++) {
                limiter.acquire("client-c", RateLimitScope.STRICT);
            }
            RateLimitResult rejected = limiter.acquire("client-c", RateLimitScope.STRICT);

            assertThat(rejected.allowed()).isFalse();
            assertThat(rejected.remainingTokens()).isLessThan(1.0);
        }

        @Test
        @DisplayName("被拒时不扣令牌：剩余量不会被拒绝请求进一步拉低")
        void rejectionDoesNotConsumeTokens() {
            RedisTokenBucketRateLimiter limiter = limiter("noconusme", 1, 2, 1, 2);

            limiter.acquire("client-d", RateLimitScope.STRICT);
            limiter.acquire("client-d", RateLimitScope.STRICT);

            double afterFirstRejection = limiter.acquire("client-d", RateLimitScope.STRICT).remainingTokens();
            double afterSecondRejection = limiter.acquire("client-d", RateLimitScope.STRICT).remainingTokens();

            assertThat(afterSecondRejection)
                    .as("拒绝还要扣令牌会让桶被「欠账」压死，永远补不回来")
                    // 两次调用之间会按时间补充极微量令牌（速率 1/分 ≈ 每次几微秒几个纳令牌），
                    // 所以只能按容差比较。用 isEqualTo 会变成一条随机器快慢偶发失败的用例。
                    .isCloseTo(afterFirstRejection, org.assertj.core.data.Offset.offset(0.01));
        }
    }

    // ==================================================================
    /**
     * 多实例语义——这是选用 Redis 桶而不是内存桶的<b>唯一理由</b>。
     *
     * <p>两个各自持有连接的限流器实例模拟两个应用副本，共享同一份配额。
     * 内存桶在这里必然失败（各算各的），所以这条用例同时也是
     * 「实现选对了没有」的判据。
     */
    @Nested
    @DisplayName("多实例共享配额")
    class MultiInstance {

        @Test
        @DisplayName("两个实例共享同一配额，合计不超过突发容量")
        void twoInstancesShareOneBucket() {
            RedisTokenBucketRateLimiter instanceA = limiter("shared", 1, 6, 1, 6);
            RedisTokenBucketRateLimiter instanceB = limiter("shared", 1, 6, 1, 6);

            int allowed = 0;
            for (int i = 0; i < 6; i++) {
                if (instanceA.acquire("client-x", RateLimitScope.STRICT).allowed()) {
                    allowed++;
                }
                if (instanceB.acquire("client-x", RateLimitScope.STRICT).allowed()) {
                    allowed++;
                }
            }

            assertThat(allowed)
                    .as("两个副本各放行 6 次说明配额没有共享，Redis 桶退化成了内存桶")
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("一个实例消耗掉的配额，另一个实例也看得见")
        void consumptionIsVisibleAcrossInstances() {
            RedisTokenBucketRateLimiter instanceA = limiter("visible", 1, 4, 1, 4);
            RedisTokenBucketRateLimiter instanceB = limiter("visible", 1, 4, 1, 4);

            instanceA.acquire("client-y", RateLimitScope.STRICT);
            instanceA.acquire("client-y", RateLimitScope.STRICT);

            double seenByB = instanceB.acquire("client-y", RateLimitScope.STRICT).remainingTokens();

            assertThat(seenByB)
                    .as("B 如果读到满桶，说明它算的是自己的本地状态")
                    .isLessThan(4.0);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("键隔离与回收")
    class IsolationAndExpiry {

        @Test
        @DisplayName("不同档位互不消耗对方配额")
        void scopesAreIsolated() {
            RedisTokenBucketRateLimiter limiter = limiter("scope-iso", 1, 2, 1, 2);

            limiter.acquire("client-s", RateLimitScope.STRICT);
            limiter.acquire("client-s", RateLimitScope.STRICT);
            assertThat(limiter.acquire("client-s", RateLimitScope.STRICT).allowed()).isFalse();

            assertThat(limiter.acquire("client-s", RateLimitScope.LOOSE).allowed())
                    .as("严格桶被刷穿后宽松桶也一起挂掉，等于把全站读接口搭进去了")
                    .isTrue();
        }

        @Test
        @DisplayName("不同客户端互不消耗对方配额")
        void clientsAreIsolated() {
            RedisTokenBucketRateLimiter limiter = limiter("client-iso", 1, 2, 1, 2);

            limiter.acquire("attacker", RateLimitScope.STRICT);
            limiter.acquire("attacker", RateLimitScope.STRICT);

            assertThat(limiter.acquire("attacker", RateLimitScope.STRICT).allowed()).isFalse();
            assertThat(limiter.acquire("normal-user", RateLimitScope.STRICT).allowed())
                    .as("一个客户端刷爆就把所有人打回 429，是限流最常见的线上事故形态")
                    .isTrue();
        }

        /**
         * 桶键必须带过期时间。
         *
         * <p>没有 PEXPIRE 的话，每个「客户端 × 档位」组合都会在 Redis 里
         * 永久留一个 Hash。真实环境里客户端标识来自 IP，
         * 这是个天然无界增长的键空间——几天就能把内存吃满。
         */
        @Test
        @DisplayName("桶键写完后带 TTL，不会永久驻留")
        void bucketKeyHasTtl() {
            RedisTokenBucketRateLimiter limiter = limiter("ttl", 60, 600, 1, 1);

            limiter.acquire("client-ttl", RateLimitScope.STRICT);

            String key = namespace("ttl") + ":rl:STRICT:client-ttl";
            Long ttl = redisTemplate().getExpire(key);
            assertThat(ttl)
                    .as("桶键没有 TTL 就是无界增长：客户端标识来自 IP，键空间天然无上限")
                    .isNotNull()
                    .isGreaterThan(0L);
        }

        @Test
        @DisplayName("桶状态存为 Hash，含 tokens 与 ts 两个字段")
        void bucketStateIsHash() {
            RedisTokenBucketRateLimiter limiter = limiter("hash", 1, 7, 1, 7);

            limiter.acquire("client-h", RateLimitScope.STRICT);

            var entries = redisTemplate().<String, String>opsForHash()
                    .entries(namespace("hash") + ":rl:STRICT:client-h");
            assertThat(entries).containsKeys("tokens", "ts");
        }
    }

    // ==================================================================
    /**
     * 令牌会随时间补充。
     *
     * <p>时间和补充速率耦合的断言只能写成区间：CI 机器的调度抖动无法预测，
     * 断言「恰好补了 N 个」会变成偶发失败——那种红比没有测试更糟，
     * 因为它会训练人忽略红。
     */
    @Nested
    @DisplayName("令牌补充")
    class Refill {

        @Test
        @DisplayName("等待一段时间后桶会补充令牌，但不超过容量上限")
        void refillsWithoutExceedingCapacity() throws InterruptedException {
            // 速率 600/分 = 10 个/秒，容量 5：等 1 秒理论上补 10 个，
            // 但不该超过容量。若实现漏了 math.min，这里会看到 10 以上
            RedisTokenBucketRateLimiter limiter = limiter("refill", 1, 5, 600, 5);

            for (int i = 0; i < 5; i++) {
                limiter.acquire("client-r", RateLimitScope.LOOSE);
            }
            assertThat(limiter.acquire("client-r", RateLimitScope.LOOSE).allowed()).isFalse();

            Thread.sleep(1_000L);

            double remaining = limiter.acquire("client-r", RateLimitScope.LOOSE).remainingTokens();
            assertThat(remaining)
                    .as("补充必须被 math.min(capacity, ...) 截断，否则桶会越攒越大、限流上限失效")
                    .isBetween(0.0, 5.0);
        }
    }
}
