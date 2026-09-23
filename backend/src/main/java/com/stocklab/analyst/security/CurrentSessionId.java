package com.stocklab.analyst.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入当前令牌所属的会话 ID（令牌中的 {@code sid} 声明）。
 *
 * <p>用途是登出：服务端从 Access Token 自身读出会话 ID，
 * 就可以吊销整次登录，不必依赖客户端把 Refresh Token 一起递上来。
 *
 * <p>为什么不复用 {@link CurrentUserId}？两者语义与失败语义都不同：
 * 用户 ID 表达「你是谁」，缺失即未登录；会话 ID 表达「哪一次登录」，
 * 且允许缺失（sid 机制上线前签发的旧令牌不含该声明）。
 * 混在一起会让「userId 非空但 sessionId 为空」这种合法状态难以表达。
 *
 * <p>用法：
 * <pre>{@code
 * @PostMapping("/logout")
 * public ApiResult<?> logout(@CurrentSessionId(required = false) String sessionId) { ... }
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentSessionId {

    /**
     * 是否必须存在会话。
     *
     * <p>默认 {@code false}：登出等接口即便拿不到会话 ID 也应当继续执行
     * （幂等语义），由业务侧决定降级行为。
     */
    boolean required() default false;
}
