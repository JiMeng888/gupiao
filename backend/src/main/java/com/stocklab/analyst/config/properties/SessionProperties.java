package com.stocklab.analyst.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话吊销存储配置。
 *
 * @param store     存储实现：{@code memory}（单副本，默认）/ {@code redis}（多实例共享）
 * @param keyPrefix Redis 键前缀
 */
@ConfigurationProperties(prefix = "app.session")
public record SessionProperties(
        String store,
        String keyPrefix
) {
    /**
     * <b>默认值说明</b>：{@code store} 默认 {@code memory}，因为改成
     * {@code redis} 会引入一个运行时依赖（Redis）。默认值不该让「不配任何
     * 东西直接启动」这件事失败。
     *
     * <p>但这意味着<b>多副本身份默认配置下的登出不跨实例生效</b>——
     * 这是必须在部署文档里写清、并在启动日志里主动告警的风险点
     * （见 {@code SecurityStoreConfig}），不能只靠一句注释。
     */
    public SessionProperties {
        if (store == null || store.isBlank()) {
            store = "memory";
        }
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = "stockanalyst:revoked";
        }
    }
}
