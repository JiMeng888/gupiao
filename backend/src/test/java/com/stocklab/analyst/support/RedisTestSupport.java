package com.stocklab.analyst.support;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 需要真 Redis 的测试的公共基类。
 *
 * <h2>为什么要有这个基类</h2>
 * 「连不上就跳过」这段逻辑本身踩过两个坑，每个坑都值得只踩一次：
 * <ol>
 *   <li><b>在 {@code @BeforeAll} 里跳过会让整类静默消失。</b>
 *       容器被判定为 aborted 后，Surefire 只报
 *       {@code Tests run: 0, Skipped: 0}——跳过原因一行都不打印，
 *       统计里也看不见。看起来「加了真 Redis 测试」，其实没人知道它没跑。
 *       所以探测放在 {@code @BeforeAll}（不抛假设），
 *       <b>跳过下沉到每个用例的 {@code @BeforeEach}</b>，
 *       这样统计里会如实出现 {@code Skipped: N}。</li>
 *   <li><b>只测 TCP 可达是不够的。</b>端口能连上但服务没就绪（或不是 Redis）
 *       时，测试会以连接错误的形式失败，看起来像代码坏了。
 *       所以再补一次 {@code PING}。</li>
 * </ol>
 *
 * <h2>怎么启用</h2>
 * <pre>
 *   REDIS_HOST=127.0.0.1 REDIS_PORT=6379 mvn test
 * </pre>
 * 未设置时默认探测 {@code 127.0.0.1:6379}。CI 由
 * {@code .github/workflows/ci.yml} 的 {@code services.redis} 提供，
 * 并且有一条守卫步骤会在「该跑的 Redis 测试被跳过」时把 job 判红。
 *
 * <h2>键隔离</h2>
 * 用 {@link #namespace(String)} 生成带唯一后缀的前缀，测试之间天然隔离。
 * <b>不提供清库方法</b>：真 Redis 上可能有别人的数据，
 * {@code FLUSHDB} 绝不能出现在测试里。
 */
public abstract class RedisTestSupport {

    private static final String HOST = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

    /** 本次测试 JVM 的唯一后缀，避免与其他并发运行共享到同一批键。 */
    private static final String RUN_ID = "it" + Long.toHexString(System.nanoTime());

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static String unavailableReason;

    @BeforeAll
    public static void connectIfAvailable() {
        if (!reachable()) {
            unavailableReason = "未发现可达的 Redis（" + HOST + ":" + PORT + "），已跳过真 Redis 测试。"
                    + "设置 REDIS_HOST / REDIS_PORT 后重跑即可启用。";
            return;
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(HOST, PORT);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();

        if (!pingSucceeds(template)) {
            factory.destroy();
            unavailableReason = "Redis 端口可达但 PING 失败（" + HOST + ":" + PORT + "），已跳过。";
            return;
        }

        connectionFactory = factory;
        redisTemplate = template;
    }

    /**
     * 让每个用例各自被标记为跳过，从而在统计里可见。
     *
     * <p>这条注释是刻意留在这里的：把它挪回 {@code @BeforeAll} 是个
     * 看起来很自然的「小重构」，代价是整类测试再也无法被发现有没有跑。
     */
    @BeforeEach
    public void requireRedis() {
        Assumptions.assumeTrue(unavailableReason == null, unavailableReason);
    }

    /** 已就绪的 Redis 模板；仅在 {@link #requireRedis()} 通过后有效。 */
    protected static StringRedisTemplate redisTemplate() {
        return redisTemplate;
    }

    /** 生成一个本次运行内唯一的键前缀，用于在共享的 Redis 上隔离测试数据。 */
    protected static String namespace(String tag) {
        return RUN_ID + ":" + tag;
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean pingSucceeds(StringRedisTemplate template) {
        RedisConnection connection = null;
        try {
            connection = template.getConnectionFactory().getConnection();
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (RuntimeException ex) {
            return false;
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }
}
