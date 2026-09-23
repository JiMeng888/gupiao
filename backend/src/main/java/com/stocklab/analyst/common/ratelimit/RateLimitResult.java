package com.stocklab.analyst.common.ratelimit;

/**
 * 令牌桶限流结果。
 *
 * @param allowed             是否放行
 * @param remainingTokens     本次判定后桶内剩余令牌（监控 / 响应头用）
 * @param retryAfterSeconds   建议重试等待秒数，放行时为 0
 */
public record RateLimitResult(boolean allowed, double remainingTokens, long retryAfterSeconds) {

    public static RateLimitResult pass(double remaining) {
        return new RateLimitResult(true, remaining, 0);
    }

    public static RateLimitResult reject(double remaining, long retryAfterSeconds) {
        return new RateLimitResult(false, remaining, Math.max(1, retryAfterSeconds));
    }
}
