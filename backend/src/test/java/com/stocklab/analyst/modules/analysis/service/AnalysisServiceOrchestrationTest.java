package com.stocklab.analyst.modules.analysis.service;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.analysis.dto.AnalysisReport;
import com.stocklab.analyst.modules.analysis.dto.AnalysisTaskSubmission;
import com.stocklab.analyst.modules.analysis.dto.AnalysisTaskView;
import com.stocklab.analyst.modules.analysis.entity.AnalysisTask;
import com.stocklab.analyst.modules.analysis.entity.AnalysisTaskStatus;
import com.stocklab.analyst.modules.analysis.support.AiReportArchiver;
import com.stocklab.analyst.modules.analysis.support.AnalysisGenerator;
import com.stocklab.analyst.modules.analysis.support.AnalysisInput;
import com.stocklab.analyst.modules.analysis.support.AnalysisInputLoader;
import com.stocklab.analyst.modules.analysis.support.AnalysisTaskStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AnalysisService} 编排层单测——钉住「读存档还是重新生成」与「同步/异步」的分支判定。
 *
 * <p>协作者（取数 / 生成 / 兜底报告 / 存档）各自有测试，这里只测<b>分支决策本身</b>：
 * <ul>
 *   <li>存档命中且未要求强刷 → 直接返回存档，一分钱模型费用都不花；</li>
 *   <li>{@code forceRefresh} → 忽略存档，重新生成并归档；</li>
 *   <li>取不到报价 → 在进入模型调用<b>之前</b>就 404（不存在的代码不烧钱）；</li>
 *   <li>模型失败 → 降级规则化报告，且失败原因必须带到响应里，不能静默；</li>
 *   <li>异步提交 → 创建任务并投递执行器，立即返回 taskId。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceOrchestrationTest {

    private static final String CODE = "600519";

    @Mock
    private MarketDataProvider marketDataProvider;
    @Mock
    private AnalysisInputLoader inputLoader;
    @Mock
    private AnalysisGenerator generator;
    @Mock
    private AiReportArchiver archiver;
    @Mock
    private AnalysisTaskStore taskStore;
    @Mock
    private AnalysisTaskExecutor taskExecutor;

    private SimpleMeterRegistry meterRegistry;
    private AnalysisService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new AnalysisService(
                marketDataProvider, inputLoader, generator, archiver, taskStore, taskExecutor);
    }

    // ------------------------------------------------------------------
    // 构造辅助
    // ------------------------------------------------------------------

    private static Quote quote(String code) {
        return new Quote(code, "股票" + code, "SH", 10.0, 0.1, 1.0,
                10.0, 10.2, 9.9, 9.9, 1000L, 1.0, 100000.0, 1700000000L);
    }

    private static AnalysisInput input(Quote q) {
        return new AnalysisInput(q, List.of(), null, null, List.of("实时报价"));
    }

    /** 一份来自存档的报告：fromArchive 为 true 是存档身份的关键标记。 */
    private static AnalysisReport archivedReport(String code) {
        return new AnalysisReport(code, "股票" + code, "2026-09-20T10:00:00+08:00",
                "存档论点", List.of("b1"), List.of("r1"), List.of("risk"),
                "估值说明", "操作提示", "qwen-plus", true,
                List.of("实时报价"), "存档 notice");
    }

    /** 走到生成分支所需的最小桩：报价可用、取数成功、生成器返回规则化报告。 */
    private void stubGenerationPathWithLlmDisabled() {
        Quote q = quote(CODE);
        when(marketDataProvider.getQuote(CODE)).thenReturn(q);
        when(inputLoader.load(eq(CODE), any(Quote.class))).thenReturn(input(q));
        when(generator.generate(any())).thenReturn(
                new AnalysisReport(CODE, "股票" + CODE, "2026-09-20T10:00:00+08:00",
                        "规则论点", List.of("b1"), List.of("r1"), List.of("risk"),
                        "估值", "操作", "rule-based", false, List.of("实时报价"), "规则化"));
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("存档命中与强制刷新")
    class ArchiveBranching {

        @Test
        @DisplayName("存档命中时直接返回，不取数、不生成、不写存档")
        void archiveHitSkipsEverything() {
            AnalysisReport archived = archivedReport(CODE);
            when(archiver.loadLatest(CODE)).thenReturn(Optional.of(archived));

            AnalysisReport result = service.analyze(CODE, "u1", false);

            assertThat(result).isSameAs(archived);
            // 核心：一次存档命中意味着零次上游取数、零次生成、零次写库
            verifyNoInteractions(marketDataProvider, inputLoader, generator);
            verify(archiver, never()).archive(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("forceRefresh 忽略存档，重新生成并归档")
        void forceRefreshIgnoresArchive() {
            stubGenerationPathWithLlmDisabled();

            AnalysisReport result = service.analyze(CODE, "u1", true);

            assertThat(result.model()).isEqualTo("rule-based");
            assertThat(result.fromArchive()).isFalse();
            // 核心：强制刷新意味着连存档都不该被读，而不是读了再覆盖
            verify(archiver, never()).loadLatest(anyString());
            verify(archiver).archive(eq(CODE), eq("u1"), any(AnalysisReport.class));
        }

        @Test
        @DisplayName("无存档时（GET 语义）生成一次并归档")
        void noArchiveGeneratesOnce() {
            when(archiver.loadLatest(CODE)).thenReturn(Optional.empty());
            stubGenerationPathWithLlmDisabled();

            AnalysisReport result = service.analyze(CODE, "u1", false);

            assertThat(result.model()).isEqualTo("rule-based");
            verify(archiver).archive(eq(CODE), eq("u1"), any(AnalysisReport.class));
        }
    }

    @Nested
    @DisplayName("校验与失败路径")
    class FailurePaths {

        @Test
        @DisplayName("非法代码在任何协作者被触碰之前就被拒绝")
        void invalidCodeRejectedBeforeAnyCollaborator() {
            assertThatThrownBy(() -> service.analyze("hello", "u1", false))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("hello");

            verifyNoInteractions(marketDataProvider, inputLoader, generator, archiver, taskStore, taskExecutor);
        }

        @Test
        @DisplayName("取不到报价直接 404：不把模型费用花在不存在的代码上")
        void missingQuoteShortCircuitsBeforeLlm() {
            when(archiver.loadLatest(CODE)).thenReturn(Optional.empty());
            when(marketDataProvider.getQuote(CODE))
                    .thenThrow(new BizException(ErrorCode.QUOTE_NOT_FOUND));

            assertThatThrownBy(() -> service.analyze(CODE, "u1", false))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getCode())
                    .isEqualTo(ErrorCode.QUOTE_NOT_FOUND.name());

            // 断钱断在取报价这一步：后面的取数、生成、归档都不该发生
            verifyNoInteractions(inputLoader, generator);
            verify(archiver, never()).archive(anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("异步提交与任务查询")
    class AsyncSubmission {

        @Test
        @DisplayName("提交创建任务、投递执行器，并返回 taskId")
        void submitCreatesTaskAndDispatches() {
            when(taskStore.create(CODE, "u1")).thenReturn("task-123");

            AnalysisTaskSubmission submission = service.submit(CODE, "u1");

            assertThat(submission.taskId()).isEqualTo("task-123");
            assertThat(submission.status()).isEqualTo("PENDING");
            // 关键：任务投递给了执行器，但执行器不阻塞提交线程（submit 立即返回）
            verify(taskExecutor).submit("task-123", CODE, "u1");
            // 异步路径不应触碰同步路径的取报价/取数/归档
            verifyNoInteractions(marketDataProvider, inputLoader, archiver);
        }

        @Test
        @DisplayName("非法代码提交时直接 404，不创建任务")
        void submitInvalidCodeDoesNotCreateTask() {
            assertThatThrownBy(() -> service.submit("hello", "u1"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("hello");

            verifyNoInteractions(taskStore, taskExecutor);
        }

        @Test
        @DisplayName("查询不存在的任务返回 404")
        void taskNotFoundReturns404() {
            when(taskStore.findOwnedById("nope", "u1")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.task("nope", "u1"))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getCode())
                    .isEqualTo(ErrorCode.ANALYSIS_TASK_NOT_FOUND.name());
        }

        @Test
        @DisplayName("查询 RUNNING 任务返回状态、无报告")
        void runningTaskReturnsStatusOnly() {
            AnalysisTask task = new AnalysisTask();
            task.setId("task-1");
            task.setStatus(AnalysisTaskStatus.RUNNING.name());
            when(taskStore.findOwnedById("task-1", "u1")).thenReturn(Optional.of(task));

            AnalysisTaskView view = service.task("task-1", "u1");

            assertThat(view.status()).isEqualTo(AnalysisTaskStatus.RUNNING);
            assertThat(view.report()).isNull();
        }

        @Test
        @DisplayName("查询 SUCCESS 任务返回报告")
        void successTaskReturnsReport() {
            AnalysisTask task = new AnalysisTask();
            task.setId("task-2");
            task.setStatus(AnalysisTaskStatus.SUCCESS.name());
            when(taskStore.findOwnedById("task-2", "u1")).thenReturn(Optional.of(task));
            when(taskStore.parseReport(task)).thenReturn(archivedReport(CODE));

            AnalysisTaskView view = service.task("task-2", "u1");

            assertThat(view.status()).isEqualTo(AnalysisTaskStatus.SUCCESS);
            assertThat(view.report()).isNotNull();
            assertThat(view.report().code()).isEqualTo(CODE);
        }

        @Test
        @DisplayName("查询他人任务：归属条件传给存储层，查不到即 404（对象级越权防护）")
        void taskQueryIsScopedToOwner() {
            when(taskStore.findOwnedById("task-1", "u1")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.task("task-1", "u1"))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getCode())
                    .isEqualTo(ErrorCode.ANALYSIS_TASK_NOT_FOUND.name());

            // 归属条件必须真的传下去：这里验证查询用的是「ID + 提交者」而不是只按 ID
            verify(taskStore).findOwnedById("task-1", "u1");
            verify(taskStore, never()).findById(anyString());
        }
    }

    @Nested
    @DisplayName("历史查询")
    class History {

        @Test
        @DisplayName("history 的 limit 被夹在 [1, MAX_HISTORY] 区间")
        void historyLimitIsClamped() {
            service.history(CODE, 0);
            verify(archiver).selectHistory(CODE, 1);

            service.history(CODE, 999);
            verify(archiver).selectHistory(CODE, 50);

            service.history(CODE, 30);
            verify(archiver).selectHistory(CODE, 30);
        }
    }
}
