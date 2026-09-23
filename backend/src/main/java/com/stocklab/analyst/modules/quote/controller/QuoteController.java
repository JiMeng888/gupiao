package com.stocklab.analyst.modules.quote.controller;

import com.stocklab.analyst.common.api.ApiResult;
import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.common.ratelimit.RateLimit;
import com.stocklab.analyst.common.ratelimit.RateLimitScope;
import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.IndexSnapshot;
import com.stocklab.analyst.market.model.MarketPeriod;
import com.stocklab.analyst.market.model.Mover;
import com.stocklab.analyst.market.model.MoverMetric;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.market.model.StockBrief;
import com.stocklab.analyst.modules.quote.dto.CarouselItem;
import com.stocklab.analyst.modules.quote.dto.MarketSourceStatus;
import com.stocklab.analyst.modules.quote.service.QuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 行情接口。
 *
 * <p>全部为公开只读接口（无需登录），使用宽松限流桶。
 *
 * <p>路径设计注意：{@code /indices}、{@code /carousel}、{@code /search}、{@code /source}
 * 与 {@code /{code}} 形式上是冲突的，Spring MVC 会按「字面量优先于路径变量」
 * 选择更精确的映射，因此这些固定路径不会被 {@code /{code}} 吞掉。
 */
@RestController
@RequestMapping("/api/v1/quote")
@Validated
@Tag(name = "行情", description = "指数概览、实时报价、K 线、搜索、数据源状态")
public class QuoteController {

    private final QuoteService quoteService;

    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @GetMapping("/indices")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "指数概览", description = "上证 / 深证 / 创业板 / 科创50 / 沪深300")
    public ApiResult<List<IndexSnapshot>> indices() {
        return ApiResult.ok(quoteService.getIndices());
    }

    @GetMapping("/carousel")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "首页轮播精选", description = "同一天内结果稳定，跨天轮换")
    public ApiResult<List<CarouselItem>> carousel(
            @RequestParam(defaultValue = "6")
            @Min(value = 1, message = "count 至少为 1")
            @Max(value = 8, message = "count 最多为 8")
            int count) {
        return ApiResult.ok(quoteService.getCarousel(count));
    }

    @GetMapping("/search")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "搜索股票", description = "按代码或名称模糊匹配全 A 股")
    public ApiResult<List<StockBrief>> search(
            @RequestParam("q") @NotBlank(message = "关键字不能为空") String keyword) {
        return ApiResult.ok(quoteService.search(keyword));
    }

    @GetMapping("/source")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "数据源状态", description = "用于前端展示「真实行情 / 演示数据」标识")
    public ApiResult<MarketSourceStatus> source() {
        return ApiResult.ok(quoteService.sourceStatus());
    }

    @GetMapping("/movers")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "市场榜单",
            description = "涨幅榜 / 跌幅榜 / 成交额榜。metric=changePct|amount，order=desc|asc，"
                    + "跌幅榜用 metric=changePct&order=asc 表达")
    public ApiResult<List<Mover>> movers(
            @Parameter(description = "排序维度：changePct（涨跌幅）或 amount（成交额）")
            @RequestParam(defaultValue = "changePct") String metric,
            @Parameter(description = "排序方向：desc（默认，取最大值一侧）或 asc（取最小值一侧）")
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "limit 至少为 1")
            @Max(value = 20, message = "limit 最多为 20")
            int limit) {
        MoverMetric metricEnum = MoverMetric.parse(metric);
        if (metricEnum == null) {
            throw new BizException(ErrorCode.INVALID_PARAMS,
                    "不支持的榜单维度：" + metric + "（可选 changePct / amount）");
        }
        if (!"asc".equalsIgnoreCase(order) && !"desc".equalsIgnoreCase(order)) {
            throw new BizException(ErrorCode.INVALID_PARAMS,
                    "order 只支持 asc / desc，收到：" + order);
        }
        boolean ascending = "asc".equalsIgnoreCase(order);
        return ApiResult.ok(quoteService.topMovers(metricEnum, ascending, limit));
    }

    @GetMapping("/{code}")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "实时报价")
    public ApiResult<Quote> quote(
            @Parameter(description = "股票代码，支持 600519 / 600519.SH / sh600519", example = "600519")
            @PathVariable String code) {
        return ApiResult.ok(quoteService.getQuote(code));
    }

    @GetMapping("/{code}/bars")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "K 线", description = "支持日/周/月/60分/30分/15分/5分/1分/分时")
    public ApiResult<List<Bar>> bars(
            @PathVariable String code,

            @Parameter(description = "周期别名")
            @RequestParam(defaultValue = "daily") String period,

            @RequestParam(defaultValue = "120")
            @Min(value = 1, message = "limit 至少为 1")
            @Max(value = 500, message = "limit 最多为 500")
            int limit,

            @Parameter(description = "起始日期 YYYY-MM-DD，留空不限制")
            @RequestParam(defaultValue = "") String start,

            @Parameter(description = "结束日期 YYYY-MM-DD，留空不限制")
            @RequestParam(defaultValue = "") String end) {

        MarketPeriod marketPeriod = MarketPeriod.fromAlias(period)
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_PERIOD,
                        "不支持的 K 线周期: " + period
                                + "（可选 daily/weekly/monthly/60min/30min/15min/5min/1min/trend）"));

        return ApiResult.ok(quoteService.getBars(code, marketPeriod, limit, start, end));
    }
}
