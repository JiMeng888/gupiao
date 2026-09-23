package com.stocklab.analyst.security;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.config.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * JWT 签发与校验。
 *
 * <p>双令牌设计：
 * <ul>
 *   <li>Access Token 有效期短（默认 30 分钟），泄漏窗口小</li>
 *   <li>Refresh Token 有效期长（默认 30 天），用于无感续期，减少重复登录</li>
 * </ul>
 *
 * <p>每个令牌都带两个关键声明：
 * <ul>
 *   <li>{@code jti}（令牌唯一 ID）——用于把「已使用过的刷新令牌」逐个拉黑，
 *       支撑刷新令牌轮转。</li>
 *   <li>{@code sid}（会话 ID）——<b>同一次登录签发的 access 与 refresh 共享同一个 sid</b>，
 *       刷新时轮转出的新令牌对也沿用该 sid。登出只需要拉黑 sid，
 *       就能让这次登录产生的所有令牌（含尚未过期的 Access Token）同时失效。</li>
 * </ul>
 *
 * <p>没有 {@code sid} 会怎样？服务端只拿到一个 Access Token 时，
 * 无法反推「它属于哪一次登录、配套的 Refresh Token 是哪一个」，
 * 于是登出只能靠客户端主动把 Refresh Token 递上来——
 * 客户端一旦漏传，服务端就会返回成功但什么都没吊销（假登出）。
 * sid 把「会话」变成服务端自己可以裁决的对象，登出因此不再依赖客户端配合。
 *
 * <p>校验失败按原因映射为不同错误码（{@code AUTH_EXPIRED} / {@code AUTH_INVALID}），
 * 前端据此决定「静默刷新」还是「跳转登录页」。
 */
@Service
public class JwtTokenService {

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_SESSION = "sid";

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtTokenService(JwtProperties properties) {
        this.properties = properties;
        // HS256 密钥材料：长度不足 32 字节时 Keys.hmacShaKeyFor 会直接抛错，
        // 而 JwtProperties 上的 @Size 校验会在更早的启动阶段就拦住
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析结果。
     *
     * @param sessionId 会话 ID；<b>可能为 null</b>——sid 机制上线前签发的旧令牌不含该声明。
     *                  调用方必须容忍 null，见 {@link TokenRevocationStore#isSessionRevoked}。
     * @param expiresAt 令牌自身的过期时间。吊销会话时用它决定黑名单条目存活多久，
     *                  比用配置里的 TTL 更精确：一个已存活 29 天的刷新令牌
     *                  只需要再拉黑 1 天，而不是全新的一整个周期。
     */
    public record ParsedToken(String userId, String tokenId, String sessionId,
                              Instant expiresAt, TokenType type) {
    }

    /** 生成一个新的会话 ID。登录 / 注册时调用一次，此后该会话内所有令牌共享它。 */
    public String newSessionId() {
        return UUID.randomUUID().toString();
    }

    public String createAccessToken(String userId, String sessionId) {
        return create(userId, TokenType.ACCESS, properties.accessTokenTtl(), sessionId);
    }

    public String createRefreshToken(String userId, String sessionId) {
        return create(userId, TokenType.REFRESH, properties.refreshTokenTtl(), sessionId);
    }

    /**
     * 校验并解析令牌。
     *
     * @throws BizException 过期抛 AUTH_EXPIRED，非法 / 类型不符抛 AUTH_INVALID
     */
    public ParsedToken parse(String token, TokenType expected) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String actualType = claims.get(CLAIM_TYPE, String.class);
            if (actualType == null || !expected.name().equalsIgnoreCase(actualType)) {
                throw new BizException(ErrorCode.AUTH_INVALID, "令牌类型错误");
            }
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new BizException(ErrorCode.AUTH_INVALID, "令牌缺少主体信息");
            }
            // sid 允许缺失：不带 sid 的旧令牌按「无会话」处理，
            // 无法被登出吊销，只能等它自然过期。见类注释。
            Date expiration = claims.getExpiration();
            return new ParsedToken(subject, claims.getId(), claims.get(CLAIM_SESSION, String.class),
                    expiration == null ? null : expiration.toInstant(), expected);
        } catch (ExpiredJwtException ex) {
            // 必须先于 JwtException 捕获：它继承自 JwtException
            throw new BizException(ErrorCode.AUTH_EXPIRED);
        } catch (JwtException | IllegalArgumentException ex) {
            // 签名不匹配、结构损坏、issuer 不符、空串等统一归为「无效凭证」，
            // 不透传底层异常信息，避免泄漏实现细节
            throw new BizException(ErrorCode.AUTH_INVALID);
        }
    }

    public long accessTokenTtlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }

    /** 刷新令牌的剩余有效时长，用于设置黑名单条目的存活时间。 */
    public Duration refreshTokenTtl() {
        return properties.refreshTokenTtl();
    }

    private String create(String userId, TokenType type, Duration ttl, String sessionId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId)
                .issuer(properties.issuer())
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TYPE, type.name().toLowerCase(Locale.ROOT))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)));
        if (sessionId != null && !sessionId.isBlank()) {
            builder.claim(CLAIM_SESSION, sessionId);
        }
        return builder.signWith(signingKey).compact();
    }
}
