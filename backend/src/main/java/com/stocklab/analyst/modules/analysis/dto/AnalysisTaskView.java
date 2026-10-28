package com.stocklab.analyst.modules.analysis.dto;

import com.stocklab.analyst.modules.analysis.entity.AnalysisTaskStatus;

/**
 * 异步分析任务的查询结果。
 *
 * <p>{@code status} 驱动前端行为：
 * <ul>
 *   <li>{@code PENDING} / {@code RUNNING} —— 前端继续轮询；</li>
 *   <li>{@code SUCCESS} —— {@code report} 非空，前端渲染报告并停止轮询；</li>
 *   <li>{@code FAILED} —— {@code error} 说明原因，前端展示错误并停止轮询。</li>
 * </ul>
 *
 * @param taskId 任务 ID
 * @param status 当前状态
 * @param report 完成后的报告；未完成时为 {@code null}
 * @param error  失败原因；成功时为空
 */
public record AnalysisTaskView(
        String taskId,
        AnalysisTaskStatus status,
        AnalysisReport report,
        String error
) {
}
