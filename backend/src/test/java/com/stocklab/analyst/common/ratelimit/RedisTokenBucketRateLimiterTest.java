package com.stocklab.analyst.common.ratelimit;

import com.stocklab.analyst.config.properties.RateLimitProperties;
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
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * {@link RedisTokenBucketRateLimiter} 的 Java 侧契约测试。
 *
 * <h2>这个测试能证明什么、不能证明什么</h2>
 * <b>能证明</b>：Java 这一侧的换算与解析——速率/容量怎么从配置算出来、
 * 发给 Redis 的 ARGV 是什么、键怎么拼、脚本返回的三种形态怎么解析、
 * 返回值异常或 Redis 故障时是不是 fail-open。<b>这些都是本类自己的逻辑。</b>
 *
 * <p><b>不能证明</b>：Lua 脚本本身的算法正确性。脚本要求一个真 Redis
 * 才能执行；这里把 {@link StringRedisTemplate} 换成了替身，
 * 脚本内容根本没被求值。真脚本的验证在
 * {@code RedisTokenBucketRateLimiterContainerTest}（需要 Docker，本地缺环境时跳过）。
 *
 * <h2>为什么这层值得单独测</h2>
 * 本类所有异常路径都归结为<b>同一个动作：放行</b>。也就是说，
 * 任何一处解析写错、任何一次强转失败，运行时都不会报错，
 * 只会表现为「限流器看起来在跑，但一个请求都没拦住」。
 * 这类失效不会自己暴露，只能靠断言钉住。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Redis 令牌桶限流器（Java 侧）")
class RedisTokenBucketRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RateLimitProperties properties;

    @BeforeEach
    void setUp() {
        // 严格桶 20/分（速率 1/3 每秒）、突发 10；宽松桶 600/分（10/秒）、突发 120
        properties = new RateLimitProperties(
                Boolean.TRUE, "redis", "stockanalyst", 20, 10, 600, 120, false, "X-Forwarded-For");
    }

    private RedisTokenBucketRateLimiter limiter() {
        return new RedisTokenBucketRateLimiter(redisTemplate, properties);
    }

    private RedisTokenBucketRateLimiter limiterWith(int strictPerMin, int strictBurst) {
        properties = new RateLimitProperties(
                Boolean.TRUE, "redis", "stockanalyst", strictPerMin, strictBurst, 600, 120, false, "X-Forwarded-For");
        return limiter();
    }

    /** 让脚本返回指定结构。 */
    @SuppressWarnings("unchecked")
    private void scriptReturns(List<Object> result) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any(), any())).thenReturn(result);
    }

    /**
     * 让脚本连续两次返回不同结构。
     *
     * <p>用一次打桩配多个返回值，而不是在同一个 mock 上重复 {@code when(...)}：
     * 后者会把前一次打桩标记为未被使用，触发严格桩校验失败——
     * 那是 Mockito 在提醒你这套桩写得含糊，不是它坏了。
     */
    @SuppressWarnings("unchecked")
    private void scriptReturns(List<Object> first, List<Object> second) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any(), any())).thenReturn(first, second);
    }

    /** 让脚本调用抛异常。 */
    @SuppressWarnings("unchecked")
    private void scriptThrows(RuntimeException ex) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any(), any())).thenThrow(ex);
    }

    @SuppressWarnings("rawtypes")
    private List<Object> capturedArgs() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(redisTemplate).execute(any(RedisScript.class), anyList(),
                captor.capture(), captor.capture(), captor.capture(), captor.capture(), captor.capture());
        return captor.getAllValues();
    }

    /**
     * 按顺序取出每次调用实际使用的 Redis 键。
     *
     * <p>{@code verify} 的默认次数是「恰好 1 次」。同一个测试里调用两次限流器
     * 却按默认次数去 verify，会得到「调用次数不符」而不是你想断言的那个问题，
     * 所以次数必须显式传进来。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<String> capturedRedisKeys(int expectedCalls) {
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.times(expectedCalls))
                .execute(any(RedisScript.class), captor.capture(),
                        any(), any(), any(), any(), any());
        return captor.getAllValues().stream().map(keys -> (String) keys.get(0)).toList();
    }

    private String capturedRedisKey() {
        return capturedRedisKeys(1).get(0);
    }

    // ==================================================================
    @Nested
    @DisplayName("发给 Redis 的参数")
    class ScriptArguments {

        @Test
        @DisplayName("严格桶按「次/分 ÷ 60」换速率，容量取突发值")
        void strictBucketRateAndCapacity() {
            scriptReturns(List.of(1L, "9.0"));

            limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            List<Object> args = capturedArgs();
            // 容量在 BucketConfig 里是 double，String.valueOf 会带上 ".0"；
            // Lua 侧 tonumber("10.0") == 10，不影响判定，但断言要按真实形态写
            assertThat((String) args.get(0)).isEqualTo(String.valueOf(20 / 60.0));
            assertThat((String) args.get(1)).isEqualTo("10.0");
        }

        @Test
        @DisplayName("宽松桶用自己的速率与容量，不串到严格桶配置")
        void looseBucketRateAndCapacity() {
            scriptReturns(List.of(1L, "119.0"));

            limiter().acquire("1.2.3.4", RateLimitScope.LOOSE);

            List<Object> args = capturedArgs();
            assertThat((String) args.get(0)).isEqualTo("10.0");
            assertThat((String) args.get(1)).isEqualTo("120.0");
        }

        @Test
        @DisplayName("单次消耗固定为 1")
        void unitCost() {
            scriptReturns(List.of(1L, "9.0"));

            limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat((String) capturedArgs().get(3)).isEqualTo("1");
        }

        @Test
        @DisplayName("时间戳用毫秒，且是当前时刻附近")
        void timestampIsMilliseconds() {
            scriptReturns(List.of(1L, "9.0"));
            long before = System.currentTimeMillis();

            limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            long sent = Long.parseLong((String) capturedArgs().get(2));
            assertThat(sent).isBetween(before, System.currentTimeMillis());
        }

        /**
         * TTL 取「填满整桶所需时间」的两倍。
         *
         * <p>这个值的意义是兜底回收：即使脚本逻辑将来出问题导致键不再被
         * 正确覆盖，空闲的桶也会自己过期，不会像纯内存实现那样无限增长。
         */
        @Test
        @DisplayName("TTL 为填满整桶耗时（容量 ÷ 速率）的两倍")
        void ttlIsTwiceFillUpTime() {
            // 速率 1/秒、容量 600 -> 填满需 600 秒 -> TTL 1_200_000 毫秒
            scriptReturns(List.of(1L, "599.0"));

            limiterWith(60, 600).acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat((String) capturedArgs().get(4)).isEqualTo("1200000");
        }

        @Test
        @DisplayName("TTL 有 60 秒下界，桶很小时也不会设置过短的过期时间")
        void ttlHasSixtySecondFloor() {
            // 速率 1/3 每秒、容量 10 -> 填满需 30 秒 -> 两倍仅 60 秒，恰好触到下界
            scriptReturns(List.of(1L, "9.0"));

            limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat((String) capturedArgs().get(4)).isEqualTo("60000");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("键设计")
    class KeyDesign {

        @Test
        @DisplayName("键为「前缀:rl:档位:客户端标识」")
        void keyFormat() {
            scriptReturns(List.of(1L, "9.0"));

            limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat(capturedRedisKey()).isEqualTo("stockanalyst:rl:STRICT:1.2.3.4");
        }

        /**
         * 档位必须进键名。否则严格桶与宽松桶会共用一份令牌数，
         * 一次登录尝试就能把行情查询的额度一起吃掉。
         */
        @Test
        @DisplayName("不同档位落在不同键上")
        void scopesHaveDistinctKeys() {
            scriptReturns(List.of(1L, "9.0"));
            RedisTokenBucketRateLimiter limiter = limiter();

            limiter.acquire("1.2.3.4", RateLimitScope.STRICT);
            limiter.acquire("1.2.3.4", RateLimitScope.LOOSE);

            assertThat(capturedRedisKeys(2)).containsExactly(
                    "stockanalyst:rl:STRICT:1.2.3.4",
                    "stockanalyst:rl:LOOSE:1.2.3.4");
        }

        @Test
        @DisplayName("不同客户端落在不同键上")
        void clientsHaveDistinctKeys() {
            scriptReturns(List.of(1L, "9.0"));
            RedisTokenBucketRateLimiter limiter = limiter();

            limiter.acquire("1.1.1.1", RateLimitScope.STRICT);
            limiter.acquire("2.2.2.2", RateLimitScope.STRICT);

            assertThat(capturedRedisKeys(2)).containsExactly(
                    "stockanalyst:rl:STRICT:1.1.1.1",
                    "stockanalyst:rl:STRICT:2.2.2.2");
        }

        @Test
        @DisplayName("键前缀跟着配置走")
        void honoursConfiguredPrefix() {
            properties = new RateLimitProperties(
                    Boolean.TRUE, "redis", "my-app", 20, 10, 600, 120, false, "X-Forwarded-For");
            scriptReturns(List.of(1L, "9.0"));

            limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat(capturedRedisKey()).startsWith("my-app:rl:");
        }
    }

    // ==================================================================
    /**
     * 脚本返回值有三种可能形态，全部都要认。
     *
     * <p>见 {@code RedisTokenBucketRateLimiter#asLong} 的注释：赌错形态的
     * 代价是「每次判断都放行」，而接口层面完全看不出来。
     */
    @Nested
    @DisplayName("脚本返回值解析")
    class ResultParsing {

        @Test
        @DisplayName("标准形态：整数 + 字符串数字 -> 放行")
        void parsesCanonicalShape() {
            scriptReturns(List.of(1L, "19.5"));

            RateLimitResult result = limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat(result.allowed()).isTrue();
            assertThat(result.remainingTokens()).isEqualTo(19.5);
            assertThat(result.retryAfterSeconds()).isZero();
        }

        @Test
        @DisplayName("标准形态：拒绝")
        void parsesCanonicalRejection() {
            scriptReturns(List.of(0L, "0.0"));

            RateLimitResult result = limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfterSeconds()).isEqualTo(3);
        }

        @Test
        @DisplayName("字符串形态的放行标记也能解析")
        void parsesStringAllowedFlag() {
            scriptReturns(Arrays.asList("1", "19.5"));

            RateLimitResult result = limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat(result.allowed())
                    .as("解析不了会 fail-open，所以这里必须真的判成放行而不是靠兜底")
                    .isTrue();
            assertThat(result.remainingTokens()).isEqualTo(19.5);
        }

        @Test
        @DisplayName("字节数组形态的剩余令牌也能解析")
        void parsesByteArrayRemaining() {
            scriptReturns(List.of(1L, "19.5".getBytes(StandardCharsets.UTF_8)));

            RateLimitResult result = limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat(result.remainingTokens()).isEqualTo(19.5);
        }

        @Test
        @DisplayName("字节数组形态的放行标记也能解析")
        void parsesByteArrayAllowedFlag() {
            scriptReturns(Arrays.asList("0".getBytes(StandardCharsets.UTF_8),
                    "0.0".getBytes(StandardCharsets.UTF_8)));

            RateLimitResult result = limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat(result.allowed()).isFalse();
        }

        @Test
        @DisplayName("真正的拒绝不会被 fail-open 掩盖")
        void genuineRejectionIsNotMaskedByFailOpen() {
            scriptReturns(List.of(0L, "0.0"));

            RateLimitResult result = limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat(result.allowed())
                    .as("把拒绝误判成放行，等于限流器完全失效")
                    .isFalse();
            assertThat(result.remainingTokens()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("剩余令牌越多，建议等待越短")
        void retryAfterShrinksWithMoreTokens() {
            scriptReturns(List.of(0L, "0.0"), List.of(0L, "0.5"));
            RedisTokenBucketRateLimiter limiter = limiter();

            long waitWhenEmpty = limiter.acquire("1.2.3.4", RateLimitScope.STRICT).retryAfterSeconds();
            long waitWhenAlmostThere = limiter.acquire("1.2.3.4", RateLimitScope.STRICT).retryAfterSeconds();

            assertThat(waitWhenAlmostThere).isLessThan(waitWhenEmpty);
            assertThat(waitWhenEmpty).isEqualTo(3);
            assertThat(waitWhenAlmostThere).isEqualTo(2);
        }

        @Test
        @DisplayName("建议等待时间不小于 1 秒")
        void retryAfterNeverBelowOneSecond() {
            scriptReturns(List.of(0L, "0.99"));

            RateLimitResult result = limiter().acquire("1.2.3.4", RateLimitScope.LOOSE);

            assertThat(result.retryAfterSeconds())
                    .as("ceil 之后可能为 0，回 0 会让客户端立刻重试、把桶打得更凶")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("返回 null 时放行，且剩余令牌按满桶计")
        void nullResultFailsOpen() {
            scriptReturns(null);

            RateLimitResult result = limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat(result.allowed()).isTrue();
            assertThat(result.remainingTokens()).isEqualTo(10.0);
        }

        @Test
        @DisplayName("返回元素不足 2 个时放行")
        void shortResultFailsOpen() {
            scriptReturns(List.of(1L));

            assertThat(limiter().acquire("1.2.3.4", RateLimitScope.STRICT).allowed()).isTrue();
        }

        @Test
        @DisplayName("剩余令牌不是数字时放行而不是抛异常")
        void unparseableRemainingFailsOpen() {
            scriptReturns(List.of(1L, "not-a-number"));

            RateLimitResult result = limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat(result.allowed()).isTrue();
            assertThat(result.remainingTokens()).isEqualTo(10.0);
        }

        @Test
        @DisplayName("放行标记不是数字时放行而不是当成拒绝")
        void unparseableFlagFailsOpen() {
            scriptReturns(Arrays.asList("weird", "5.0"));

            assertThat(limiter().acquire("1.2.3.4", RateLimitScope.STRICT).allowed())
                    .as("无法判断时选择可用性，而不是把全站打成 429")
                    .isTrue();
        }
    }

    // ==================================================================
    /**
     * Redis 故障时的选择：<b>放行（fail-open）</b>。
     *
     * <p>这是有意的取舍——本项目的限流是防刷保护，不是安全边界。
     * Redis 挂掉时全站 429 的代价远大于短暂失去限流。
     * 但正因为它「静默放行」，必须有断言固定住这个行为，
     * 否则将来有人把它改成 fail-close 时没人会注意到行为变了。
     */
    @Nested
    @DisplayName("Redis 故障")
    class RedisFailure {

        @Test
        @DisplayName("连接失败时放行，不把异常抛给业务")
        void connectionFailureFailsOpen() {
            scriptThrows(new RedisConnectionFailureException("连接被拒绝"));

            RateLimitResult result = limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat(result.allowed()).isTrue();
            assertThat(result.remainingTokens()).isEqualTo(10.0);
        }

        @Test
        @DisplayName("脚本执行超时同样放行")
        void timeoutFailsOpen() {
            scriptThrows(new RuntimeException("Command timed out after 1 second(s)"));

            assertThat(limiter().acquire("1.2.3.4", RateLimitScope.STRICT).allowed()).isTrue();
        }

        @Test
        @DisplayName("故障不能变成 429：全站 429 的影响大于失去限流")
        void failureNeverProducesRejection() {
            scriptThrows(new RedisConnectionFailureException("连接被拒绝"));

            RateLimitResult result = limiter().acquire("1.2.3.4", RateLimitScope.STRICT);

            assertThat(result.allowed()).isTrue();
            assertThat(result.retryAfterSeconds()).isZero();
        }
    }
}
