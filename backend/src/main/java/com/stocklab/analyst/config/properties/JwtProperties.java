package com.stocklab.analyst.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * JWT 配置。
 *
 * <p>密钥仅从环境变量注入（{@code APP_JWT_SECRET}），配置文件里只放占位符，
 * 避免真实密钥进入版本库。HS256 要求密钥长度不少于 256 bit（32 字节）。
 *
 * @param secret          HMAC-SHA256 签名密钥，长度 ≥ 32 字符
 * @param issuer          签发者标识，解析时校验，防止跨系统令牌混用
 * @param accessTokenTtl  Access Token 有效期
 * @param refreshTokenTtl Refresh Token 有效期
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "JWT 密钥长度必须不少于 32 个字符（HS256 需要 256 bit）")
        String secret,

        @NotBlank
        String issuer,

        @NotNull
        Duration accessTokenTtl,

        @NotNull
        Duration refreshTokenTtl
) {
}
