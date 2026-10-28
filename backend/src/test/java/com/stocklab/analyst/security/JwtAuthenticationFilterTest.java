package com.stocklab.analyst.security;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JwtAuthenticationFilter} 单测——这是全站唯一的鉴权关卡，
 * 之前完全没有测试保护，属于「失效时不报错」的典型盲区。
 *
 * <p>这里钉住的是三条真实判定路径：
 * <ul>
 *   <li>带合法令牌 → 是否建立登录态；</li>
 *   <li>已吊销会话 → 是否降级为匿名（登出即时生效的关键）；</li>
 *   <li>{@code /actuator} 与 {@code /ws/} → 是否真的豁免。</li>
 * </ul>
 *
 * <p>本过滤器<b>只识别不拦截</b>，所以每条用例都要同时断言
 * 「过滤器链是否被放行」——漏掉放行会让受保护接口之外的公开接口也一起挂掉。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter 鉴权关卡")
class JwtAuthenticationFilterTest {

    private static final String TOKEN = "access-token-abc";
    private static final String USER = "u-1";
    private static final String SID = "sid-1";
    private static final String PROTECTED_URI = "/api/v1/watchlist";

    @Mock
    private JwtTokenService tokenService;

    @Mock
    private TokenRevocationStore revocationStore;

    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(tokenService, revocationStore);
        request = new MockHttpServletRequest("GET", PROTECTED_URI);
        request.setRequestURI(PROTECTED_URI);
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    private static JwtTokenService.ParsedToken parsedToken() {
        return new JwtTokenService.ParsedToken(USER, "jti-1", SID,
                Instant.now().plusSeconds(600), TokenType.ACCESS);
    }

    private void run() throws ServletException, IOException {
        filter.doFilter(request, response, chain);
    }

    @Nested
    @DisplayName("登录态识别")
    class Identify {

        @Test
        @DisplayName("无 Authorization 头：保持匿名，请求继续放行")
        void noHeaderStaysAnonymous() throws Exception {
            run();

            assertThat(request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE)).isNull();
            assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_ATTRIBUTE)).isNull();
            assertThat(chain.getRequest()).as("公开接口必须放行").isNotNull();
            verifyNoInteractions(tokenService, revocationStore);
        }

        @Test
        @DisplayName("非 Bearer 前缀（如 Basic）：忽略，不尝试解析")
        void nonBearerSchemeIsIgnored() throws Exception {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");

            run();

            assertThat(request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE)).isNull();
            assertThat(chain.getRequest()).isNotNull();
            verifyNoInteractions(tokenService, revocationStore);
        }

        @Test
        @DisplayName("空 Bearer 令牌：忽略，不把空串送去解析")
        void blankBearerTokenIsIgnored() throws Exception {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer    ");

            run();

            verify(tokenService, never()).parse(anyString(), any());
            assertThat(chain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("合法令牌：写入 userId 与 sid，建立登录态")
        void validTokenEstablishesIdentity() throws Exception {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
            when(tokenService.parse(TOKEN, TokenType.ACCESS)).thenReturn(parsedToken());
            when(revocationStore.isSessionRevoked(SID)).thenReturn(false);

            run();

            assertThat(request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE)).isEqualTo(USER);
            assertThat(request.getAttribute(JwtAuthenticationFilter.SESSION_ID_ATTRIBUTE)).isEqualTo(SID);
            assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_ATTRIBUTE)).isNull();
            assertThat(chain.getRequest()).isNotNull();
        }
    }

    @Nested
    @DisplayName("失败降级为匿名")
    class Fallback {

        @Test
        @DisplayName("会话已吊销：不建立登录态，失败原因记为 AUTH_INVALID")
        void revokedSessionIsAnonymous() throws Exception {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
            when(tokenService.parse(TOKEN, TokenType.ACCESS)).thenReturn(parsedToken());
            when(revocationStore.isSessionRevoked(SID)).thenReturn(true);

            run();

            assertThat(request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE))
                    .as("吊销后不能再被识别为已登录，否则登出形同无效").isNull();
            assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_ATTRIBUTE))
                    .isInstanceOf(BizException.class);
            assertThat(((BizException) request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_ATTRIBUTE)).getCode())
                    .isEqualTo(ErrorCode.AUTH_INVALID.name());
            assertThat(chain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("令牌过期：保留 AUTH_EXPIRED 供前端静默刷新，不误判为非法")
        void expiredTokenKeepsErrorCode() throws Exception {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
            when(tokenService.parse(TOKEN, TokenType.ACCESS))
                    .thenThrow(new BizException(ErrorCode.AUTH_EXPIRED));

            run();

            assertThat(request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE)).isNull();
            assertThat(((BizException) request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_ATTRIBUTE)).getCode())
                    .as("这个码决定前端是静默刷新还是跳登录页，不能退化成通用失败")
                    .isEqualTo(ErrorCode.AUTH_EXPIRED.name());
            assertThat(chain.getRequest()).as("解析失败也要放行，由参数解析器决定要不要 401").isNotNull();
            verify(revocationStore, never()).isSessionRevoked(anyString());
        }

        @Test
        @DisplayName("非法令牌：降级为匿名且不中断请求")
        void invalidTokenDoesNotBreakChain() throws Exception {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
            when(tokenService.parse(TOKEN, TokenType.ACCESS))
                    .thenThrow(new BizException(ErrorCode.AUTH_INVALID));

            run();

            assertThat(request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE)).isNull();
            assertThat(chain.getRequest()).isNotNull();
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("豁免路径")
    class Exemption {

        @Test
        @DisplayName("/actuator 与 /ws/ 不做令牌解析")
        void actuatorAndWsAreExempt() throws Exception {
            for (String uri : new String[]{"/actuator/health", "/ws/quote"}) {
                MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
                req.setRequestURI(uri);
                req.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
                MockFilterChain localChain = new MockFilterChain();

                filter.doFilter(req, new MockHttpServletResponse(), localChain);

                assertThat(req.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE))
                        .as(uri + " 应豁免令牌解析").isNull();
                assertThat(localChain.getRequest()).isNotNull();
            }
            verifyNoInteractions(tokenService, revocationStore);
        }

        @Test
        @DisplayName("/api 路径不在豁免名单内")
        void apiPathIsNotExempt() throws Exception {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
            when(tokenService.parse(TOKEN, TokenType.ACCESS)).thenReturn(parsedToken());
            when(revocationStore.isSessionRevoked(SID)).thenReturn(false);

            run();

            verify(tokenService).parse(TOKEN, TokenType.ACCESS);
            assertThat(request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE)).isEqualTo(USER);
        }
    }
}
