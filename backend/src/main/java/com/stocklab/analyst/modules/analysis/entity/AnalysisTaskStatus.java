package com.stocklab.analyst.modules.analysis.entity;

/**
 * AI 分析异步任务状态。
 *
 * <p>状态机单向流转：{@code PENDING → RUNNING → SUCCESS / FAILED}。
 * 没有「重试回退到 PENDING」——失败的任务由调用方重新提交一个新的任务，
 * 保持「一次任务 = 一次不可逆的生命周期」的简单语义。
 */
public enum AnalysisTaskStatus {

    /** 已投递，尚未被工作线程拾取。 */
    PENDING,

    /** 正在执行取数 + 调模型。 */
    RUNNING,

    /** 完成，{@code reportJson} 已填充。 */
    SUCCESS,

    /** 失败，{@code errorMsg} 说明原因。 */
    FAILED
}
