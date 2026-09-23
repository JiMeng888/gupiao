package com.stocklab.analyst.modules.analysis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * AI 分析请求体。
 *
 * <p>保留 JSON body 形式的入口，是为了对齐原 FastAPI 版本
 * {@code POST /api/v1/analysis} 的契约（前端有多种触发方式）。
 *
 * @param code 股票代码
 */
public record AnalysisRequest(
        @NotBlank(message = "股票代码不能为空")
        @Schema(description = "股票代码", example = "600519")
        String code
) {
}
