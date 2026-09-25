package com.stocklab.analyst.security;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.config.properties.JwtProperties;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtTokenService} 能力边界单测。
 *
 * <p>此前它只作为 {@code AuthServiceTest} 的真实依赖被间接覆盖——
 * 登录流程通了不代表边界行为正确。这里针对<b>令牌自身的能力边界</b>：
 * 签发/解析往返、类型混用、过期、篡改、跨 issuer、sid 缺失容忍、弱密钥拒绝。
 *
 * <p>每个失败分支对应前端的一种不同动作（静默刷新 / 跳登录页），
 * 错误码映射错了会把「该重新登录」的用户留在死循环里，所以值得逐条钉住。
 */
class JwtTokenServiceTest {

    /** 32 字节，恰好满足 HS256 下限。 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "stocklab-test";

    private static JwtProperties props(Duration accessTtl, Duration refreshTtl) {
        return new JwtProperties(SECRET, ISSUER, accessTtl, refreshTtl);
    }

    private final JwtTokenService service =
            new JwtTokenService(props(Duration.ofMinutes(30), Duration.ofDays(30)));

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("签发与解析往返")
    class RoundTrip {

        @Test
        @DisplayName("access 令牌往返：主体、sid、类型、过期时间齐全")
        void accessTokenRoundTrip() {
            String token = service.createAccessToken("42", "sid-1");

            JwtTokenService.ParsedToken parsed = service.parse(token, TokenType.ACCESS);

            assertThat(parsed.userId()).isEqualTo("42");
            assertThat(parsed.sessionId()).isEqualTo("sid-1");
            assertThat(parsed.type()).isEqualTo(TokenType.ACCESS);
            assertThat(parsed.tokenId()).isNotBlank();
            // 过期时间在 [now, now+30min] 内——签发时钟与断言时钟之间的微小偏移不影响
            Instant now = Instant.now();
            assertThat(parsed.expiresAt()).isAfter(now);
            assertThat(parsed.expiresAt()).isBefore(now.plus(Duration.ofMinutes(31)));
        }

        @Test
        @DisplayName("同一次登录的 access 与 refresh 共享 sid")
        void accessAndRefreshShareSessionId() {
            String access = service.createAccessToken("42", "sid-9");
            String refresh = service.createRefreshToken("42", "sid-9");

            assertThat(service.parse(access, TokenType.ACCESS).sessionId())
                    .isEqualTo(service.parse(refresh, TokenType.REFRESH).sessionId())
                    .isEqualTo("sid-9");
        }

        @Test
        @DisplayName("sid 机制上线前的旧令牌（无 sid）被容忍解析")
        void missingSessionIdIsTolerated() {
            String token = service.createAccessToken("42", null);

            JwtTokenService.ParsedToken parsed = service.parse(token, TokenType.ACCESS);

            // null 是合法结果：调用方必须容忍（见 TokenRevocationStore#isSessionRevoked）
            assertThat(parsed.sessionId()).isNull();
            assertThat(parsed.userId()).isEqualTo("42");
        }
    }

    @Nested
    @DisplayName("校验失败的错误码映射")
    class FailureMapping {

        @Test
        @DisplayName("access 当 refresh 用（或反之）→ AUTH_INVALID")
        void typeMismatchIsInvalid() {
            String access = service.createAccessToken("42", "sid-1");

            assertThatThrownBy(() -> service.parse(access, TokenType.REFRESH))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getCode())
                    .isEqualTo(ErrorCode.AUTH_INVALID.name());
        }

        @Test
        @DisplayName("过期令牌 → AUTH_EXPIRED（前端据此走静默刷新而非跳登录）")
        void expiredTokenMapsToExpiredCode() {
            JwtTokenService expiredIssuer = new JwtTokenService(
                    props(Duration.ofSeconds(-60), Duration.ofDays(30)));
            String token = expiredIssuer.createAccessToken("42", "sid-1");

            assertThatThrownBy(() -> service.parse(token, TokenType.ACCESS))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getCode())
                    .isEqualTo(ErrorCode.AUTH_EXPIRED.name());
        }

        @Test
        @DisplayName("篡改签名的令牌 → AUTH_INVALID，不透传底层异常")
        void tamperedSignatureIsInvalid() {
            String token = service.createAccessToken("42", "sid-1");
            String tampered = token.substring(0, token.length() - 2) + "xy";

            assertThatThrownBy(() -> service.parse(tampered, TokenType.ACCESS))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getCode())
                    .isEqualTo(ErrorCode.AUTH_INVALID.name());
        }

        @Test
        @DisplayName("其它系统签发的令牌（issuer 不符）→ AUTH_INVALID")
        void foreignIssuerTokenIsRejected() {
            // 同一密钥、不同 issuer：模拟「密钥被两个系统共用」的混用场景
            JwtTokenService otherSystem = new JwtTokenService(
                    new JwtProperties(SECRET, "another-system",
                            Duration.ofMinutes(30), Duration.ofDays(30)));
            String foreignToken = otherSystem.createAccessToken("42", "sid-1");

            assertThatThrownBy(() -> service.parse(foreignToken, TokenType.ACCESS))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getCode())
                    .isEqualTo(ErrorCode.AUTH_INVALID.name());
        }

        @Test
        @DisplayName("空串与垃圾输入 → AUTH_INVALID，而不是 500")
        void garbageInputIsInvalidNot500() {
            assertThatThrownBy(() -> service.parse("", TokenType.ACCESS))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getCode())
                    .isEqualTo(ErrorCode.AUTH_INVALID.name());

            assertThatThrownBy(() -> service.parse("not.a.jwt", TokenType.ACCESS))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getCode())
                    .isEqualTo(ErrorCode.AUTH_INVALID.name());
        }
    }

    @Nested
    @DisplayName("构造期防线")
    class ConstructorGuards {

        @Test
        @DisplayName("密钥不足 32 字节在构造时即失败，不等到第一次签名")
        void weakSecretRejectedAtConstruction() {
            JwtProperties weak =
                    new JwtProperties("short-secret", ISSUER,
                            Duration.ofMinutes(30), Duration.ofDays(30));

            // JwtProperties 的 @Size 校验本应在启动阶段更早拦住；
            // 这里钉住「即使绕过配置校验直接构造，服务也不会带弱密钥运行」
            assertThatThrownBy(() -> new JwtTokenService(weak))
                    .isInstanceOf(WeakKeyException.class);
        }
    }
}
