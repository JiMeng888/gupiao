package com.stocklab.analyst.modules.fundamental.controller;

import com.stocklab.analyst.common.api.ApiResult;
import com.stocklab.analyst.common.ratelimit.RateLimit;
import com.stocklab.analyst.common.ratelimit.RateLimitScope;
import com.stocklab.analyst.modules.fundamental.dto.DupontAnalysis;
import com.stocklab.analyst.modules.fundamental.dto.FundamentalOverview;
import com.stocklab.analyst.modules.fundamental.dto.StatementsResponse;
import com.stocklab.analyst.modules.fundamental.service.FundamentalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 财务基本面接口。
 *
 * <p>只读接口，使用宽松限流桶。
 *
 * <p><b>注意响应中的 {@code dataSource} 字段</b>：
 * 财务数据在真实源不可用时会返回估算值，此时 {@code dataSource=estimated}。
 * 前端必须把这个标识显示出来，不能把估算值当真实披露数据展示。
 */
@RestController
@RequestMapping("/api/v1/fundamental")
@Validated
@Tag(name = "财务基本面", description = "财务指标概览 / 杜邦分析 / 逐期业绩 / 同行对比")
public class FundamentalController {

    private final FundamentalService fundamentalService;

    public FundamentalController(FundamentalService fundamentalService) {
        this.fundamentalService = fundamentalService;
    }

    @GetMapping("/{code}")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "财务指标概览",
            description = "含 PE/PB/ROE/毛利率/净利率/资产负债率，以及相对行业的估值溢价。"
                    + "dataSource=estimated 表示当前为估算值，非真实披露数据。")
    public ApiResult<FundamentalOverview> getOverview(
            @Parameter(description = "股票代码", example = "600519")
            @PathVariable String code) {
        return ApiResult.ok(fundamentalService.getOverview(code));
    }

    @GetMapping("/{code}/dupont")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "杜邦分析",
            description = "ROE = 净利率 × 总资产周转率 × 权益乘数。"
                    + "周转率为反推值以严格满足恒等式，详见接口文档说明。")
    public ApiResult<DupontAnalysis> getDupont(
            @Parameter(description = "股票代码", example = "600519")
            @PathVariable String code) {
        return ApiResult.ok(fundamentalService.getDupont(code));
    }

    @GetMapping("/{code}/statements")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "逐期业绩报表",
            description = "返回真实披露的逐期业绩摘要（营收 / 净利润 / ROE / 每股指标 / 分红方案）。"
                    + "取不到数据时返回空列表，不返回编造数据。"
                    + "A 股财报为年内累计值，趋势比较请使用同一报告期口径。")
    public ApiResult<StatementsResponse> getStatements(
            @Parameter(description = "股票代码", example = "600519")
            @PathVariable String code,

            @Parameter(description = "返回的报告期数量")
            @RequestParam(defaultValue = "8")
            @Min(value = 1, message = "至少返回 1 期")
            @Max(value = 40, message = "最多返回 40 期")
            int periods) {
        return ApiResult.ok(fundamentalService.getStatements(code, periods));
    }

    @GetMapping("/compare")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "同行对比",
            description = "一次最多 6 只股票。上限是为了避免单次请求放大成数十次上游调用。")
    public ApiResult<List<FundamentalOverview>> compare(
            @Parameter(description = "逗号分隔的股票代码", example = "600519,000858,000568")
            @RequestParam String codes) {
        return ApiResult.ok(fundamentalService.compare(codes));
    }
}
