package com.stocklab.analyst.common.ratelimit;

import com.stocklab.analyst.config.properties.RateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InMemoryTokenBucketRateLimiter} 单测。
 *
 * <p>为什么这个类值得单独测：限流器是**整个项目里唯一一个「出错不会报错、
 * 只会安静放行或安静拦截」的组件**。放行过头 = 保护失效；拦截过头 = 正常用户
 * 被挡在门外。两种失败都不会抛异常、不会写 ERROR 日志，只能靠断言发现。
 *
 * <p>它此前完全没有任何测试覆盖——本来唯一的间接验证途径是
 * {@code smoke-test.sh} 命中 429，但那是「靠踩到才发现」，不是测试。
 */
@DisplayName("内存令牌桶限流器")
class InMemoryTokenBucketRateLimiterTest {

    /** 构造一份可控配置：速率与容量都取整数，避免浮点小数让断言变得难读。 */
    private static RateLimitProperties props(int strictPerMin, int strictBurst,
                                             int loosePerMin, int looseBurst) {
        return new RateLimitProperties(
                Boolean.TRUE, "memory", "test",
                strictPerMin, strictBurst,
                loosePerMin, looseBurst,
                false, "X-Forwarded-For");
    }

    /**
     * 把速率设为 0 造成「永不补充」，把时间维度从测试里彻底排除。
     *
     * <p>这不是在回避测试，而是在消除不确定性：桶的补充走的是
     * {@code System.nanoTime()}，想验证补充就必须 sleep，而 sleep 测试在
     * CI 负载高时会变成随机失败。真正需要验证时间行为的只有一处
     * （见「补充」那一组），那里用按速率反推出的极短 sleep，且断言留了余量。
     */
    private static InMemoryTokenBucketRateLimiter frozen(int burst) {
        return new InMemoryTokenBucketRateLimiter(props(0, burst, 0, burst));
    }

    // ==================================================================
    @Nested
    @DisplayName("容量与拒绝")
    class Capacity {

        @Test
        @DisplayName("桶容量内全部放行，第 burst+1 次被拒")
        void rejectsExactlyAfterBurst() {
            InMemoryTokenBucketRateLimiter limiter = frozen(3);

            for (int i = 0; i < 3; i++) {
                assertThat(limiter.acquire("1.2.3.4", RateLimitScope.STRICT).allowed())
                        .as("第 %d 次请求应在容量内", i + 1)
                        .isTrue();
            }

            RateLimitResult rejected = limiter.acquire("1.2.3.4", RateLimitScope.STRICT);
            assertThat(rejected.allowed())
                    .as("容量 3 的第 4 次请求必须被拒——少拦一次等于限流没生效")
                    .isFalse();
        }

        @Test
        @DisplayName("被拒时 retryAfterSeconds 至少为 1，不会给出「等 0 秒」这种无意义建议")
        void rejectedResultCarriesUsableRetryHint() {
            InMemoryTokenBucketRateLimiter limiter = frozen(1);
            limiter.acquire("1.2.3.4", RateLimitScope.STRICT);

            RateLimitResult rejected = limiter.acquire("1.2.3.4", RateLimitScope.STRICT);
            assertThat(rejected.allowed()).isFalse();
            assertThat(rejected.retryAfterSeconds())
                    .as("速率极低时 deficit/rate 会算出很大的值，但不能是 0")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("放行时 retryAfterSeconds 为 0")
        void passedResultHasNoRetryHint() {
            RateLimitResult passed = frozen(1).acquire("1.2.3.4", RateLimitScope.STRICT);
            assertThat(passed.allowed()).isTrue();
            assertThat(passed.retryAfterSeconds()).isZero();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("隔离：不同 key / 不同档位不共享配额")
    class Isolation {

        @Test
        @DisplayName("不同客户端 IP 各自独立计数")
        void differentKeysDoNotShareQuota() {
            InMemoryTokenBucketRateLimiter limiter = frozen(2);

            limiter.acquire("1.1.1.1", RateLimitScope.STRICT);
            limiter.acquire("1.1.1.1", RateLimitScope.STRICT);
            assertThat(limiter.acquire("1.1.1.1", RateLimitScope.STRICT).allowed())
                    .as("A 应该已经用完了")
                    .isFalse();

            assertThat(limiter.acquire("2.2.2.2", RateLimitScope.STRICT).allowed())
                    .as("B 是新客户端，必须能正常访问——把 A 的配额算到 B 头上就是误伤")
                    .isTrue();
        }

        /**
         * 这条是本组最关键的断言。
         *
         * <p>桶的 key 是 {@code key + '|' + scope.name()}。如果哪天有人图省事
         * 把 scope 从 key 里去掉，STRICT 的消耗会直接吃掉 LOOSE 的额度——
         * 表现是「登录几次之后首页的行情接口就开始 429」，而限流代码本身看不出问题。
         */
        @Test
        @DisplayName("同一客户端的 STRICT 与 LOOSE 是两个桶，互不挤占")
        void scopesHaveSeparateBuckets() {
            InMemoryTokenBucketRateLimiter limiter = frozen(2);

            limiter.acquire("1.1.1.1", RateLimitScope.STRICT);
            limiter.acquire("1.1.1.1", RateLimitScope.STRICT);
            assertThat(limiter.acquire("1.1.1.1", RateLimitScope.STRICT).allowed()).isFalse();

            assertThat(limiter.acquire("1.1.1.1", RateLimitScope.LOOSE).allowed())
                    .as("STRICT 用尽不得影响 LOOSE，否则登录一次就会让首页行情挂掉")
                    .isTrue();
        }

        @Test
        @DisplayName("activeBucketCount 反映实际桶数：2 个 IP × 2 个档位 = 4")
        void bucketCountMatchesKeyScopePairs() {
            InMemoryTokenBucketRateLimiter limiter = frozen(5);

            limiter.acquire("1.1.1.1", RateLimitScope.STRICT);
            limiter.acquire("1.1.1.1", RateLimitScope.LOOSE);
            limiter.acquire("2.2.2.2", RateLimitScope.STRICT);
            limiter.acquire("2.2.2.2", RateLimitScope.LOOSE);

            assertThat(limiter.activeBucketCount()).isEqualTo(4);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("补充")
    class Refill {

        /**
         * 唯一一处必须依赖真实时间的测试。
         *
         * <p>做法：桶容量 100、速率 600/min（= 10/s）。先把 100 个令牌打光，
         * 睡 500ms 后理论上补回约 5 个。
         *
         * <p>断言刻意写成一个**区间**而不是精确值：CI 上 sleep 实际睡多久不可控，
         * 写死 5 会在负载高时随机红。但区间也不能开到「大于 0」就完事——
         * 那样就区分不出「按速率补充」和「一次补满」，而后者会让桶退化成
         * 永久赦免，长期平均速率完全失效。
         *
         * <p>所以区间取 [1, 40]：下界排除「根本没补」，上界远离容量 100，
         * 排除「一次补满」。
         */
        @Test
        @DisplayName("空闲后按速率补充：既不是不补，也不是一次补满")
        void refillsAtConfiguredRate() throws InterruptedException {
            int capacity = 100;
            // 600/min = 10/s；睡 500ms 应补回约 5 个
            InMemoryTokenBucketRateLimiter limiter =
                    new InMemoryTokenBucketRateLimiter(props(600, capacity, 600, capacity));

            for (int i = 0; i < capacity; i++) {
                assertThat(limiter.acquire("9.9.9.9", RateLimitScope.STRICT).allowed()).isTrue();
            }
            assertThat(limiter.acquire("9.9.9.9", RateLimitScope.STRICT).allowed())
                    .as("桶应已被打光")
                    .isFalse();

            Thread.sleep(500);

            int passed = 0;
            for (int i = 0; i < 60; i++) {
                if (limiter.acquire("9.9.9.9", RateLimitScope.STRICT).allowed()) {
                    passed++;
                }
            }

            assertThat(passed)
                    .as("睡 500ms @ 10/s 应补回少量令牌；0 说明补充逻辑没生效，"
                            + "接近 100 说明是一次补满（桶退化成永久赦免）")
                    .isBetween(1, 40);
        }

        /**
         * 补充不能突破容量上限。
         *
         * <p>容量 5、速率 6000/min（= 100/s）：睡 1000ms 期间理论上会补 100 个令牌，
         * 但容量是 5，所以放行次数必须远小于 100。如果实现里漏掉了
         * {@code Math.min(capacity, ...)}，睡完之后就能连过 100 次——
         * 突发保护形同虚设。
         *
         * <p>断言写成区间而非等号是有意的：桶的补充依赖真实时间，
         * 循环里任何一次线程抢占都会多补进一两个令牌。用 [5, 15] 这个区间，
         * 既容得下抢占噪声，又和「没做截断」的 100 拉开了 6 倍以上差距。
         */
        @Test
        @DisplayName("补充被容量截断：速率再高也只能恢复 burst 个")
        void refillNeverExceedsCapacity() throws InterruptedException {
            int capacity = 5;
            // 100/s，即 10ms 补一个；睡 1s 的「无截断理论值」是 100
            InMemoryTokenBucketRateLimiter limiter =
                    new InMemoryTokenBucketRateLimiter(props(6000, capacity, 6000, capacity));

            // 打光容量
            for (int i = 0; i < capacity; i++) {
                assertThat(limiter.acquire("9.9.9.9", RateLimitScope.STRICT).allowed()).isTrue();
            }
            assertThat(limiter.acquire("9.9.9.9", RateLimitScope.STRICT).allowed())
                    .as("桶应已被打光")
                    .isFalse();

            Thread.sleep(1000);

            int passed = 0;
            for (int i = 0; i < 200; i++) {
                if (limiter.acquire("9.9.9.9", RateLimitScope.STRICT).allowed()) {
                    passed++;
                }
            }

            assertThat(passed)
                    .as("补满即止：无截断时会放行约 100 次，有截断时只有 5 次（+少量抢占噪声）")
                    .isBetween(capacity, 15);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("并发")
    class Concurrency {

        /**
         * 这条是整份测试里最有价值的一条。
         *
         * <p>{@code Bucket.tryAcquire} 用 {@code synchronized} 保证「补充 + 扣减」
         * 的原子性，注释里是这么写的——但注释不是证据。这里让 16 个线程各发 4 次
         * （共 64 次请求）去抢 20 个令牌，断言**恰好**放行 20 个。
         *
         * <p>速率设为 0，杜绝补充带来的干扰；如果实现里没做同步，
         * 「读 tokens → 判断 → 写 tokens」三步会被交错，放行数会超过容量。
         * 这类 bug 在单机低并发下永远不会暴露。
         */
        @Test
        @DisplayName("16 线程各发 4 次共 64 次请求抢 20 个令牌，恰好放行 20 个")
        void exactlyCapacityRequestsPassUnderContention() throws Exception {
            int capacity = 20;
            int acquiresPerThread = 4;
            InMemoryTokenBucketRateLimiter limiter = frozen(capacity);

            int threads = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Callable<Integer>> tasks = IntStream.range(0, threads)
                        .mapToObj(i -> (Callable<Integer>) () -> {
                            start.await();  // 所有线程在同一瞬间起跑，最大化竞争窗口
                            int ok = 0;
                            for (int k = 0; k < acquiresPerThread; k++) {
                                if (limiter.acquire("7.7.7.7", RateLimitScope.STRICT).allowed()) {
                                    ok++;
                                }
                            }
                            return ok;
                        })
                        .toList();

                List<Future<Integer>> futures = tasks.stream().map(pool::submit).toList();
                start.countDown();

                long passed = 0;
                for (Future<Integer> f : futures) {
                    passed += f.get(5, TimeUnit.SECONDS);
                }

                assertThat(passed)
                        .as("64 次请求抢 20 个令牌，放行数必须精确等于 20；"
                                + "大于 20 说明「读-判-写」不是原子的")
                        .isEqualTo(capacity);
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }

        @Test
        @DisplayName("并发下同一个 IP 只对应一个桶（computeIfAbsent 不产生重复桶）")
        void concurrentAccessCreatesSingleBucket() throws Exception {
            InMemoryTokenBucketRateLimiter limiter = frozen(1000);

            int threads = 32;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Callable<Void>> tasks = IntStream.range(0, threads)
                        .mapToObj(i -> (Callable<Void>) () -> {
                            start.await();
                            for (int k = 0; k < 50; k++) {
                                limiter.acquire("same-ip", RateLimitScope.STRICT);
                            }
                            return null;
                        })
                        .toList();

                List<Future<Void>> futures = tasks.stream().map(pool::submit).toList();
                start.countDown();
                for (Future<Void> f : futures) {
                    f.get(10, TimeUnit.SECONDS);
                }

                assertThat(limiter.activeBucketCount())
                        .as("32 线程 × 50 次同 IP 请求只应产生 1 个桶，桶泄漏同样是内存泄漏")
                        .isEqualTo(1);
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("回收空闲桶")
    class Eviction {

        /**
         * 只验证「不该被回收的不会被回收」。
         *
         * <p>「空闲 10 分钟后应被回收」这一半没法在这条测试里验证——真的等 10 分钟
         * 不现实，而把阈值改成可注入只是为了测试而加的抽象。这里退而求其次，
         * 至少锁住「回收动作不会误伤活跃桶」，剩下的靠代码走查。
         *
         * <p><b>未被验证的部分要写清楚，不能因为测试绿了就当成收回逻辑没问题。</b>
         */
        @Test
        @DisplayName("刚访问过的桶不会被回收")
        void recentlyUsedBucketsSurviveEviction() {
            InMemoryTokenBucketRateLimiter limiter = frozen(1);
            limiter.acquire("1.1.1.1", RateLimitScope.STRICT);
            assertThat(limiter.activeBucketCount()).isEqualTo(1);

            limiter.evictIdleBuckets();

            assertThat(limiter.activeBucketCount())
                    .as("刚用过的桶若被回收，等于攻击者只要诱导一次 GC 就能重置配额")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("没有桶时执行回收不抛异常")
        void evictionOnEmptyLimiterIsSafe() {
            InMemoryTokenBucketRateLimiter limiter = frozen(1);
            limiter.evictIdleBuckets();
            assertThat(limiter.activeBucketCount()).isZero();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("stats 监控输出")
    class Stats {

        @Test
        @DisplayName("stats 暴露存储类型、活跃桶数与两份速率配置")
        void statsExposeOperationalFields() {
            InMemoryTokenBucketRateLimiter limiter =
                    new InMemoryTokenBucketRateLimiter(props(20, 10, 600, 120));
            limiter.acquire("1.1.1.1", RateLimitScope.STRICT);

            var stats = limiter.stats();

            assertThat(stats).containsEntry("store", "memory");
            assertThat(stats).containsEntry("activeBuckets", 1);
            assertThat(stats).containsEntry("strictRatePerMin", 20);
            assertThat(stats).containsEntry("looseRatePerMin", 600);
            assertThat(stats.keySet())
                    .as("监控字段名一旦被改动，接入的看板会静默画不出线，这里锁死键集合")
                    .containsExactlyInAnyOrderElementsOf(
                            Set.of("store", "activeBuckets", "strictRatePerMin", "looseRatePerMin"));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("配置默认值（构造器兜底）")
    class ConfigDefaults {

        /**
         * 限流开关默认必须是「开」。
         *
         * <p>这一条对应一个真实修过的缺陷：最初 {@code enabled} 声明为
         * {@code Boolean} 且没有兜底，漏配时得到 {@code null}，
         * 上层 {@code Boolean.TRUE.equals(enabled)} 判定为 false——
         * <b>限流静默关闭，且启动日志里没有任何异常</b>。
         */
        @Test
        @DisplayName("enabled 留空时默认为 true（保护默认生效，而不是默认关闭）")
        void enabledDefaultsToTrue() {
            RateLimitProperties p = new RateLimitProperties(
                    null, null, null, null, null, null, null, false, null);

            assertThat(p.enabled())
                    .as("限流开关漏配时必须默认开启——默认关闭的开关几乎不会有人发现")
                    .isTrue();
            assertThat(p.store()).isEqualTo("memory");
            assertThat(p.keyPrefix()).isEqualTo("stockanalyst");
            assertThat(p.clientIpHeader()).isEqualTo("X-Forwarded-For");
        }

        @Test
        @DisplayName("速率与容量留空时给出非零默认值（否则启动期会除以 null 崩掉）")
        void ratesDefaultToNonNull() {
            RateLimitProperties p = new RateLimitProperties(
                    null, null, null, null, null, null, null, false, null);

            assertThat(p.strictRatePerMin()).isNotNull().isPositive();
            assertThat(p.strictBurst()).isNotNull().isPositive();
            assertThat(p.looseRatePerMin()).isNotNull().isPositive();
            assertThat(p.looseBurst()).isNotNull().isPositive();
        }

        @Test
        @DisplayName("宽松桶额度必须大于严格桶（只读接口的量级本来就不同）")
        void looseBucketIsMoreGenerousThanStrict() {
            RateLimitProperties p = new RateLimitProperties(
                    null, null, null, null, null, null, null, false, null);

            assertThat(p.looseRatePerMin())
                    .as("单个详情页会并发发出十几个只读请求，宽松桶若与严格桶同量级必然误伤")
                    .isGreaterThan(p.strictRatePerMin());
            assertThat(p.looseBurst()).isGreaterThan(p.strictBurst());
        }

        @Test
        @DisplayName("trustForwardedHeader 默认关闭（否则可换头绕过限流）")
        void trustForwardedHeaderDefaultsToFalse() {
            RateLimitProperties p = new RateLimitProperties(
                    null, null, null, null, null, null, null, false, null);

            assertThat(p.trustForwardedHeader())
                    .as("X-Forwarded-For 由客户端完全可控，默认信任等于把限流开关交给攻击者")
                    .isFalse();
        }
    }
}
