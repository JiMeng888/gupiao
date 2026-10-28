package com.stocklab.analyst.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI 分析模块配置。
 *
 * @param archiveMaxAge      存档的有效期。超过它的历史报告不再被当作「最新结论」返回，
 *                           会重新生成一份。设为 0 或负数表示不限定有效期（旧行为）。
 *                           见 {@code AiReportArchiver#loadLatest} 对「为什么必须有有效期」的说明。
 * @param taskRetention      异步任务的保留时长。超过它的<b>终态</b>任务会被定时清理。
 *                           {@code PENDING} / {@code RUNNING} 永远不会被清理——那会把
 *                           正在执行的任务结果连同状态一起删掉。设为 0 表示不清理。
 * @param cleanupInterval    清理任务的执行间隔（固定延迟：上一轮结束后再等这么久）。
 */
@ConfigurationProperties(prefix = "app.analysis")
public record AnalysisProperties(
        Duration archiveMaxAge,
        Duration taskRetention,
        Duration cleanupInterval
) {

    /**
     * 默认 6 小时。
     *
     * <p>取值依据是报告正文的性质：{@code ruleThesis()} 拼出来的句子是
     * 「贵州茅台（600519）最新价 1685.00，当日 +1.23%」——它把生成那一刻的价格
     * 写成了自然语言。A 股一个交易日的交易时长是 4 小时，
     * 6 小时足以覆盖「同一次会话里反复查看同一只标的」这个主要复用场景，
     * 又不会让一份报告跨过两个交易日还在冒充「最新」。
     *
     * <p>取 {@code null} 时用这个默认值而不是「不限有效期」：
     * 配置漏填导致回到「永久复用」是最糟的失败方向。
     */
    public AnalysisProperties {
        if (archiveMaxAge == null) {
            archiveMaxAge = Duration.ofHours(6);
        }
        if (taskRetention == null) {
            taskRetention = Duration.ofDays(3);
        }
        if (cleanupInterval == null) {
            cleanupInterval = Duration.ofHours(6);
        }
    }

    /** 是否启用存档时效。 */
    public boolean archiveMaxAgeEnabled() {
        return !archiveMaxAge.isZero() && !archiveMaxAge.isNegative();
    }

    /** 是否启用异步任务清理。 */
    public boolean taskRetentionEnabled() {
        return !taskRetention.isZero() && !taskRetention.isNegative();
    }
}
