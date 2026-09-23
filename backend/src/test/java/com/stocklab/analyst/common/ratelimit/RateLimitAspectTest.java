package com.stocklab.analyst.common.ratelimit;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.config.properties.RateLimitProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitAspect} 单测。
 *
 * <p><b>为什么这个类必须测：它是「限流到底生效了没有」的关隘。</b>
 * {@link RateLimiter} 本身有测试、{@code RateLimitProperties} 有默认值，
 * 但把两者接起来的那层切面此前零覆盖——而限流失效的典型形态恰恰不是
 * 限流器算错，是<b>切面没被调用</b>：
 * <ul>
 *   <li>切入点表达式写错（{@code @annotation} 写成 {@code execution}），
 *       注解贴在方法上但根本没人读它；</li>
 *   <li>放行路径忘了 {@code proceed()}，业务方法静默不执行；</li>
 *   <li>超限路径仍然 {@code proceed()}，限流器在拦，请求还是照做。</li>
 * </ul>
 * 这三种情况都不会报错、不会写 ERROR 日志，只会让限流形同虚设。
 *
 * <p>所以本类分两层验证：
 * <ol>
 *   <li><b>切入点真的匹配吗</b>——用 {@link AspectJProxyFactory} 织入真实代理，
 *       确认贴了注解的方法被拦、没贴的不被拦。这一条只有走真 AOP 才测得出来，
 *       直接调 {@code around(...)} 是测不到的。</li>
 *   <li><b>拦到之后逻辑对不对</b>——直接驱动 {@code around(...)}，
 *       逐条钉住放行 / 拦截 / 客户端标识解析 / 指标标签。</li>
 * </ol>
 */
@DisplayName("限流切面")
class RateLimitAspectTest {

    private SimpleMeterRegistry registry;
    private RateLimiter rateLimiter;
    private RateLimitAspect aspect;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        rateLimiter = mock(RateLimiter.class);
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void aspectWith(RateLimitProperties properties) {
        aspect = new RateLimitAspect(rateLimiter, properties, registry);
    }

    private static RateLimitProperties properties(boolean enabled, boolean trustForwarded, String header) {
        return new RateLimitProperties(enabled, "memory", "test",
                20, 10, 600, 120, trustForwarded, header);
    }

    private void bindRequest(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static ProceedingJoinPoint joinPointReturning(Object value) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(value);
        return joinPoint;
    }

    /** 捕获实际传给限流器的客户端标识。 */
    private String capturedClientKey() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).acquire(captor.capture(), any());
        return captor.getValue();
    }

    private Double metricCount(String name, String scope) {
        var counter = registry.find(name).tag("scope", scope).counter();
        return counter == null ? null : counter.count();
    }

    // ==================================================================
    /**
     * 走真实 AOP 织入。这一组回答的是「限流挂上去了没有」，
     * 而不是「限流逻辑写得对不对」。
     */
    @Nested
    @DisplayName("切入点织入")
    class PointcutWeaving {

        @BeforeEach
        void weave() {
            aspectWith(properties(true, false, "X-Forwarded-For"));
        }

        private GuardedTarget proxy() {
            AspectJProxyFactory factory = new AspectJProxyFactory(new GuardedTarget());
            factory.addAspect(aspect);
            return factory.getProxy();
        }

        @Test
        @DisplayName("贴了 @RateLimit 的方法会被限流器拦一道")
        void annotatedMethodIsGuarded() {
            when(rateLimiter.acquire(anyString(), any())).thenReturn(RateLimitResult.pass(9));

            String result = proxy().guarded();

            assertThat(result).isEqualTo("业务返回值");
            verify(rateLimiter).acquire(anyString(), any());
        }

        @Test
        @DisplayName("没贴注解的方法完全不被拦")
        void unannotatedMethodIsNotGuarded() {
            String result = proxy().unguarded();

            assertThat(result).isEqualTo("业务返回值");
            verifyNoInteractions(rateLimiter);
        }

        @Test
        @DisplayName("注解上的档位被原样传给限流器")
        void annotationScopeReachesLimiter() {
            when(rateLimiter.acquire(anyString(), any())).thenReturn(RateLimitResult.pass(9));

            proxy().guarded();

            ArgumentCaptor<RateLimitScope> captor = ArgumentCaptor.forClass(RateLimitScope.class);
            verify(rateLimiter).acquire(anyString(), captor.capture());
            assertThat(captor.getValue())
                    .as("档位传错会让写操作走宽松桶，等于没限流")
                    .isEqualTo(RateLimitScope.STRICT);
        }

        @Test
        @DisplayName("超限时业务方法一点都不执行")
        void blockedCallNeverReachesBusinessMethod() {
            when(rateLimiter.acquire(anyString(), any()))
                    .thenReturn(RateLimitResult.reject(0.0, 3));
            GuardedTarget target = proxy();

            BizException ex = catchThrowableOfType(target::guarded, BizException.class);

            assertThat(ex.getCode()).isEqualTo(ErrorCode.RATE_LIMITED.name());
            assertThat(target.invocationCount())
                    .as("限流器说拒绝却仍然执行了业务方法，限流就是假的")
                    .isZero();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("放行与拦截")
    class PassAndBlock {

        @Test
        @DisplayName("放行时返回业务结果并记录放行指标")
        void passProceedsAndCounts() throws Throwable {
            aspectWith(properties(true, false, "X-Forwarded-For"));
            when(rateLimiter.acquire(anyString(), any())).thenReturn(RateLimitResult.pass(9));
            ProceedingJoinPoint joinPoint = joinPointReturning("PAYLOAD");

            Object result = aspect.around(joinPoint, strict());

            assertThat(result).isEqualTo("PAYLOAD");
            assertThat(metricCount("app.rate_limit.passed", "strict")).isEqualTo(1.0);
            assertThat(metricCount("app.rate_limit.blocked", "strict")).isNull();
        }

        @Test
        @DisplayName("超限时抛 429 且不执行业务方法")
        void blockThrows429AndSkipsProceed() throws Throwable {
            aspectWith(properties(true, false, "X-Forwarded-For"));
            when(rateLimiter.acquire(anyString(), any()))
                    .thenReturn(RateLimitResult.reject(0.0, 5));
            ProceedingJoinPoint joinPoint = joinPointReturning("PAYLOAD");

            BizException ex = catchThrowableOfType(
                    () -> aspect.around(joinPoint, strict()), BizException.class);

            assertThat(ex.getCode()).isEqualTo(ErrorCode.RATE_LIMITED.name());
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            verify(joinPoint, never()).proceed();
        }

        @Test
        @DisplayName("拦截时记录拦截指标，scope 标签为小写")
        void blockCountsWithLowercaseScope() throws Throwable {
            aspectWith(properties(true, false, "X-Forwarded-For"));
            when(rateLimiter.acquire(anyString(), any()))
                    .thenReturn(RateLimitResult.reject(0.0, 5));
            ProceedingJoinPoint joinPoint = joinPointReturning("PAYLOAD");

            catchThrowableOfType(() -> aspect.around(joinPoint, strict()), BizException.class);

            assertThat(metricCount("app.rate_limit.blocked", "strict")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("宽松档位记录到 loose 标签下，不与严格档位混在一起")
        void scopeTagsAreSeparate() throws Throwable {
            aspectWith(properties(true, false, "X-Forwarded-For"));
            when(rateLimiter.acquire(anyString(), any())).thenReturn(RateLimitResult.pass(9));
            ProceedingJoinPoint joinPoint = joinPointReturning("PAYLOAD");

            aspect.around(joinPoint, loose());

            assertThat(metricCount("app.rate_limit.passed", "loose")).isEqualTo(1.0);
            assertThat(metricCount("app.rate_limit.passed", "strict")).isNull();
        }

        /**
         * 限流关闭时应当<b>完全不碰</b>限流器，而不是「调了但总放行」。
         *
         * <p>区别在于：前者不产生指标、不写降级日志、不占 Redis 往返；
         * 后者会让压测/自动化环境里出现大量无意义的限流调用。
         */
        @Test
        @DisplayName("限流关闭时直接放行，不调用限流器")
        void disabledSkipsLimiterEntirely() throws Throwable {
            aspectWith(properties(false, false, "X-Forwarded-For"));
            ProceedingJoinPoint joinPoint = joinPointReturning("PAYLOAD");

            Object result = aspect.around(joinPoint, strict());

            assertThat(result).isEqualTo("PAYLOAD");
            verifyNoInteractions(rateLimiter);
            assertThat(metricCount("app.rate_limit.passed", "strict")).isNull();
        }

        /**
         * 业务方法自己抛异常时必须原样上抛，不能被限流逻辑改写或吞掉。
         */
        @Test
        @DisplayName("业务方法抛出的异常原样透传")
        void businessExceptionPropagates() throws Throwable {
            aspectWith(properties(true, false, "X-Forwarded-For"));
            when(rateLimiter.acquire(anyString(), any())).thenReturn(RateLimitResult.pass(9));
            ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
            when(joinPoint.proceed()).thenThrow(new IllegalStateException("业务炸了"));

            IllegalStateException ex = catchThrowableOfType(
                    () -> aspect.around(joinPoint, strict()), IllegalStateException.class);

            assertThat(ex).isNotNull().hasMessage("业务炸了");
        }
    }

    // ==================================================================
    /**
     * 客户端标识决定「谁和谁共用一个桶」。
     *
     * <p>这一段出错的后果是两极的：取错成固定值会让<b>所有用户互相挤兑</b>
     * （一个人刷就把全站刷成 429），取成可伪造的请求头会让<b>限流直接失效</b>。
     */
    @Nested
    @DisplayName("客户端标识解析")
    class ClientKeyResolution {

        private static final String HEADER = "X-Forwarded-For";

        @Test
        @DisplayName("无请求上下文时用固定标识 unknown")
        void fallsBackToUnknownWithoutRequest() throws Throwable {
            aspectWith(properties(true, false, HEADER));
            when(rateLimiter.acquire(anyString(), any())).thenReturn(RateLimitResult.pass(9));

            aspect.around(joinPointReturning("x"), strict());

            assertThat(capturedClientKey())
                    .as("取不到来源时共享一个桶，比给一个「无限额」的独立桶安全")
                    .isEqualTo("unknown");
        }

        @Test
        @DisplayName("不信任转发头时用 remoteAddr")
        void usesRemoteAddrWhenForwardedHeaderUntrusted() throws Throwable {
            aspectWith(properties(true, false, HEADER));
            when(rateLimiter.acquire(anyString(), any())).thenReturn(RateLimitResult.pass(9));
            bindRequest("10.1.2.3", "1.2.3.4");

            aspect.around(joinPointReturning("x"), strict());

            assertThat(capturedClientKey())
                    .as("未部署在受信反代之后时信任该头，等于把限流绕过方法公开")
                    .isEqualTo("10.1.2.3");
        }

        @Test
        @DisplayName("信任转发头时取第一个地址")
        void usesFirstForwardedAddress() throws Throwable {
            aspectWith(properties(true, true, HEADER));
            when(rateLimiter.acquire(anyString(), any())).thenReturn(RateLimitResult.pass(9));
            bindRequest("10.0.0.1", "203.0.113.9, 10.0.0.7, 10.0.0.8");

            aspect.around(joinPointReturning("x"), strict());

            assertThat(capturedClientKey())
                    .as("多级代理时第一个才是最初的客户端，取最后一个等于按代理分组")
                    .isEqualTo("203.0.113.9");
        }

        @Test
        @DisplayName("转发头首尾空白被去掉")
        void trimsForwardedAddress() throws Throwable {
            aspectWith(properties(true, true, HEADER));
            when(rateLimiter.acquire(anyString(), any())).thenReturn(RateLimitResult.pass(9));
            bindRequest("10.0.0.1", "  203.0.113.9  , 10.0.0.7");

            aspect.around(joinPointReturning("x"), strict());

            assertThat(capturedClientKey()).isEqualTo("203.0.113.9");
        }

        @Test
        @DisplayName("信任转发头但头为空时回落到 remoteAddr")
        void fallsBackWhenForwardedHeaderBlank() throws Throwable {
            aspectWith(properties(true, true, HEADER));
            when(rateLimiter.acquire(anyString(), any())).thenReturn(RateLimitResult.pass(9));
            bindRequest("10.1.2.3", "   ");

            aspect.around(joinPointReturning("x"), strict());

            assertThat(capturedClientKey())
                    .as("空白头当有效值会让所有此类请求共享空串这个桶")
                    .isEqualTo("10.1.2.3");
        }

        @Test
        @DisplayName("信任转发头但头不存在时回落到 remoteAddr")
        void fallsBackWhenForwardedHeaderMissing() throws Throwable {
            aspectWith(properties(true, true, HEADER));
            when(rateLimiter.acquire(anyString(), any())).thenReturn(RateLimitResult.pass(9));
            bindRequest("10.1.2.3", null);

            aspect.around(joinPointReturning("x"), strict());

            assertThat(capturedClientKey()).isEqualTo("10.1.2.3");
        }

        @Test
        @DisplayName("请求头名可在配置里改名")
        void honoursConfiguredHeaderName() throws Throwable {
            aspectWith(properties(true, true, "CF-Connecting-IP"));
            when(rateLimiter.acquire(anyString(), any())).thenReturn(RateLimitResult.pass(9));
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.1");
            request.addHeader("CF-Connecting-IP", "198.51.100.7");
            request.addHeader("X-Forwarded-For", "203.0.113.9");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            aspect.around(joinPointReturning("x"), strict());

            assertThat(capturedClientKey())
                    .as("配置了却不生效，会让「部署在 Cloudflare 后面」这类环境取错维度")
                    .isEqualTo("198.51.100.7");
        }

        @Test
        @DisplayName("同一个客户端多次调用命中同一个桶键")
        void sameClientStable() throws Throwable {
            aspectWith(properties(true, false, HEADER));
            when(rateLimiter.acquire(anyString(), any())).thenReturn(RateLimitResult.pass(9));
            bindRequest("10.1.2.3", null);

            aspect.around(joinPointReturning("x"), strict());
            aspect.around(joinPointReturning("x"), strict());

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(rateLimiter, org.mockito.Mockito.times(2)).acquire(captor.capture(), any());
            assertThat(captor.getAllValues()).containsOnly("10.1.2.3");
        }
    }

    // ==================================================================
    // 辅助

    private static RateLimit strict() {
        return annotation(RateLimitScope.STRICT);
    }

    private static RateLimit loose() {
        return annotation(RateLimitScope.LOOSE);
    }

    /**
     * 造一个 {@link RateLimit} 注解实例。
     *
     * <p>用动态代理而不是真实方法上的注解实例：{@code around} 只依赖
     * {@code value()}，直接构造可以让「档位」成为显式入参，
     * 不必为了测 LOOSE 再写一个业务类。
     */
    private static RateLimit annotation(RateLimitScope scope) {
        return (RateLimit) java.lang.reflect.Proxy.newProxyInstance(
                RateLimit.class.getClassLoader(),
                new Class<?>[]{RateLimit.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "value" -> scope;
                    case "annotationType" -> RateLimit.class;
                    case "toString" -> "@RateLimit(" + scope + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    /** 供 AOP 织入测试使用的业务类；{@code guarded} 贴注解，{@code unguarded} 不贴。 */
    public static class GuardedTarget {

        private int invocationCount;

        @RateLimit(RateLimitScope.STRICT)
        public String guarded() {
            invocationCount++;
            return "业务返回值";
        }

        public String unguarded() {
            invocationCount++;
            return "业务返回值";
        }

        public int invocationCount() {
            return invocationCount;
        }
    }
}
