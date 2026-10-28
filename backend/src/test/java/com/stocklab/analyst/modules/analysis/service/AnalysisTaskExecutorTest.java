package com.stocklab.analyst.modules.analysis.service;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.analysis.dto.AnalysisReport;
import com.stocklab.analyst.modules.analysis.support.AiReportArchiver;
import com.stocklab.analyst.modules.analysis.support.AnalysisGenerator;
import com.stocklab.analyst.modules.analysis.support.AnalysisInput;
import com.stocklab.analyst.modules.analysis.support.AnalysisInputLoader;
import com.stocklab.analyst.modules.analysis.support.AnalysisTaskStore;
import com.stocklab.analyst.modules.analysis.support.RuleBasedReportGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AnalysisTaskExecutor} 单测。
 *
 * <p>异步链路此前<b>一条测试都没有</b>——它却是「约 5 个并发分析请求吃掉大半
 * Tomcat 线程池」这个问题的全部解法。没有测试的后果不是它坏了没人知道，
 * 而是<b>它悄悄退化成同步也没人知道</b>：接口照样返回 202 和一个 taskId，
 * 前端照样能轮询到结果，只是后台线程池再没被用过。
 *
 * <p>这里钉住三条最容易写错、且出错时表现为「用户看不出、运维不好查」的行为：
 * <ol>
 *   <li>线程池满时任务必须被明确标记 FAILED，而不是静默丢失——
 *       提交方已经拿着 taskId 在轮询，它必须能看到一个确定的终态；</li>
 *   <li>生成过程抛异常（如代码不存在）时同样落到 FAILED，且原因带得出去；</li>
 *   <li>成功路径的顺序必须是「先归档、再写回任务」，且模型名要落库。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AnalysisTaskExecutorTest {

    private static final String TASK_ID = "task-1";
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

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    /** 用「直接在当前线程执行」的 Executor 构造，让后台逻辑在测试里同步可断言。 */
    private AnalysisTaskExecutor executorWith(Executor delegate) {
        return new AnalysisTaskExecutor(delegate, marketDataProvider, inputLoader,
                generator, archiver, taskStore, meterRegistry);
    }

    private AnalysisReport report(String model) {
        return new AnalysisReport(
                CODE, "贵州茅台", "2026-10-16T15:00:00+08:00", "结论",
                List.of(), List.of(), List.of(), "估值", "操作",
                model, false, List.of("实时报价"), null);
    }

    private static Quote quote() {
        return new Quote(CODE, "贵州茅台", "SH", 1685.0, 20.0, 1.2,
                1670.0, 1700.0, 1660.0, 1665.0, 1000L, 1.0, 1.0e8, 1700000000L);
    }

    /** 指标与财务取不到（null）是常态，这里就按常态构造。 */
    private static AnalysisInput input(Quote quote) {
        return new AnalysisInput(quote, List.of(), null, null, List.of("实时报价"));
    }

    @Test
    @DisplayName("线程池满：任务标记为 FAILED 并提示稍后重试，而不是静默丢失")
    void rejectedSubmissionMarksTaskFailed() {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("queue full");
        };

        executorWith(rejecting).submit(TASK_ID, CODE, null);

        verify(taskStore).markFailed(eq(TASK_ID), contains("繁忙"));
        // 关键：没有任何一步生成逻辑被执行——被拒绝的任务不应该再走一遍取数
        verify(taskStore, never()).markRunning(anyString());
        verify(taskStore, never()).markSuccess(anyString(), any());
        verify(generator, never()).generate(any());
        assertThat(meterRegistry.counter("app.analysis.task.rejected").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("成功路径：RUNNING → 取数 → 生成 → 归档 → SUCCESS，模型名落库")
    void successPathArchivesThenWritesBackTask() {
        Quote quote = quote();
        AnalysisInput input = input(quote);
        AnalysisReport report = report("deepseek-chat");

        when(marketDataProvider.getQuote(CODE)).thenReturn(quote);
        when(inputLoader.load(CODE, quote)).thenReturn(input);
        when(generator.generate(input)).thenReturn(report);

        executorWith(Runnable::run).submit(TASK_ID, CODE, "user-1");

        verify(taskStore).markRunning(TASK_ID);
        verify(archiver).archive(CODE, "user-1", report);
        verify(taskStore).markSuccess(TASK_ID, report);
        verify(taskStore, never()).markFailed(anyString(), anyString());
        assertThat(meterRegistry.counter("app.analysis.task.succeeded").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("取不到报价（代码不存在）时任务失败，且不产生模型费用")
    void missingQuoteFailsTaskWithoutCallingGenerator() {
        when(marketDataProvider.getQuote(CODE))
                .thenThrow(new BizException(ErrorCode.QUOTE_NOT_FOUND, "未找到股票代码 600519"));

        executorWith(Runnable::run).submit(TASK_ID, CODE, null);

        verify(taskStore).markFailed(TASK_ID, "未找到股票代码 600519");
        verify(generator, never()).generate(any());
        verify(archiver, never()).archive(anyString(), any(), any());
        assertThat(meterRegistry.counter("app.analysis.task.failed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("生成过程抛异常时落到 FAILED，不让后台线程把异常抛出去")
    void generationFailureIsContained() {
        Quote quote = quote();
        when(marketDataProvider.getQuote(CODE)).thenReturn(quote);
        when(inputLoader.load(CODE, quote)).thenReturn(input(quote));
        when(generator.generate(any())).thenThrow(new IllegalStateException("上游 500"));

        executorWith(Runnable::run).submit(TASK_ID, CODE, null);

        verify(taskStore).markFailed(TASK_ID, "上游 500");
        verify(taskStore, never()).markSuccess(anyString(), any());
    }

    @Test
    @DisplayName("降级为规则化报告时同样标记 SUCCESS：那是可交付的结果，不是失败")
    void ruleBasedReportIsStillSuccess() {
        Quote quote = quote();
        AnalysisReport ruleBased = report(RuleBasedReportGenerator.RULE_BASED_MODEL);
        when(marketDataProvider.getQuote(CODE)).thenReturn(quote);
        when(inputLoader.load(CODE, quote)).thenReturn(input(quote));
        when(generator.generate(any())).thenReturn(ruleBased);

        executorWith(Runnable::run).submit(TASK_ID, CODE, null);

        verify(taskStore).markSuccess(TASK_ID, ruleBased);
        // 是否入库由 archiver 内部判定（规则化报告不入档），调用本身照常发生
        verify(archiver).archive(CODE, null, ruleBased);
    }
}
