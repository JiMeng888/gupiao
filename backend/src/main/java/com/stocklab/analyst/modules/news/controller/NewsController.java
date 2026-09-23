package com.stocklab.analyst.modules.news.controller;

import com.stocklab.analyst.common.api.ApiResult;
import com.stocklab.analyst.common.ratelimit.RateLimit;
import com.stocklab.analyst.common.ratelimit.RateLimitScope;
import com.stocklab.analyst.modules.news.dto.NewsChannelView;
import com.stocklab.analyst.modules.news.service.NewsService;
import com.stocklab.analyst.news.NewsItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 财经资讯接口。
 *
 * <p>列表接口走宽松限流桶。虽然上游结果有 60 秒缓存，但缓存未命中时每个
 * {@code all} 请求会并发打 5 次上游，不限流时可以被轻易放大成对上游的压测。
 */
@RestController
@RequestMapping("/api/v1/news")
@Validated
@Tag(name = "财经资讯", description = "新浪财经五频道聚合（财经要闻 / A股 / 港股 / 美股 / 全球市场）")
public class NewsController {

    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @GetMapping("/list")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "资讯列表",
            description = "channel=all 时聚合五个频道并按链接去重。"
                    + "上游不可用时返回空数组而不是报错——资讯缺失不应让整页失败。")
    public ApiResult<List<NewsItem>> list(
            @Parameter(description = "频道：all / finance / a-shares / hk / us / global")
            @RequestParam(defaultValue = "all") String channel,

            @Parameter(description = "返回条数")
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "至少返回 1 条")
            @Max(value = 50, message = "最多返回 50 条")
            int limit) {
        return ApiResult.ok(newsService.list(channel, limit));
    }

    @GetMapping("/channels")
    @Operation(summary = "频道清单", description = "供前端渲染频道标签页。")
    public ApiResult<List<NewsChannelView>> channels() {
        return ApiResult.ok(newsService.channels());
    }
}
