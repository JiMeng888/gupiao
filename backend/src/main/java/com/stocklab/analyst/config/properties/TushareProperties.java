package com.stocklab.analyst.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tushare 数据源配置。
 *
 * <p>Tushare Pro 是<b>有授权协议</b>的行情数据源（区别于东方财富/腾讯的内部接口），
 * 通过个人 Token 访问 HTTP API。积分制：免费注册可获得基础积分，
 * 不同接口需要不同积分门槛（如日线行情需 120 积分、实时行情需更高）。
 * 详见 <a href="https://tushare.pro/document/1?doc_id=131">官方文档</a>。
 *
 * @param token    Tushare 个人 Token（必填，未配置则本源不可用）
 * @param baseUrl  API 地址，默认官方 {@code http://api.tushare.pro}
 * @param timeout  单次请求超时
 */
@ConfigurationProperties(prefix = "app.tushare")
public record TushareProperties(
        String token,
        String baseUrl,
        Duration timeout
) {
    public TushareProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://api.tushare.pro";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(10);
        }
    }

    /** 是否具备调用条件：配置了 Token。未配置时本源 {@code available()=false}。 */
    public boolean enabled() {
        return token != null && !token.isBlank();
    }

    /** 仅暴露给日志的脱敏 Token 摘要。 */
    public String maskedToken() {
        if (!enabled()) {
            return "(未配置)";
        }
        return token.length() <= 8 ? "****" : token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}
