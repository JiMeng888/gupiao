package com.stocklab.analyst.modules.holding.controller;

import com.stocklab.analyst.common.api.ApiResult;
import com.stocklab.analyst.common.ratelimit.RateLimit;
import com.stocklab.analyst.common.ratelimit.RateLimitScope;
import com.stocklab.analyst.modules.holding.dto.HoldingBuyRequest;
import com.stocklab.analyst.modules.holding.dto.HoldingSellRequest;
import com.stocklab.analyst.modules.holding.dto.HoldingView;
import com.stocklab.analyst.modules.holding.dto.PortfolioSummary;
import com.stocklab.analyst.modules.holding.service.HoldingService;
import com.stocklab.analyst.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 持仓管理接口（全部需要登录）。
 *
 * <p>买入 / 卖出走严格限流桶：这两个接口会写库且可被重复提交利用，
 * 严格桶能把「脚本高频刷单」挡在业务逻辑之前。
 */
@RestController
@RequestMapping("/api/v1/holdings")
@Tag(name = "持仓", description = "买入 / 卖出 / 持仓列表 / 组合总览")
public class HoldingController {

    private final HoldingService holdingService;

    public HoldingController(HoldingService holdingService) {
        this.holdingService = holdingService;
    }

    @GetMapping
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "持仓列表", description = "含实时行情、市值、浮动盈亏与当日盈亏")
    public ApiResult<List<HoldingView>> list(@CurrentUserId String userId) {
        return ApiResult.ok(holdingService.listHoldings(userId));
    }

    @GetMapping("/summary")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "组合总览", description = "总市值 / 总成本 / 总盈亏 / 当日盈亏 / 持仓只数")
    public ApiResult<PortfolioSummary> summary(@CurrentUserId String userId) {
        return ApiResult.ok(holdingService.portfolioSummary(userId));
    }

    @PostMapping("/buy")
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "买入 / 补仓", description = "按加权平均法更新成本价；已持仓则合并计算")
    public ApiResult<HoldingView> buy(@CurrentUserId String userId,
                                     @Valid @RequestBody HoldingBuyRequest request) {
        return ApiResult.ok(holdingService.buy(userId, request));
    }

    @PostMapping("/sell")
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "卖出", description = "扣减股数，平均成本不变；可卖至 0 股（清仓）")
    public ApiResult<HoldingView> sell(@CurrentUserId String userId,
                                       @Valid @RequestBody HoldingSellRequest request) {
        return ApiResult.ok(holdingService.sell(userId, request));
    }

    @DeleteMapping("/{code}")
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "删除持仓记录", description = "幂等：记录不存在时同样返回成功")
    public ApiResult<Map<String, Boolean>> delete(@CurrentUserId String userId,
                                                 @PathVariable String code) {
        holdingService.removeHolding(userId, code);
        return ApiResult.ok(Map.of("ok", true));
    }
}
