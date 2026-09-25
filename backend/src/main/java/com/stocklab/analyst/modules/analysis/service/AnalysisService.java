package com.stocklab.analyst.modules.analysis.service;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.StockCodes;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.analysis.dto.AnalysisReport;
import com.stocklab.analyst.modules.analysis.support.AiReportArchiver;
import com.stocklab.analyst.modules.analysis.support.AnalysisInput;
import com.stocklab.analyst.modules.analysis.support.AnalysisInputLoader;
import com.stocklab.analyst.modules.analysis.support.LlmJsonParser;
import com.stocklab.analyst.modules.analysis.support.PromptBuilder;
import com.stocklab.analyst.modules.analysis.support.RuleBasedReportGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static com.stocklab.analyst.modules.analysis.support.AnalysisLimits.MAX_HISTORY;
import static com.stocklab.analyst.modules.analysis.support.LlmJsonParser.readList;
import static com.stocklab.analyst.modules.analysis.support.LlmJsonParser.readText;
import static com.stocklab.analyst.modules.analysis.support.PromptBuilder.SYSTEM_PROMPT;

/**
 * AI 深度分析服务——<b>编排层</b>。
 *
 * <p>这个类只负责一件事：把「校验标的 → 读存档或取数 → 调模型或兜底 → 归档」
 * 这条主干串起来并决定分支。具体的取数、拼提示词、解析 JSON、生成兜底报告、
 * 读写存档分别由 {@code support} 包下的协作者承担。
 *
 * <p>拆分之前这个类是 950+ 行的「上帝类」，同时包含提示词工程、JSON 容错解析、
 * 规则化文案拼装、SQL 读写四类互不相干的逻辑。拆分的直接动机不是「好看」，
 * 而是提示词与兜底报告此前<b>完全没有测试覆盖</b>——它们和取数、归档纠缠在一起，
 * 想测就得启动整个 Spring 上下文。现在提示词与兜底报告是纯函数，
 * 「数据缺失时是否写明未获取到」「估算值是否被挡在结论之外」都能直接断言。
 *
 * <h2>相对原 FastAPI 版本的三处实质修正</h2>
 *
 * <h3>1. {@code ai_reports} 表从「死表」变成真正的存档</h3>
 * 原实现的 ORM 里定义了 {@code ai_reports}，但没有任何代码写入或读取它，
 * 每次请求都重新调用大模型计费。更糟的是 {@code GET /analysis/{code}}
 * ——一个语义上「获取最新报告」的读接口——同样会触发一次新的模型调用。
 * 本实现区分两类入口：
 * <ul>
 *   <li>{@code GET}：优先返回存档；没有存档时才生成一次并归档</li>
 *   <li>{@code POST}：显式要求重新生成</li>
 * </ul>
 * 响应中的 {@code fromArchive} 与 {@code generatedAt} 让前端能如实告知
 * 「这是 20 分钟前生成的报告」。读写细节见 {@link AiReportArchiver}。
 *
 * <h3>2. 把真实数据喂进提示词</h3>
 * 原实现的 prompt 要求模型输出 {@code valuation_note}（估值参考），
 * 但上下文里只有 60 日 K 线的粗略摘要，没有任何 PE / PB / ROE 数据，
 * 模型只能凭常识编一个数字。本实现把实时报价、区间统计、技术指标末值、
 * 真实财务指标全部结构化拼进 prompt。构造逻辑见 {@link PromptBuilder}。
 *
 * <h3>3. 降级不再隐藏</h3>
 * 大模型调用失败时不再抛 502 让页面空掉，而是返回规则化报告，
 * 但 {@code model=rule-based} 与 {@code notice} 会写清失败原因。
 * 规则化内容全部由真实数据推导，不含任何编造的具体数值，
 * 见 {@link RuleBasedReportGenerator}。
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private static final String AI_DISCLAIMER = "本分析由 AI 生成，不构成投资建议。";

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 输出带时区偏移的时间，前端不需要猜服务端时区。 */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final MarketDataProvider marketDataProvider;
    private final AnalysisInputLoader inputLoader;
    private final LlmClient llmClient;
    private final LlmJsonParser jsonParser;
    private final AiReportArchiver archiver;
    private final MeterRegistry meterRegistry;

    public AnalysisService(MarketDataProvider marketDataProvider,
                           AnalysisInputLoader inputLoader,
                           LlmClient llmClient,
                           LlmJsonParser jsonParser,
                           AiReportArchiver archiver,
                           MeterRegistry meterRegistry) {
        this.marketDataProvider = marketDataProvider;
        this.inputLoader = inputLoader;
        this.llmClient = llmClient;
        this.jsonParser = jsonParser;
        this.archiver = archiver;
        this.meterRegistry = meterRegistry;
    }

    // ==================================================================
    // 对外入口
    // ==================================================================

    /**
     * 生成或读取分析报告。
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
        AnalysisReport report = generate(input);
        archiver.archive(normalized, userId, report);
        return report;
    }

    /** 历史报告列表（按时间倒序）。 */
    public List<AnalysisReport> history(String code, int limit) {
        String normalized = normalizeCode(code);
        int capped = Math.min(Math.max(limit, 1), MAX_HISTORY);
        return archiver.selectHistory(normalized, capped);
    }

    // ==================================================================
    // 生成：大模型优先，失败降级规则化
    // ==================================================================

    /**
     * 生成分析报告。大模型优先，失败或未配置时降级为规则化报告。
     *
     * <h2>⚠️ 这里的大模型调用是<b>同步阻塞</b>的，不是异步任务</h2>
     * {@link #fromLlm} 会直接在当前线程（HTTP 请求线程）上调用
     * {@code LlmClient.complete}，并等待到 {@code app.llm.timeout}
     * （默认 45 秒）为止。项目里<b>没有</b>为 AI 生成建任务表、没有投递队列、
     * 没有异步回执，也没有断点续查——一次请求从头等到尾。
     *
     * <p>这个事实必须写在代码里，因为很容易被误认为「既然用了
     * {@code @EnableAsync} 和 {@code CompletableFuture}，AI 应该是异步的」。
     * 实际被并发的只有 {@link AnalysisInputLoader#load} 里那三次数据取数；
     * 大模型这一段是串行等待。
     *
     * <h3>当前为什么还能接受</h3>
     * 分析类接口都标了 {@code @RateLimit(RateLimitScope.STRICT)}
     * （默认 20 次/分/客户端），同时流量被限流器挡在门外，
     * 不会出现「200 个并发请求各占一个 Tomcat 线程等 45 秒」这种把线程池打满的形态。
     * 换句话说，限流器顺带兜住了线程耗尽的风险。
     *
     * <h3>什么时候必须改</h3>
     * 一旦出现以下任一情况，同步方案就不再成立，需要改成
     * 「提交任务 → 立即返回 taskId → 前端轮询或 WebSocket 回推」：
     * <ul>
     *   <li>分析接口的限流额度被调高，或它不再走严格桶；</li>
     *   <li>上游超时被调大，或引入了多轮对话式生成（单次耗时成倍上升）；</li>
     *   <li>需要支持「一次提交分析多只股票」这类批量入口。</li>
     * </ul>
     * 改造的落点很清楚：把 {@code generate} 抽成一个独立的执行器任务，
     * 结果按 {@code taskId} 落库或落 Redis，接口只负责投递与查询。
     * 到那一步之前，不建议为了「看起来是异步」而套一层
     * {@code @Async}——那只会把 45 秒的等待从请求线程挪到线程池，
     * 既不减少等待，又让超时和错误无处可回。
     */
    private AnalysisReport generate(AnalysisInput input) {
        if (!llmClient.enabled()) {
            return RuleBasedReportGenerator.generate(
                    input, "未配置大模型 API Key（app.llm.api-key）", now());
        }
        try {
            return fromLlm(input);
        } catch (BizException ex) {
            // 上游失败不应让整个页面空掉，但必须把失败原因显式带到响应里，
            // 否则用户会把规则化文案当成大模型分析。
            log.warn("AI 分析降级为规则化报告 code={} 原因={}：{}",
                    input.quote().code(), ex.getCode(), ex.getMessage());
            meterRegistry.counter("app.analysis.fallback", "reason", ex.getCode()).increment();
            return RuleBasedReportGenerator.generate(input, ex.getMessage(), now());
        } catch (RuntimeException ex) {
            log.error("AI 分析出现未预期异常 code={}", input.quote().code(), ex);
            meterRegistry.counter("app.analysis.fallback", "reason", "unexpected").increment();
            return RuleBasedReportGenerator.generate(input, "大模型调用出现未预期异常", now());
        }
    }

    private AnalysisReport fromLlm(AnalysisInput input) {
        String content = llmClient.complete(SYSTEM_PROMPT, PromptBuilder.buildUserPrompt(input));
        JsonNode root = jsonParser.parse(content);

        Quote quote = input.quote();
        return new AnalysisReport(
                quote.code(),
                quote.name(),
                now(),
                readText(root, "thesis", "模型未给出核心论点"),
                readList(root, "bullCase"),
                readList(root, "bearCase"),
                readList(root, "risks"),
                readText(root, "valuationNote", "模型未给出估值说明"),
                readText(root, "actionHint", "模型未给出操作提示"),
                llmClient.modelName(),
                false,
                input.basis(),
                AI_DISCLAIMER);
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

    private static String now() {
        return TIMESTAMP.format(Instant.now().atZone(SHANGHAI));
    }
}
