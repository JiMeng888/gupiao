package com.stocklab.analyst.security;

/**
 * JWT 令牌类型。
 *
 * <p>Access 与 Refresh 用同一把密钥签名，靠 {@code type} 声明区分用途：
 * Refresh Token 不能用于访问业务接口，Access Token 不能用于换新令牌。
 * 缺少这层校验会导致「刷新令牌被当成访问凭证」的越权问题。
 */
public enum TokenType {

    /** 访问令牌：随业务请求携带，有效期短。 */
    ACCESS,

    /** 刷新令牌：仅用于换取新的令牌对，有效期长。 */
    REFRESH
}
