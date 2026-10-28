package com.stocklab.analyst.modules.analysis.controller;

import com.stocklab.analyst.common.api.ApiResult;
import com.stocklab.analyst.common.ratelimit.RateLimit;
import com.stocklab.analyst.common.ratelimit.RateLimitScope;
import com.stocklab.analyst.modules.analysis.dto.AnalysisReport;
import com.stocklab.analyst.modules.analysis.dto.AnalysisRequest;
import com.stocklab.analyst.modules.analysis.dto.AnalysisTaskSubmission;
import com.stocklab.analyst.modules.analysis.dto.AnalysisTaskView;
import com.stocklab.analyst.modules.analysis.service.AnalysisService;
import com.stocklab.analyst.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 深度分析接口。
 *
 * <h2>GET 与 POST 的语义区分（相对原实现的核心修正）</h2>
 * 原 FastAPI 版本里 {@code GET /analysis/{code}} 与 {@code POST /analysis/{code}}
 * 做的是同一件事——都重新调用一次大模型。GET 语义上是「读取」，实际却在
 * 触发一次几十秒、按 token 计费的写操作。
 *
 * <p>本实现：
 * <ul>
 *   <li>{@code GET} —— 有存档就返回存档（{@code fromArchive=true}），
 *       没有才生成一次并归档。反复刷新页面不会反复烧钱。</li>
 *   <li>{@code POST} —— 显式要求重新生成。</li>
 * </ul>
 *
 * <h2>限流</h2>
 * 两类入口都走<b>严格桶</b>。这是全站唯一会产生外部费用的接口，
 * 也是最慢的接口（10~40 秒），必须比查询类接口收得更紧。
 * 只有 {@code /history} 是纯读库，用宽松桶。
 *
 * <h2>登录要求</h2>
 * 不强制登录（游客也能体验），但带上令牌时会把 {@code userId} 记进报告归档，
 * 便于回溯「谁触发了这次调用」。
 */
@RestController
@RequestMapping("/api/v1/analysis")
@Validated
@Tag(name = "AI 深度分析", description = "结构化分析报告生成与历史存档")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @GetMapping("/{code}")
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "获取分析报告",
            description = "优先返回时效内的历史存档（fromArchive=true），"
                    + "无存档或存档已超过 app.analysis.archive-max-age 时生成一次并归档。"
                    + "未配置大模型 API Key 时返回规则化报告（model=rule-based），且规则化报告不入存档。"
                    + "model 与 notice 字段必须在前端展示。"
                    + "注意本接口在无存档时会同步等待大模型（最长 45 秒），"
                    + "前端超时预算必须大于该值；想避免等待请用 POST /{code}/async。")
    public ApiResult<AnalysisReport> get(
            @Parameter(description = "股票代码", example = "600519")
            @PathVariable String code,

            @CurrentUserId(required = false) String userId) {
        return ApiResult.ok(analysisService.analyze(code, userId, false));
    }

    @PostMapping("/{code}")
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "重新生成分析报告",
            description = "忽略存档强制重新生成并归档。会真实调用大模型，产生费用与 10~40 秒延迟。")
    public ApiResult<AnalysisReport> regenerate(
            @Parameter(description = "股票代码", example = "600519")
            @PathVariable String code,

            @CurrentUserId(required = false) String userId) {
        return ApiResult.ok(analysisService.analyze(code, userId, true));
    }

    @PostMapping
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "重新生成分析报告（请求体形式）",
            description = "与 POST /{code} 等价，保留 JSON body 入口以兼容前端多种触发方式。")
    public ApiResult<AnalysisReport> regenerateWithBody(
            @Valid @RequestBody AnalysisRequest request,
            @CurrentUserId(required = false) String userId) {
        return ApiResult.ok(analysisService.analyze(request.code(), userId, true));
    }

    @GetMapping("/{code}/history")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "历史分析报告",
            description = "按生成时间倒序返回该标的的历史报告存档（纯读库，不触发模型调用）。")
    public ApiResult<List<AnalysisReport>> history(
            @Parameter(description = "股票代码", example = "600519")
            @PathVariable String code,

            @Parameter(description = "返回条数")
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "至少返回 1 条")
            @Max(value = 50, message = "最多返回 50 条")
            int limit) {
        return ApiResult.ok(analysisService.history(code, limit));
    }

    // ==================================================================
    // 异步端点
    // ==================================================================

    /**
     * 提交异步分析任务。
     *
     * <p>相对同步的 {@code POST /{code}}，这个端点<b>立即返回</b> taskId（202），
     * 真正的大模型调用在后台线程执行，不占用 Tomcat 工作线程。前端拿到 taskId
     * 后轮询 {@code GET /tasks/{taskId}} 直到 {@code status} 变为终态。
     *
     * <p>这是消除「约 5 个并发分析请求吃掉大半 Tomcat 线程池」的关键入口。
     * 代码非法时仍会同步返回 404（入参校验不异步化）。
     */
    @PostMapping("/{code}/async")
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "提交异步分析任务",
            description = "校验代码后立即返回 taskId（HTTP 202），大模型调用在后台执行。"
                    + "前端轮询 GET /api/v1/analysis/tasks/{taskId} 获取结果。")
    public ResponseEntity<ApiResult<AnalysisTaskSubmission>> submitAsync(
            @Parameter(description = "股票代码", example = "600519")
            @PathVariable String code,

            @CurrentUserId(required = false) String userId) {
        AnalysisTaskSubmission submission = analysisService.submit(code, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResult.ok(submission));
    }

    /**
     * 查询异步任务状态与结果。
     *
     * <p>纯读库、不触发模型调用，用宽松桶。任务不存在返回 404，
     * 前端据此清理本地残留的过期 taskId。
     */
    @GetMapping("/tasks/{taskId}")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "查询异步分析任务",
            description = "返回任务状态（PENDING/RUNNING/SUCCESS/FAILED）。"
                    + "SUCCESS 时 report 非空；FAILED 时 error 说明原因。")
    public ApiResult<AnalysisTaskView> task(
            @Parameter(description = "任务 ID")
            @PathVariable String taskId,
            @CurrentUserId(required = false) String userId) {
        return ApiResult.ok(analysisService.task(taskId, userId));
    }
}
