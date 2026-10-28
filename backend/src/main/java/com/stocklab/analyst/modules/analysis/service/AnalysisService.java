package com.stocklab.analyst.modules.analysis.service;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.StockCodes;
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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static com.stocklab.analyst.modules.analysis.support.AnalysisLimits.MAX_HISTORY;

/**
 * AI 深度分析服务——<b>编排层</b>。
 *
 * <p>这个类只负责一件事：把「校验标的 → 读存档或取数 → 生成 → 归档」
 * 这条主干串起来并决定分支。具体的取数、拼提示词、生成报告、解析 JSON、
 * 读写存档分别由 {@code support} 包下的协作者承担。
 *
 * <h2>同步入口与异步入口</h2>
 * <ul>
 *   <li>{@link #analyze} —— 同步（GET 存档语义）：有存档直接返回，否则生成一次。
 *       仍会同步等待大模型，适用于「读报告」场景（命中存档时零耗时）。</li>
 *   <li>{@link #submit} —— 异步（POST 语义）：校验代码 → 投递后台任务 → 立即返回
 *       taskId；真正耗时的大模型调用在 {@link AnalysisTaskExecutor} 的后台线程执行。
 *       前端轮询 {@link #task} 直到终态。这是消除「约 5 个并发分析请求吃掉大半
 *       Tomcat 线程池」的关键。</li>
 * </ul>
 *
 * <p>相对原 FastAPI 版本的三处实质修正（存档不再是死表、真实数据喂进提示词、
 * 降级不再隐藏）详见 {@link AnalysisGenerator} 与 {@link AiReportArchiver}。
 */
@Service
public class AnalysisService {

    private final MarketDataProvider marketDataProvider;
    private final AnalysisInputLoader inputLoader;
    private final AnalysisGenerator generator;
    private final AiReportArchiver archiver;
    private final AnalysisTaskStore taskStore;
    private final AnalysisTaskExecutor taskExecutor;

    public AnalysisService(MarketDataProvider marketDataProvider,
                           AnalysisInputLoader inputLoader,
                           AnalysisGenerator generator,
                           AiReportArchiver archiver,
                           AnalysisTaskStore taskStore,
                           AnalysisTaskExecutor taskExecutor) {
        this.marketDataProvider = marketDataProvider;
        this.inputLoader = inputLoader;
        this.generator = generator;
        this.archiver = archiver;
        this.taskStore = taskStore;
        this.taskExecutor = taskExecutor;
    }

    // ==================================================================
    // 同步入口
    // ==================================================================

    /**
     * 生成或读取分析报告（同步）。
     *
     * <p>读存档这一步带时效（{@code app.analysis.archive-max-age}，默认 6 小时）：
     * 过期的存档等同不存在，会重新生成。理由见 {@link AiReportArchiver} 的类注释——
     * 报告正文里写着「最新价 xxx」，把生成时刻的价格固化成了自然语言。
     *
     * @param code         股票代码（任意受支持写法，会归一化为 6 位数字）
     * @param userId       触发者；游客为 {@code null}，仅作审计字段
     * @param forceRefresh {@code true} 时忽略存档重新生成（对应 POST）
     */
    public AnalysisReport analyze(String code, String userId, boolean forceRefresh) {
        String normalized = normalizeCode(code);

        if (!forceRefresh) {
            Optional<AnalysisReport> archived = archiver.loadLatest(normalized);
            if (archived.isPresent()) {
                return archived.get();
            }
        }

        // 这一步同时承担「代码是否真实存在」的校验：取不到报价直接 404，
        // 不该把一次注定失败的模型调用费用花在一个不存在的代码上。
        Quote quote = marketDataProvider.getQuote(normalized);

        AnalysisInput input = inputLoader.load(normalized, quote);
        AnalysisReport report = generator.generate(input);
        archiver.archive(normalized, userId, report);
        return report;
    }

    // ==================================================================
    // 异步入口
    // ==================================================================

    /**
     * 提交异步分析任务，立即返回 taskId。
     *
     * <p>代码非法时在此处直接抛 404，不会创建任务——异步化只把「耗时的生成」
     * 挪到后台，「入参校验」仍然留在请求线程上同步完成，这样非法的提交
     * 能立刻得到明确反馈，而不是先给一个 taskId 再在轮询时才发现失败。
     */
    public AnalysisTaskSubmission submit(String code, String userId) {
        String normalized = normalizeCode(code);
        String taskId = taskStore.create(normalized, userId);
        taskExecutor.submit(taskId, normalized, userId);
        return new AnalysisTaskSubmission(taskId, AnalysisTaskStatus.PENDING.name());
    }

    /**
     * 查询异步任务状态。
     *
     * <p>任务不存在时抛 {@link ErrorCode#QUOTE_NOT_FOUND} 之外的新错误码
     * 不合适——这里复用「任务不存在」语义，用 404 让前端明确知道
     * taskId 无效（比如本地存储里残留的过期 ID）。
     *
     * <p>查询带 {@code userId} 归属条件：别人的任务对本请求而言等同于不存在，
     * 因此同样返回 404——既不泄露任务是否存在，也省去一次显式鉴权分支。
     *
     * @param userId 提交者；{@code null} 只匹配匿名任务
     */
    public AnalysisTaskView task(String taskId, String userId) {
        AnalysisTask task = taskStore.findOwnedById(taskId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.ANALYSIS_TASK_NOT_FOUND, "分析任务不存在 " + taskId));
        AnalysisTaskStatus status = AnalysisTaskStatus.valueOf(task.getStatus());
        if (status == AnalysisTaskStatus.SUCCESS) {
            AnalysisReport report = taskStore.parseReport(task);
            if (report == null) {
                // 反序列化失败：对外呈现为失败，避免前端拿到 null 报告误判
                return new AnalysisTaskView(taskId, AnalysisTaskStatus.FAILED, null, "报告数据损坏，请重新生成");
            }
            return new AnalysisTaskView(taskId, status, report, null);
        }
        if (status == AnalysisTaskStatus.FAILED) {
            return new AnalysisTaskView(taskId, status, null, task.getErrorMsg());
        }
        return new AnalysisTaskView(taskId, status, null, null);
    }

    /** 历史报告列表（按时间倒序）。 */
    public List<AnalysisReport> history(String code, int limit) {
        String normalized = normalizeCode(code);
        int capped = Math.min(Math.max(limit, 1), MAX_HISTORY);
        return archiver.selectHistory(normalized, capped);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static String normalizeCode(String raw) {
        StockCodes.ParsedCode parsed = StockCodes.parse(raw);
        if (parsed == null) {
            throw new BizException(ErrorCode.QUOTE_NOT_FOUND, "未找到股票代码 " + raw);
        }
        return parsed.code();
    }
}
