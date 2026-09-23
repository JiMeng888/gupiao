package com.stocklab.analyst.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式限流。
 *
 * <pre>{@code
 * @RateLimit(RateLimitScope.STRICT)
 * @PostMapping("/login")
 * public ApiResult<TokenPair> login(@Valid @RequestBody LoginRequest req) { ... }
 * }</pre>
 *
 * <p>限额由 {@code app.rate-limit.*} 配置决定，注解只负责选择档位，
 * 避免限额散落在代码里导致调参需要改代码。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    RateLimitScope value() default RateLimitScope.LOOSE;
}
