package com.stocklab.analyst.modules.indicator.controller;

import com.stocklab.analyst.common.api.ApiResult;
import com.stocklab.analyst.common.ratelimit.RateLimit;
import com.stocklab.analyst.common.ratelimit.RateLimitScope;
import com.stocklab.analyst.modules.indicator.dto.IndicatorName;
import com.stocklab.analyst.modules.indicator.dto.IndicatorResponse;
import com.stocklab.analyst.modules.indicator.service.IndicatorService;
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

import java.util.Set;

/**
 * 技术指标接口。
 *
 * <p>只读接口，使用宽松限流桶。
 */
@RestController
@RequestMapping("/api/v1/indicator")
@Validated
@Tag(name = "技术指标", description = "MACD / RSI / BOLL / KDJ / MA 计算与解读")
public class IndicatorController {

    private final IndicatorService indicatorService;

    public IndicatorController(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    @GetMapping("/{code}")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "计算技术指标",
            description = "按需返回被请求的指标；未请求的指标字段不会出现在响应中。"
                    + "signals 字段为各指标的末值解读。")
    public ApiResult<IndicatorResponse> getIndicators(
            @Parameter(description = "股票代码", example = "600519")
            @PathVariable String code,

            @Parameter(description = "逗号分隔的指标名，可选 MACD/RSI/BOLL/KDJ/MA")
            @RequestParam(defaultValue = "MACD,RSI,BOLL,KDJ") String indicators,

            @Parameter(description = "参与计算的 K 线根数")
            @RequestParam(defaultValue = "120")
            @Min(value = 20, message = "至少需要 20 根 K 线才能计算指标")
            @Max(value = 500, message = "最多支持 500 根 K 线")
            int limit) {

        Set<IndicatorName> names = IndicatorName.parseAll(indicators);
        return ApiResult.ok(indicatorService.compute(code, names, limit));
    }
}
