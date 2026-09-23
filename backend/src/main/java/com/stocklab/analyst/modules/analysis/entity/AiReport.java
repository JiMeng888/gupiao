package com.stocklab.analyst.modules.analysis.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * AI 分析报告存档。
 *
 * <p>{@code reportJson} 存序列化后的报告全文。用 JSON 字段而不是拆成多列，
 * 是因为报告结构（多空观点 / 风险 / 操作建议）还会演进，
 * 拆列会导致每次调整输出结构都要改表。
 *
 * <p><b>列类型注意</b>：原 FastAPI 版本用 {@code VARCHAR(4000)}。
 * MySQL 的 VARCHAR 长度按<b>字符</b>算但行限制按字节算，
 * utf8mb4 下 4000 字符最多占 16000 字节，加上其它列虽仍在 65535 字节的行上限内，
 * 但只要报告变长就会截断（且 MySQL 严格模式下会直接报错，宽松模式静默截断成非法 JSON）。
 * 因此这里改为 {@code TEXT}（64KB），消除这类隐患。
 */
@TableName("ai_reports")
public class AiReport {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 触发分析的用户；游客视角的分析可以为 null。 */
    private String userId;

    private String code;

    /** 报告全文（JSON 字符串）。 */
    private String reportJson;

    /** 实际使用的模型名，便于回溯「同一只股票不同模型给出的结论差异」。 */
    private String model;

    @TableField(fill = FieldFill.INSERT)
    private Long createdAt;

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

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
