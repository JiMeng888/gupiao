package com.stocklab.analyst.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流配置。
 *
 * @param enabled              是否启用限流（留空默认启用）
 * @param store                令牌桶存储：{@code memory}（单机）/ {@code redis}（多实例共享）
 * @param keyPrefix            Redis 键前缀
 * @param strictRatePerMin     严格桶速率（次/分钟）：登录注册、写操作、AI 分析（留空默认 20）
 * @param strictBurst          严格桶突发容量（留空默认 10）
 * @param looseRatePerMin      宽松桶速率（次/分钟）：行情、K 线、资讯等只读（留空默认 600）
 * @param looseBurst           宽松桶突发容量（留空默认 120）
 * @param trustForwardedHeader 是否信任 {@code X-Forwarded-For} 作为客户端标识
 * @param clientIpHeader       取客户端 IP 的请求头名
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        Boolean enabled,
        String store,
        String keyPrefix,
        Integer strictRatePerMin,
        Integer strictBurst,
        Integer looseRatePerMin,
        Integer looseBurst,
        boolean trustForwardedHeader,
        String clientIpHeader
) {
    /**
     * <b>安全提示</b>：{@code trustForwardedHeader} 默认关闭是有意为之。
     * 请求头由客户端完全可控，若在「未经过反向代理」的环境下直接信任它，
     * 攻击者只需每次请求换一个 {@code X-Forwarded-For} 值即可完全绕过限流。
     *
     * <p>仅当应用<b>确实</b>部署在受信反向代理之后，且该代理会
     * <b>覆盖</b>（而非追加）该请求头时才应开启：Nginx 侧必须使用
     * {@code proxy_set_header X-Forwarded-For $remote_addr;}，
     * 若使用 {@code $proxy_add_x_forwarded_for} 则会保留客户端伪造的值。
     */
    public RateLimitProperties {
        // 用包装类型 + 默认 TRUE，而不是 boolean 的隐式默认 FALSE：
        // 漏写配置时的行为应该是「保护生效」，而不是「保护静默关闭」。
        // 这类开关一旦默认关闭，几乎不会有人发现它没开。
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (store == null || store.isBlank()) {
            store = "memory";
        }
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = "stockanalyst";
        }
        if (clientIpHeader == null || clientIpHeader.isBlank()) {
            clientIpHeader = "X-Forwarded-For";
        }
        // 速率必须有默认值：限流器实现里直接做 `strictRatePerMin() / 60.0`，
        // 配置缺失时会在启动阶段 NPE，而报错信息只会指向一行除法表达式，
        // 很难看出是「漏配了一个字段」。
        if (strictRatePerMin == null) {
            strictRatePerMin = 20;
        }
        if (strictBurst == null) {
            strictBurst = 10;
        }
        if (looseRatePerMin == null) {
            looseRatePerMin = 600;
        }
        if (looseBurst == null) {
            looseBurst = 120;
        }
    }
}
