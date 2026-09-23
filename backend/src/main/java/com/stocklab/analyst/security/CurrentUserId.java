package com.stocklab.analyst.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入当前登录用户 ID。
 *
 * <p>用法：
 * <pre>{@code
 * @GetMapping("/items")
 * public ApiResult<List<WatchlistItemOut>> list(@CurrentUserId String userId) { ... }
 * }</pre>
 *
 * <p>{@code required = false} 用于「有登录态则个性化、无登录态也能看」的接口
 * （如首页轮播），此时参数可能是 {@code null}。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUserId {

    /** 是否必须已登录。为 true 且未携带有效令牌时抛 AUTH_REQUIRED。 */
    boolean required() default true;
}
