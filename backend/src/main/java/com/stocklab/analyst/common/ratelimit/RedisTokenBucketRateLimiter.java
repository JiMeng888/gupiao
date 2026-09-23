package com.stocklab.analyst.common.ratelimit;

import com.stocklab.analyst.config.properties.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 基于 Redis + Lua 的分布式令牌桶限流器。
 *
 * <p>为什么必须用 Lua：令牌桶的「读取 → 补充 → 判断 → 回写」是复合操作。
 * 若在 Java 侧分多条命令执行，两个并发请求可能读到同一份令牌数、
 * 双双判定通过，超额放行。Lua 脚本在 Redis 中单线程原子执行，
 * 从根上消除这个竞态。
 *
 * <p>键设计：{@code {prefix}:rl:{scope}:{clientKey}}，
 * 桶状态用 Hash 存 {@code tokens}/{@code ts} 两个字段，
 * 并设置 PEXPIRE 兜底——即使清理逻辑失效，键也会自然过期，
 * 不会像纯内存实现那样无限增长。
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    /**
     * 令牌桶 Lua 脚本。
     *
     * <p>KEYS[1] 桶键；
     * ARGV[1] 速率（令牌/秒）、ARGV[2] 容量、ARGV[3] 当前时间（毫秒）、
     * ARGV[4] 单次消耗、ARGV[5] 键过期时间（毫秒）。
     *
     * <p>返回 {是否放行(0/1), 剩余令牌字符串}。剩余令牌用字符串返回，
     * 避免 Redis 把浮点数截断成整数。
     */
    private static final String TOKEN_BUCKET_SCRIPT = """
            local key = KEYS[1]
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local cost = tonumber(ARGV[4])
            local ttl = tonumber(ARGV[5])

            local state = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil or ts == nil then
                tokens = capacity
                ts = now
            end

            local elapsed = (now - ts) / 1000.0
            if elapsed < 0 then
                elapsed = 0
            end
            tokens = math.min(capacity, tokens + elapsed * rate)

            local allowed = 0
            if tokens >= cost then
                tokens = tokens - cost
                allowed = 1
            end

            redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', key, ttl)
            return {allowed, tostring(tokens)}
            """;

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final DefaultRedisScript<List> script;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(TOKEN_BUCKET_SCRIPT);
        redisScript.setResultType(List.class);
        this.script = redisScript;
    }

    @Override
    @SuppressWarnings("unchecked")
    public RateLimitResult acquire(String key, RateLimitScope scope) {
        BucketConfig config = configOf(scope);
        String redisKey = properties.keyPrefix() + ":rl:" + scope.name() + ':' + key;
        long nowMillis = System.currentTimeMillis();
        // 键过期时间取「填满整桶所需时间」的两倍，保证空闲键能自然回收
        long ttlMillis = Math.max(60_000L, (long) (config.capacity() / config.ratePerSecond() * 1000 * 2));

        try {
            List<Object> result = redisTemplate.execute(
                    script,
                    List.of(redisKey),
                    String.valueOf(config.ratePerSecond()),
                    String.valueOf(config.capacity()),
                    String.valueOf(nowMillis),
                    "1",
                    String.valueOf(ttlMillis));

            if (result == null || result.size() < 2) {
                // 脚本返回异常结构时按放行处理：限流是保护措施，
                // 不应因为返回结构解析失败而让正常业务不可用
                log.warn("限流脚本返回结构异常，放行本次请求 key={}", redisKey);
                return RateLimitResult.pass(config.capacity());
            }

            Long allowedFlag = asLong(result.get(0));
            Double remainingTokens = asDouble(result.get(1));
            if (allowedFlag == null || remainingTokens == null) {
                log.warn("限流脚本返回值无法解析，放行本次请求 key={} raw={}", redisKey, result);
                return RateLimitResult.pass(config.capacity());
            }

            boolean allowed = allowedFlag == 1L;
            double remaining = remainingTokens;
            if (allowed) {
                return RateLimitResult.pass(remaining);
            }
            long retryAfter = (long) Math.ceil((1.0 - remaining) / config.ratePerSecond());
            return RateLimitResult.reject(remaining, retryAfter);
        } catch (RuntimeException ex) {
            // 限流组件故障时选择「放行」（fail-open）而不是「拒绝」（fail-close）：
            // 本项目的限流是防刷保护，不是安全边界；Redis 故障时全站 429 的
            // 影响远大于短暂失去限流。若用于安全敏感场景应改为 fail-close。
            log.error("Redis 限流器不可用，本次请求放行 key={}", redisKey, ex);
            return RateLimitResult.pass(config.capacity());
        }
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

    /**
     * 把脚本返回的「是否放行」转成 {@code Long}。
     *
     * <p><b>为什么接受三种形态而不是直接强转。</b>Lua 的数值在 RESP 里是整数回复，
     * 多数情况下客户端反序列化成 {@code Long}，但实际类型取决于客户端实现、
     * RESP 协议版本（RESP2 / RESP3），以及 Spring 是否对结果数组再做一次
     * 逐元素反序列化。这几种组合下同一个脚本可能给出 {@code Long}、
     * {@code byte[]} 或 {@code String}。
     *
     * <p>赌错的代价不对称：强转失败抛出的 {@code ClassCastException}
     * 会被下面那个 {@code catch (RuntimeException)} 接住，
     * 结果是<b>每一次限流判断都放行，而接口全部正常返回 200</b>——
     * 表现为「限流器在跑、日志里有 ERROR、但一个请求都没拦住」。
     * 而多认两种形态的代价只是几行代码。
     */
    private static Long asLong(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof byte[] bytes) {
            return parseLong(new String(bytes, StandardCharsets.UTF_8));
        }
        return raw == null ? null : parseLong(String.valueOf(raw));
    }

    /** 把脚本返回的「剩余令牌」转成 {@code Double}；形态说明见 {@link #asLong}。 */
    private static Double asDouble(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof byte[] bytes) {
            return parseDouble(new String(bytes, StandardCharsets.UTF_8));
        }
        return raw == null ? null : parseDouble(String.valueOf(raw));
    }

    private static Long parseLong(String text) {
        try {
            return (long) Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double parseDouble(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private record BucketConfig(double ratePerSecond, double capacity) {
    }
}
