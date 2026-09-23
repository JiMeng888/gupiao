package com.stocklab.analyst.ws;

import com.stocklab.analyst.common.api.ApiResult;
import com.stocklab.analyst.common.ratelimit.RateLimit;
import com.stocklab.analyst.common.ratelimit.RateLimitScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WebSocket 推送状态查询（监控用）。
 *
 * <p>路径沿用原 FastAPI 版本的 {@code /ws/stats}，但返回体统一包进
 * {@code ApiResult}，与全站其它接口保持一致。
 *
 * <p>有意不要求登录：它是运维观测口，且返回的信息已做收敛
 * （只给前 20 个标的的订阅热度，不给完整清单）。真正的生产做法是把它
 * 收进 Actuator 的 {@code /actuator} 端点并加上管理端口隔离，
 * 这里为了沿用原路径而保留在业务路径下，并在文档里标注这个取舍。
 */
@RestController
@RequestMapping("/ws")
@Tag(name = "WebSocket 推送", description = "连接与订阅状态")
public class WsStatsController {

    private final QuoteSessionRegistry registry;

    public WsStatsController(QuoteSessionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/stats")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "推送状态",
            description = "返回当前连接数、被订阅标的数，以及订阅数最多的 20 个标的明细。")
    public ApiResult<QuoteSessionRegistry.Stats> stats() {
        return ApiResult.ok(registry.stats());
    }
}
