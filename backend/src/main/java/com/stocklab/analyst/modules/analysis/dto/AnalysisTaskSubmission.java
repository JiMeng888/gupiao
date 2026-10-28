package com.stocklab.analyst.modules.analysis.dto;

/**
 * 提交异步分析任务的响应。
 *
 * <p>请求线程只返回一个 taskId 就结束了，真正耗时的大模型调用在后台进行。
 * 前端拿到 taskId 后轮询 {@code GET /analysis/tasks/{taskId}} 直到
 * {@code status} 变为 {@code SUCCESS}（读 {@code report}）或 {@code FAILED}。
 *
 * @param taskId 任务 ID，用于轮询查询结果
 * @param status 提交时的初始状态，恒为 {@code PENDING}
 */
public record AnalysisTaskSubmission(
        String taskId,
        String status
) {
}
