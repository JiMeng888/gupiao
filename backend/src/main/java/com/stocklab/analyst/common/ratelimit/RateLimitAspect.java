package com.stocklab.analyst.common.ratelimit;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.config.properties.RateLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link RateLimit} 注解的切面实现。
 *
 * <p>用 AOP 而不是拦截器（Interceptor）的原因：拦截器只能按 URL 模式匹配，
 * 而限流档位的语义是「这个方法做什么」，注解直接贴在方法上更贴近意图，
 * 重构改路径时不会漏改配置。
 *
 * <p>超限时抛 {@link ErrorCode#RATE_LIMITED}（HTTP 429），
 * 由全局异常处理器统一输出，与其它错误保持同一套响应结构。
 */
@Aspect
@Component
public class RateLimitAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;

    public RateLimitAspect(RateLimiter rateLimiter,
                           RateLimitProperties properties,
                           MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        if (!properties.enabled()) {
            return joinPoint.proceed();
        }

        String clientKey = resolveClientKey();
        RateLimitResult result = rateLimiter.acquire(clientKey, rateLimit.value());

        if (!result.allowed()) {
            meterRegistry.counter("app.rate_limit.blocked",
                    "scope", rateLimit.value().name().toLowerCase()).increment();
            log.warn("限流拦截 client={} scope={} retryAfter={}s",
                    clientKey, rateLimit.value(), result.retryAfterSeconds());
            throw new BizException(ErrorCode.RATE_LIMITED);
        }

        meterRegistry.counter("app.rate_limit.passed",
                "scope", rateLimit.value().name().toLowerCase()).increment();
        return joinPoint.proceed();
    }

    /**
     * 解析限流维度标识（客户端 IP 或代理转发的真实 IP）。
     *
     * <p>取不到请求上下文时返回固定标识 {@code unknown}——这意味着所有此类
     * 请求共享同一个桶。发生在异步线程中调用被限流方法是异常情况，
     * 共享一个桶比「无限额」更安全。
     */
    private String resolveClientKey() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return "unknown";
        }
        if (properties.trustForwardedHeader()) {
            String forwarded = request.getHeader(properties.clientIpHeader());
            if (forwarded != null && !forwarded.isBlank()) {
                // 只取第一个地址：经过多级代理时为最初的客户端
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr;
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
