package com.stocklab.analyst.modules.analysis.service;

import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.analysis.dto.AnalysisReport;
import com.stocklab.analyst.modules.analysis.support.AiReportArchiver;
import com.stocklab.analyst.modules.analysis.support.AnalysisGenerator;
import com.stocklab.analyst.modules.analysis.support.AnalysisInput;
import com.stocklab.analyst.modules.analysis.support.AnalysisInputLoader;
import com.stocklab.analyst.modules.analysis.support.AnalysisTaskStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * AI 分析异步任务执行器——后台真正「跑一次生成」的地方。
 *
 * <h2>它解决的核心问题</h2>
 * 异步化之前，{@code POST /analysis/{code}} 在 HTTP 请求线程上同步等待大模型
 * 最长 45 秒，约 5 个并发就能吃掉大半 Tomcat 线程池余量。现在请求线程只负责
 * 投递任务，这个执行器在专用线程池里完成「取数 → 调模型/兜底 → 归档 → 写回任务」，
 * 全程不占用任何 Tomcat 工作线程。
 *
 * <h2>线程池满时的语义</h2>
 * 拒绝策略是 {@code AbortPolicy}（见 {@code AsyncConfig#analysisTaskThreadPool}），
 * 满则抛 {@link RejectedExecutionException}。这里捕获后把任务标记为 FAILED 并
 * 提示「稍后重试」，而不是让投递它的那次 HTTP 请求替它同步等 40 秒——
 * 那样等于异步化白做。
 *
 * <h2>为什么不用 {@code @Async}</h2>
 * {@code @Async} 的返回值为 {@code void} 时异常会静默吞掉，为 {@code Future} 时
 * 又要求调用方等待；这里需要的是「把异常显式写回任务状态」，用显式的
 * {@code Executor#execute} + 内部 try/catch 最直白，也不会和 Spring 代理
 * 的可见性细节纠缠。
 */
@Component
public class AnalysisTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskExecutor.class);

    private final Executor executor;
    private final MarketDataProvider marketDataProvider;
    private final AnalysisInputLoader inputLoader;
    private final AnalysisGenerator generator;
    private final AiReportArchiver archiver;
    private final AnalysisTaskStore taskStore;
    private final MeterRegistry meterRegistry;

    public AnalysisTaskExecutor(@Qualifier("analysisTaskThreadPool") Executor executor,
                                MarketDataProvider marketDataProvider,
                                AnalysisInputLoader inputLoader,
                                AnalysisGenerator generator,
                                AiReportArchiver archiver,
                                AnalysisTaskStore taskStore,
                                MeterRegistry meterRegistry) {
        this.executor = executor;
        this.marketDataProvider = marketDataProvider;
        this.inputLoader = inputLoader;
        this.generator = generator;
        this.archiver = archiver;
        this.taskStore = taskStore;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 投递一个分析任务。立即返回，不等待执行结果。
     *
     * <p>投递失败（线程池满）时任务被标记为 FAILED 而不是静默丢失——
     * 提交方已经拿到了 taskId，轮询时必须能看到一个确定的终态。
     */
    public void submit(String taskId, String code, String userId) {
        try {
            executor.execute(() -> run(taskId, code, userId));
        } catch (RejectedExecutionException ex) {
            log.warn("分析任务 {} 被拒绝（线程池已满）：{}", taskId, ex.getMessage());
            taskStore.markFailed(taskId, "分析服务繁忙，请稍后重试");
            meterRegistry.counter("app.analysis.task.rejected").increment();
        }
    }

    // ==================================================================
    // 后台执行
    // ==================================================================

    private void run(String taskId, String code, String userId) {
        taskStore.markRunning(taskId);
        meterRegistry.counter("app.analysis.task.started").increment();
        long start = System.currentTimeMillis();

        try {
            // 取报价这一步同时承担「代码是否存在」的校验：不存在直接失败，
            // 不该把一次注定失败的模型费用花在不存在的代码上
            Quote quote = marketDataProvider.getQuote(code);
            AnalysisInput input = inputLoader.load(code, quote);

            // 复用 AnalysisGenerator 的生成逻辑（大模型优先，失败降级规则化），
            // 保证同步/异步两条路径产出完全一致的报告
            AnalysisReport report = generator.generate(input);

            // 先归档（复用 ai_reports 的按 code 复用语义），再写回任务
            archiver.archive(code, userId, report);
            taskStore.markSuccess(taskId, report);

            meterRegistry.counter("app.analysis.task.succeeded").increment();
            log.info("分析任务 {} 完成 code={} model={} 耗时={}ms",
                    taskId, code, report.model(), System.currentTimeMillis() - start);
        } catch (RuntimeException ex) {
            log.warn("分析任务 {} 失败 code={}：{}", taskId, code, ex.getMessage());
            taskStore.markFailed(taskId, ex.getMessage() == null ? "分析失败" : ex.getMessage());
            meterRegistry.counter("app.analysis.task.failed").increment();
        }
    }
}
