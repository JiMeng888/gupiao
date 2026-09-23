package com.stocklab.analyst.modules.auth.dto;

/**
 * 令牌对。
 *
 * @param accessToken  访问令牌，随业务请求放在 {@code Authorization: Bearer} 头
 * @param refreshToken 刷新令牌，仅用于 {@code POST /api/v1/auth/refresh}
 * @param tokenType    固定为 {@code Bearer}
 * @param expiresIn    accessToken 剩余有效秒数，前端据此决定何时提前续期
 */
public record TokenPair(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {
    public static TokenPair of(String accessToken, String refreshToken, long expiresInSeconds) {
        return new TokenPair(accessToken, refreshToken, "Bearer", expiresInSeconds);
    }
}
