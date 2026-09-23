package com.stocklab.analyst.security;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 从 {@code Authorization: Bearer <token>} 中解析登录态，写入请求属性。
 *
 * <p>设计取舍：本过滤器<b>只做识别，不做拦截</b>。
 * 令牌缺失或非法时不中断请求，只保持「匿名」状态；是否需要登录
 * 由 {@link CurrentUserId} 在具体接口上声明。
 * 这样公开接口与受保护接口可以在同一个过滤器下共存，
 * 不会出现「忘记放行某个路径」的整站 401。
 *
 * <p><b>登出即时生效</b>：解析成功后除了写 userId，还会检查令牌携带的
 * {@code sid} 是否已被吊销（见 {@link TokenRevocationStore#isSessionRevoked}）。
 * 已吊销的会话不再被视为已登录，且失败原因记为「令牌已注销」，
 * 使一张未到期的 Access Token 在用户登出后立即失效，
 * 而不必等它自然过期。
 *
 * <p>失败原因通过请求属性透传给 {@link CurrentUserIdArgumentResolver}，
 * 由它翻译成精确的错误码（{@code AUTH_EXPIRED} / {@code AUTH_INVALID}）。
 * 前端据此分流：{@code AUTH_EXPIRED} → 静默刷新；{@code AUTH_INVALID} → 跳登录页。
 * 早期版本把所有失败都归为 {@code AUTH_REQUIRED}，前端无法区分
 * 「该刷新」和「该重新登录」，只能一律跳登录页。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 请求属性键：当前登录用户 ID。 */
    public static final String USER_ID_ATTRIBUTE = "app.currentUserId";

    /** 请求属性键：当前令牌所属会话 ID（可能为 null，见 JwtTokenService）。 */
    public static final String SESSION_ID_ATTRIBUTE = "app.currentSessionId";

    /**
     * 请求属性键：令牌解析失败原因（{@link BizException}）。
     *
     * <p>只在本过滤器内部与参数解析器之间传递，不写入响应。
     */
    public static final String AUTH_FAILURE_ATTRIBUTE = "app.authFailure";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenService tokenService;
    private final TokenRevocationStore revocationStore;

    public JwtAuthenticationFilter(JwtTokenService tokenService,
                                   TokenRevocationStore revocationStore) {
        this.tokenService = tokenService;
        this.revocationStore = revocationStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                authenticate(request, token);
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        try {
            JwtTokenService.ParsedToken parsed = tokenService.parse(token, TokenType.ACCESS);

            // 会话已被吊销（用户登出）：不建立登录态。
            // 这一步让登出能立刻废掉尚未过期的 Access Token。
            if (revocationStore.isSessionRevoked(parsed.sessionId())) {
                request.setAttribute(AUTH_FAILURE_ATTRIBUTE,
                        new BizException(ErrorCode.AUTH_INVALID, "登录状态已失效，请重新登录"));
                log.debug("会话已注销，按匿名处理 uri={} sid={}", request.getRequestURI(), parsed.sessionId());
                return;
            }

            request.setAttribute(USER_ID_ATTRIBUTE, parsed.userId());
            request.setAttribute(SESSION_ID_ATTRIBUTE, parsed.sessionId());
        } catch (BizException ex) {
            // 静默降级为匿名，但保留具体原因供参数解析器使用；
            // DEBUG 级别避免被恶意令牌刷日志
            request.setAttribute(AUTH_FAILURE_ATTRIBUTE, ex);
            if (log.isDebugEnabled()) {
                log.debug("令牌解析失败，按匿名处理 uri={} code={}", request.getRequestURI(), ex.getCode());
            }
        }
    }

    /** 静态资源与监控端点不需要解析令牌。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator") || uri.startsWith("/ws/");
    }
}
