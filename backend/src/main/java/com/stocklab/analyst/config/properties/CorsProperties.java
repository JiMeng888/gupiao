package com.stocklab.analyst.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 跨域配置。
 *
 * @param allowedOriginPatterns 允许的来源，支持通配符（如 {@code http://localhost:*}）
 * @param allowCredentials      是否允许携带 Cookie / Authorization
 * @param maxAgeSeconds         预检请求缓存时长
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
        List<String> allowedOriginPatterns,
        boolean allowCredentials,
        Long maxAgeSeconds
) {
    public CorsProperties {
        if (allowedOriginPatterns == null || allowedOriginPatterns.isEmpty()) {
            allowedOriginPatterns = List.of("http://localhost:*", "http://127.0.0.1:*");
        }
        if (maxAgeSeconds == null) {
            maxAgeSeconds = 3600L;
        }
    }
}
