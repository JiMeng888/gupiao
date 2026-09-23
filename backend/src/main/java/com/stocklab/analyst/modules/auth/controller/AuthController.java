package com.stocklab.analyst.modules.auth.controller;

import com.stocklab.analyst.common.api.ApiResult;
import com.stocklab.analyst.common.ratelimit.RateLimit;
import com.stocklab.analyst.common.ratelimit.RateLimitScope;
import com.stocklab.analyst.modules.auth.dto.ChangePasswordRequest;
import com.stocklab.analyst.modules.auth.dto.LoginRequest;
import com.stocklab.analyst.modules.auth.dto.RefreshRequest;
import com.stocklab.analyst.modules.auth.dto.RegisterRequest;
import com.stocklab.analyst.modules.auth.dto.TokenPair;
import com.stocklab.analyst.modules.auth.dto.UserProfile;
import com.stocklab.analyst.modules.auth.service.AuthService;
import com.stocklab.analyst.security.CurrentSessionId;
import com.stocklab.analyst.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 鉴权接口。
 *
 * <p>限流档位分配：所有涉及凭证校验或写操作的接口走严格桶
 * （登录 / 注册 / 刷新 / 改密），这些是被撞库与暴力破解的主要目标；
 * {@code /me} 是纯读，走宽松桶。
 *
 * <p>登出接口<b>不</b>限流：登出不应因为限流而失败，
 * 否则用户想主动结束会话时被挡住，反而扩大了令牌暴露窗口。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "鉴权", description = "注册 / 登录 / 令牌刷新 / 登出 / 改密")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "注册", description = "注册成功直接返回令牌对，免去二次登录")
    public ApiResult<TokenPair> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResult.ok(authService.register(request));
    }

    @PostMapping("/login")
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "登录")
    public ApiResult<TokenPair> login(@Valid @RequestBody LoginRequest request) {
        return ApiResult.ok(authService.login(request.phone(), request.password()));
    }

    @PostMapping("/refresh")
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "刷新令牌",
            description = "用 refreshToken 换取新的令牌对；旧 refreshToken 立即失效（令牌轮转）")
    public ApiResult<TokenPair> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResult.ok(authService.refresh(request.refreshToken()));
    }

    /**
     * 登出。
     *
     * <p>两条吊销线索都收，任一存在即可真正生效：
     * <ul>
     *   <li>{@code sessionId}——由 {@link CurrentSessionId} 从 Access Token 中取出，
     *       吊销整次登录，连带作废尚未过期的 Access Token。</li>
     *   <li>{@code request.refreshToken}——客户端主动递上来的刷新令牌，作为加固。</li>
     * </ul>
     *
     * <p><b>为什么参数都是 required = false</b>：登出必须幂等。
     * 用户的 Access Token 可能已经过期（这正是他想登出的时机之一），
     * 此时 {@code sessionId} 取不到；但只要请求体里带了 refreshToken，
     * 仍应完成吊销。反过来，若前端只发了 Authorization 头没带 body
     * （前端 axios 拦截器的常见写法），{@code sessionId} 就让登出依然有效。
     * 两者都缺时服务端无能为力，返回成功但会打 WARN 日志，
     * 不会静默假装吊销成功。
     */
    @PostMapping("/logout")
    @Operation(summary = "登出",
            description = "吊销当前会话：该会话的 access 与 refresh 令牌全部立即失效。"
                    + "请求体可省略（此时依据 Authorization 头中的会话标识吊销）。接口幂等。")
    public ApiResult<Map<String, Boolean>> logout(
            @CurrentSessionId(required = false) String sessionId,
            @RequestBody(required = false) RefreshRequest request) {
        authService.logout(request == null ? null : request.refreshToken(), sessionId);
        return ApiResult.ok(Map.of("ok", true));
    }

    @GetMapping("/me")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "当前登录用户")
    public ApiResult<UserProfile> me(@CurrentUserId String userId) {
        return ApiResult.ok(authService.currentUser(userId));
    }

    @PostMapping("/password")
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "修改密码", description = "需登录；校验原密码")
    public ApiResult<Map<String, Object>> changePassword(
            @CurrentUserId String userId,
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(userId, request.oldPassword(), request.newPassword());
        return ApiResult.ok(Map.of("ok", true, "message", "密码已修改，请使用新密码重新登录"));
    }
}
