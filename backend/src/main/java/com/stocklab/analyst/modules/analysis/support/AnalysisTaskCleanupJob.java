package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.config.properties.AnalysisProperties;
import com.stocklab.analyst.modules.analysis.mapper.AnalysisTaskMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 异步分析任务的过期清理。
 *
 * <h2>为什么这张表必须清理</h2>
 * {@code analysis_tasks} 与 {@code ai_reports} 的生长曲线完全不同：
 * <ul>
 *   <li>{@code ai_reports} 按 code 组织，同一只标的反复分析会不断插入新行，
 *       但它是「报告存档」，历史本身就是价值；</li>
 *   <li>{@code analysis_tasks} 是<b>一次提交的生命周期</b>：PENDING → RUNNING →
 *       终态。终态之后这行数据对业务已经没有任何用途——报告已经写进
 *       {@code ai_reports}，前端拿到结果也不会再轮询。</li>
 * </ul>
 * 所以任务行是纯粹的「有寿命的过程数据」。不清理的后果不是报错，而是
 * 这张表带着 {@code report_json}（单条可达数 KB）稳定增长：
 * 半年后某个查询开始变慢，而没人会想到原因是「每次分析都留下了一条任务记录」。
 * 表结构里 {@code idx_analysis_task_created} 的注释写着「支撑按状态清理过期任务」，
 * 本类就是把那句话兑现的地方。
 *
 * <h2>清理边界（安全前提）</h2>
 * <ul>
 *   <li><b>只删终态</b>。{@code PENDING} / {@code RUNNING} 的行绝不动——
 *       后台线程稍后写回状态时如果发现目标行没了，任务对提交方就是凭空消失。
 *       这个条件在 SQL 里，见 {@code AnalysisTaskMapper.deleteTerminalOlderThan}。</li>
 *   <li><b>保留期默认 3 天</b>，远大于用户「看着进度条等结果」的时间跨度。
 *       过期后前端若还在轮询会拿到 404，前端按「任务不存在或已过期，请重新发起」
 *       提示——这是可接受的，但保留期不该短到让正常使用撞上它。</li>
 *   <li><b>初始延迟 5 分钟</b>。{@code fixedDelay} 默认启动即执行，
 *       而启动阶段本来就有一堆初始化在抢数据库连接，清理没必要凑这个热闹。</li>
 * </ul>
 *
 * <h2>清理失败不应该影响任何东西</h2>
 * 删除语句出错（例如数据库只读）只记日志。清理是维护动作，
 * 它失败的最坏后果是「表继续长」，而不是「功能不可用」。
 */
@Component
public class AnalysisTaskCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskCleanupJob.class);

    private final AnalysisTaskMapper taskMapper;
    private final AnalysisProperties properties;
    private final MeterRegistry meterRegistry;

    public AnalysisTaskCleanupJob(AnalysisTaskMapper taskMapper,
                                  AnalysisProperties properties,
                                  MeterRegistry meterRegistry) {
        this.taskMapper = taskMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${app.analysis.cleanup-interval:6h}",
            initialDelayString = "${app.analysis.cleanup-initial-delay:5m}")
    public void purgeExpiredTasks() {
        if (!properties.taskRetentionEnabled()) {
            log.debug("异步任务清理已关闭（app.analysis.task-retention <= 0），跳过");
            return;
        }

        long cutoff = Instant.now().minus(properties.taskRetention()).getEpochSecond();
        try {
            int deleted = taskMapper.deleteTerminalOlderThan(cutoff);
            if (deleted > 0) {
                meterRegistry.counter("app.analysis.task.purged").increment(deleted);
                log.info("已清理 {} 条超过 {} 的终态分析任务（cutoff={}）",
                        deleted, properties.taskRetention(), cutoff);
            } else {
                log.debug("无过期的终态分析任务需要清理");
            }
        } catch (RuntimeException ex) {
            // 清理失败不是功能故障：表继续长而已，下一轮还有机会
            log.warn("清理过期分析任务失败：{}", ex.getMessage());
        }
    }
}
