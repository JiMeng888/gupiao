package com.stocklab.analyst.modules.analysis.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * AI 分析异步任务。
 *
 * <p>一次「提交分析」的生命周期载体。异步化之前，POST 分析接口在请求线程上
 * 同步等待大模型（最长 45 秒）；现在请求线程只投递任务并立刻返回 taskId，
 * 后台线程执行取数 + 调模型 + 归档，完成后把报告写回 {@code reportJson}。
 *
 * <p>{@code status} 取值见 {@link AnalysisTaskStatus}。状态机单向流转：
 * {@code PENDING → RUNNING → SUCCESS / FAILED}，不存在回退。
 */
@TableName("analysis_tasks")
public class AnalysisTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 触发者；游客触发为 null，仅作审计。 */
    private String userId;

    /** 股票代码（6 位数字）。 */
    private String code;

    /** 任务状态，见 {@link AnalysisTaskStatus}。 */
    private String status;

    /** 成功后的报告全文 JSON；未完成时为 null。 */
    private String reportJson;

    /** 产出报告的模型名。 */
    private String model;

    /** 失败原因（面向用户的简短说明）。 */
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private Long createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReportJson() {
        return reportJson;
    }

    public void setReportJson(String reportJson) {
        this.reportJson = reportJson;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
