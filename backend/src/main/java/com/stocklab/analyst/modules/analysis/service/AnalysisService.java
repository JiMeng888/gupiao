package com.stocklab.analyst.modules.analysis.service;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.StockCodes;
import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.MarketPeriod;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.analysis.dto.AnalysisReport;
import com.stocklab.analyst.modules.analysis.entity.AiReport;
import com.stocklab.analyst.modules.analysis.mapper.AiReportMapper;
import com.stocklab.analyst.modules.fundamental.dto.FundamentalOverview;
import com.stocklab.analyst.modules.fundamental.service.FundamentalService;
import com.stocklab.analyst.modules.indicator.dto.IndicatorName;
import com.stocklab.analyst.modules.indicator.dto.IndicatorResponse;
import com.stocklab.analyst.modules.indicator.service.IndicatorService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * AI 深度分析服务。
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
 * 「这是 20 分钟前生成的报告」。
 *
 * <h3>2. 把真实数据喂进提示词</h3>
 * 原实现的 prompt 要求模型输出 {@code valuation_note}（估值参考），
 * 但上下文里<b>只有 60 日 K 线的粗略摘要</b>，没有任何 PE / PB / ROE 数据。
 * 模型只能凭常识编一个「当前 PE 约 28x，行业均值约 32x」——
 * 而原实现的规则化降级报告里，这句话就是硬编码的常量。
 * 本实现把实时报价、区间统计、技术指标末值、真实财务指标全部结构化拼进 prompt，
 * 并在数据缺失时明确写「该项未获取到，不要推测」。
 *
 * <h3>3. 降级不再隐藏</h3>
 * 大模型调用失败时不再抛 502 让页面空掉，而是返回规则化报告，
 * 但 {@code model=rule-based} 与 {@code notice} 会写清失败原因。
 * 规则化内容全部由真实数据推导，<b>不含任何编造的具体数值</b>。
 *
 * <h2>安全边界</h2>
 * 拼进 prompt 的字符串全部来自上游接口（股票名来自行情源，数值来自财务接口），
 * 没有任何一段是用户自由文本——用户只能提供股票代码，且代码会先被
 * {@link StockCodes#parse} 校验成 6 位数字。这是刻意设计：
 * 一旦允许用户文本进入 prompt，就等于给出了一个提示注入面。
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    /** 喂给模型的 K 线天数。60 个交易日约等于一个季度，覆盖一次中期波段。 */
    private static final int CONTEXT_BAR_DAYS = 60;

    /** 指标计算使用的 K 线根数。需大于 60 才能让 MA60 有值。 */
    private static final int INDICATOR_BAR_LIMIT = 120;

    /** 单个观点数组最多保留的条数，防止模型输出失控。 */
    private static final int MAX_LIST_ITEMS = 6;

    /** 历史报告一次最多返回的条数。 */
    private static final int MAX_HISTORY = 50;

    private static final String RULE_BASED_MODEL = "rule-based";

    private static final String AI_DISCLAIMER = "本分析由 AI 生成，不构成投资建议。";

    private static final String RULE_DISCLAIMER =
            "本内容由规则化逻辑根据实时行情与财务数据生成（非大模型输出），不构成投资建议。";

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 输出带时区偏移的时间，前端不需要猜服务端时区。 */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final Set<IndicatorName> ANALYSIS_INDICATORS =
            Set.of(IndicatorName.MACD, IndicatorName.RSI, IndicatorName.BOLL,
                    IndicatorName.KDJ, IndicatorName.MA);

    private final MarketDataProvider marketDataProvider;
    private final IndicatorService indicatorService;
    private final FundamentalService fundamentalService;
    private final LlmClient llmClient;
    private final AiReportMapper aiReportMapper;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Executor fetchExecutor;

    public AnalysisService(MarketDataProvider marketDataProvider,
                           IndicatorService indicatorService,
                           FundamentalService fundamentalService,
                           LlmClient llmClient,
                           AiReportMapper aiReportMapper,
                           ObjectMapper objectMapper,
                           MeterRegistry meterRegistry,
                           @Qualifier("marketFetchExecutor") Executor fetchExecutor) {
        this.marketDataProvider = marketDataProvider;
        this.indicatorService = indicatorService;
        this.fundamentalService = fundamentalService;
        this.llmClient = llmClient;
        this.aiReportMapper = aiReportMapper;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.fetchExecutor = fetchExecutor;
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
            Optional<AnalysisReport> archived = loadLatest(normalized);
            if (archived.isPresent()) {
                return archived.get();
            }
        }

        // 这一步同时承担「代码是否真实存在」的校验：取不到报价直接 404，
        // 不该把一次注定失败的模型调用费用花在一个不存在的代码上。
        Quote quote = marketDataProvider.getQuote(normalized);

        AnalysisInput input = buildInput(normalized, quote);
        AnalysisReport report = generate(input);
        archive(normalized, userId, report);
        return report;
    }

    /** 历史报告列表（按时间倒序）。 */
    public List<AnalysisReport> history(String code, int limit) {
        String normalized = normalizeCode(code);
        int capped = Math.min(Math.max(limit, 1), MAX_HISTORY);

        List<AnalysisReport> result = new ArrayList<>();
        List<AiReport> rows;
        try {
            rows = aiReportMapper.selectHistoryByCode(normalized, capped);
        } catch (RuntimeException ex) {
            log.warn("查询历史报告失败 code={}：{}", normalized, ex.getMessage());
            return List.of();
        }
        for (AiReport row : rows) {
            AnalysisReport parsed = deserialize(row.getReportJson(), normalized);
            if (parsed != null) {
                result.add(withArchiveFlag(parsed, true));
            }
        }
        return List.copyOf(result);
    }

    // ==================================================================
    // 数据准备
    // ==================================================================

    /** 一次分析用到的全部输入。 */
    private record AnalysisInput(
            Quote quote,
            List<Bar> bars,
            IndicatorResponse indicators,
            FundamentalOverview fundamental,
            List<String> basis
    ) {
    }

    /**
     * 并行取齐三块数据。
     *
     * <p>串行取的话，模型还没开始工作就要先等 K 线、指标、财务三次往返；
     * 三者之间没有依赖，并行可以把这段等待压到最慢的那一项。
     *
     * <p>每一路都单独捕获异常：财务接口挂掉不应该让整份分析无法生成，
     * 只是 prompt 里会写明「财务数据未获取到」。
     *
     * <p>指标计算会再取一次 K 线（120 根，与这里的 60 根不是同一个缓存键）。
     * 这是刻意的：指标需要更长的窗口做 EMA 与 MA60 的预热，
     * 而走势摘要只需要 60 根，多算 60 根对计算本身没有意义。
     */
    private AnalysisInput buildInput(String code, Quote quote) {
        CompletableFuture<List<Bar>> barsFuture =
                CompletableFuture.supplyAsync(() -> safeBars(code), fetchExecutor);
        CompletableFuture<IndicatorResponse> indicatorsFuture =
                CompletableFuture.supplyAsync(() -> safeIndicators(code), fetchExecutor);
        CompletableFuture<FundamentalOverview> fundamentalFuture =
                CompletableFuture.supplyAsync(() -> safeFundamental(code), fetchExecutor);

        List<Bar> bars = barsFuture.join();
        IndicatorResponse indicators = indicatorsFuture.join();
        FundamentalOverview fundamental = fundamentalFuture.join();

        List<String> basis = new ArrayList<>(4);
        basis.add("实时报价");
        if (!bars.isEmpty()) {
            basis.add("近 " + bars.size() + " 个交易日 K 线");
        }
        if (indicators != null) {
            basis.add("技术指标 MACD / RSI / BOLL / KDJ / MA");
        }
        if (fundamental != null) {
            basis.add("财务指标 PE / PB / ROE / 毛利率 / 资产负债率"
                    + ("estimated".equals(fundamental.dataSource()) ? "（估算值）" : ""));
        }

        return new AnalysisInput(quote, bars, indicators, fundamental, List.copyOf(basis));
    }

    private List<Bar> safeBars(String code) {
        try {
            List<Bar> bars = marketDataProvider.getBars(code, MarketPeriod.DAILY, CONTEXT_BAR_DAYS);
            return bars == null ? List.of() : bars;
        } catch (RuntimeException ex) {
            log.debug("分析取 K 线失败 code={}：{}", code, ex.getMessage());
            return List.of();
        }
    }

    private IndicatorResponse safeIndicators(String code) {
        try {
            return indicatorService.compute(code, ANALYSIS_INDICATORS, INDICATOR_BAR_LIMIT);
        } catch (RuntimeException ex) {
            log.debug("分析取技术指标失败 code={}：{}", code, ex.getMessage());
            return null;
        }
    }

    private FundamentalOverview safeFundamental(String code) {
        try {
            return fundamentalService.getOverview(code);
        } catch (RuntimeException ex) {
            log.debug("分析取财务数据失败 code={}：{}", code, ex.getMessage());
            return null;
        }
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
     * 实际被并发的只有 {@link #buildInput} 里那三次数据取数；
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
            return ruleBased(input, "未配置大模型 API Key（app.llm.api-key）");
        }
        try {
            return fromLlm(input);
        } catch (BizException ex) {
            // 上游失败不应让整个页面空掉，但必须把失败原因显式带到响应里，
            // 否则用户会把规则化文案当成大模型分析。
            log.warn("AI 分析降级为规则化报告 code={} 原因={}：{}",
                    input.quote().code(), ex.getCode(), ex.getMessage());
            meterRegistry.counter("app.analysis.fallback", "reason", ex.getCode()).increment();
            return ruleBased(input, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("AI 分析出现未预期异常 code={}", input.quote().code(), ex);
            meterRegistry.counter("app.analysis.fallback", "reason", "unexpected").increment();
            return ruleBased(input, "大模型调用出现未预期异常");
        }
    }

    private AnalysisReport fromLlm(AnalysisInput input) {
        String content = llmClient.complete(SYSTEM_PROMPT, buildUserPrompt(input));
        JsonNode root = parseJson(content);

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

    /** 系统提示词：约束模型的输出边界与格式。 */
    private static final String SYSTEM_PROMPT = """
            你是一名严谨的 A 股证券分析师。必须遵守以下规则：
            1. 只能基于用户消息中提供的数据得出结论，不得引入未提供的任何数据\
            （例如历史财务数据、行业增速、机构评级、股东变动、政策细节）。
            2. 若某项数据被标注为「未获取」，你必须明确指出该项数据缺失，禁止推测或编造具体数值。
            3. 使用中性、可验证的表述，避免「有望」「或将」这类无信息量的措辞，不使用夸张用语。
            4. 严格输出 JSON 对象，不要 markdown 代码块，不要 JSON 之外的任何文字。
            """;

    /**
     * 用户提示词：数据 + 任务 + 输出约束。
     *
     * <p>这一段是整套提示工程的核心：原实现要求模型输出估值结论却不给估值数据，
     * 等于邀请模型编造。因此这里的顺序是「先把数据摆全，再要求结论」，
     * 并且对缺失项显式写「未获取到」。
     */
    private String buildUserPrompt(AnalysisInput input) {
        Quote quote = input.quote();
        StringBuilder sb = new StringBuilder(2048);

        sb.append("## 标的\n")
                .append("- 名称：").append(quote.name())
                .append("（").append(quote.code()).append("，").append(quote.exchange()).append("）\n");

        sb.append("\n## 实时行情\n")
                .append("- 现价 ").append(num(quote.price()))
                .append("，涨跌 ").append(signed(quote.change()))
                .append("（").append(signed(quote.changePct())).append("%）\n")
                .append("- 今开 ").append(num(quote.open()))
                .append(" / 最高 ").append(num(quote.high()))
                .append(" / 最低 ").append(num(quote.low()))
                .append(" / 昨收 ").append(num(quote.prevClose())).append("\n")
                .append("- 成交量 ").append(quote.volume()).append(" 手");
        if (quote.turnover() > 0) {
            sb.append(" / 换手率 ").append(num(quote.turnover())).append("%");
        }
        if (quote.amount() != null) {
            sb.append(" / 成交额 ").append(num(quote.amount() / 100_000_000.0)).append(" 亿");
        }
        sb.append("\n");

        sb.append("\n## 近期走势\n").append(barsBlock(input.bars()));
        sb.append("\n## 技术指标（最近一日）\n").append(indicatorBlock(input.indicators()));
        sb.append("\n## 财务指标\n").append(fundamentalBlock(input.fundamental()));

        sb.append("""

                ## 输出要求
                严格输出如下 JSON 结构，不要包含其它字段：
                {
                  "thesis": "核心论点，不超过 150 字",
                  "bullCase": ["看多逻辑，2 到 4 项，每项不超过 40 字"],
                  "bearCase": ["看空逻辑，2 到 4 项，每项不超过 40 字"],
                  "risks": ["风险因素，2 到 4 项，每项不超过 40 字"],
                  "valuationNote": "估值参考，不超过 60 字；若估值数据未获取则写明数据缺失",
                  "actionHint": "操作层面提示，不超过 60 字"
                }
                """);
        return sb.toString();
    }

    /** 走势摘要。数据为空时返回「未获取到」而不是留白，避免模型自行补全。 */
    private String barsBlock(List<Bar> bars) {
        if (bars.isEmpty()) {
            return "- 未获取到 K 线数据，请勿对走势做出任何判断\n";
        }
        double first = bars.get(0).close();
        double last = bars.get(bars.size() - 1).close();
        double high = bars.get(0).high();
        double low = bars.get(0).low();
        for (Bar bar : bars) {
            high = Math.max(high, bar.high());
            low = Math.min(low, bar.low());
        }
        double changePct = first == 0 ? 0 : (last - first) / first * 100;
        int position = percentile(last, low, high);

        StringBuilder sb = new StringBuilder(256);
        sb.append("- 区间首末价 ").append(num(first)).append(" → ").append(num(last))
                .append("（").append(signed(changePct)).append("%）\n")
                .append("- 区间最高 ").append(num(high)).append(" / 最低 ").append(num(low))
                .append("，当前价处于区间 ").append(position).append("% 分位\n")
                .append("- 样本区间 ").append(bars.get(0).date()).append(" 至 ")
                .append(bars.get(bars.size() - 1).date()).append("\n");

        int recentCount = Math.min(10, bars.size());
        List<Bar> recent = bars.subList(bars.size() - recentCount, bars.size());
        sb.append("- 近 ").append(recentCount).append(" 个交易日收盘：");
        for (int i = 0; i < recent.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(num(recent.get(i).close()));
        }
        sb.append("\n");
        return sb.toString();
    }

    private String indicatorBlock(IndicatorResponse indicators) {
        if (indicators == null) {
            return "- 未获取到技术指标数据，请勿对指标形态做出判断\n";
        }
        StringBuilder sb = new StringBuilder(512);

        if (indicators.macd() != null) {
            sb.append("- MACD：DIF ").append(num(last(indicators.macd().dif())))
                    .append(" / DEA ").append(num(last(indicators.macd().dea())))
                    .append(" / 柱 ").append(num(last(indicators.macd().hist()))).append("\n");
        }
        if (indicators.rsi() != null) {
            sb.append("- RSI(14)：").append(num(last(indicators.rsi()))).append("\n");
        }
        if (indicators.boll() != null) {
            sb.append("- BOLL：上轨 ").append(num(last(indicators.boll().upper())))
                    .append(" / 中轨 ").append(num(last(indicators.boll().mid())))
                    .append(" / 下轨 ").append(num(last(indicators.boll().lower()))).append("\n");
        }
        if (indicators.kdj() != null) {
            sb.append("- KDJ：K ").append(num(last(indicators.kdj().k())))
                    .append(" / D ").append(num(last(indicators.kdj().d())))
                    .append(" / J ").append(num(last(indicators.kdj().j()))).append("\n");
        }
        if (indicators.ma() != null) {
            sb.append("- 均线：MA5 ").append(num(last(indicators.ma().ma5())))
                    .append(" / MA10 ").append(num(last(indicators.ma().ma10())))
                    .append(" / MA20 ").append(num(last(indicators.ma().ma20())))
                    .append(" / MA60 ").append(num(last(indicators.ma().ma60()))).append("\n");
        }
        // 把服务端的形态解读一并给模型，避免它对金叉 / 超买这类形态判断出错
        Map<String, String> signals = indicators.signals();
        if (signals != null && !signals.isEmpty()) {
            sb.append("- 形态解读：");
            boolean first = true;
            for (Map.Entry<String, String> entry : signals.entrySet()) {
                if (!first) {
                    sb.append("；");
                }
                sb.append(entry.getValue());
                first = false;
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String fundamentalBlock(FundamentalOverview f) {
        if (f == null) {
            return "- 未获取到财务与估值数据，请勿对估值水平做出判断\n";
        }
        StringBuilder sb = new StringBuilder(384);
        if ("estimated".equals(f.dataSource())) {
            sb.append("- 注意：以下财务数据为估算值，不是真实披露数据，只能说明数据缺失，不可作为结论依据\n");
        }

        sb.append("- 估值：");
        appendMetric(sb, "PE(TTM)", f.peTtm());
        appendMetric(sb, "PE(动态)", f.pe());
        appendMetric(sb, "PE(静态)", f.peStatic());
        appendMetric(sb, "PB", f.pb());
        if (allNull(f.peTtm(), f.pe(), f.peStatic(), f.pb())) {
            sb.append("未获取到");
        }
        sb.append("\n");

        sb.append("- 盈利能力：");
        appendMetric(sb, "ROE", f.roe(), "%");
        appendMetric(sb, "净利率", f.netMargin(), "%");
        if (Boolean.TRUE.equals(f.grossMarginApplicable())) {
            appendMetric(sb, "毛利率", f.grossMargin(), "%");
        } else {
            sb.append("毛利率不适用（金融类） ");
        }
        sb.append("\n");

        sb.append("- 财务结构：");
        appendMetric(sb, "资产负债率", f.debtRatio(), "%");
        appendMetric(sb, "每股净资产", f.bps());
        if (f.revenue() != null) {
            sb.append("营业收入 ").append(num(f.revenue() / 100_000_000.0)).append(" 亿 ");
        }
        if (f.netProfit() != null) {
            sb.append("归母净利润 ").append(num(f.netProfit() / 100_000_000.0)).append(" 亿 ");
        }
        sb.append("\n");

        if (f.industry() != null && !f.industry().isBlank()) {
            sb.append("- 行业：").append(f.industry());
            if (f.industryPe() != null) {
                sb.append("，行业平均 PE ").append(num(f.industryPe()));
            }
            if (f.pePremiumToIndustry() != null) {
                sb.append("，个股相对行业")
                        .append(f.pePremiumToIndustry() >= 0 ? "溢价 " : "折价 ")
                        .append(num(Math.abs(f.pePremiumToIndustry()))).append("%");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==================================================================
    // 规则化降级报告
    // ==================================================================

    /**
     * 规则化报告。
     *
     * <p>原实现的 {@code _mock_report} 是一段与标的无关的固定文案
     * （算了 {@code up = change_pct >= 0} 却没用在任何地方，
     * 估值说明写死「当前 PE 约 28x，行业均值约 32x」）。
     * 这里每一条结论都由本次真实取到的数据推导，取不到就明说取不到。
     */
    private AnalysisReport ruleBased(AnalysisInput input, String reason) {
        Quote quote = input.quote();
        List<Bar> bars = input.bars();
        IndicatorResponse indicators = input.indicators();
        FundamentalOverview f = input.fundamental();

        Double rsi = indicators != null && indicators.rsi() != null ? last(indicators.rsi()) : null;
        Double ma20 = indicators != null && indicators.ma() != null ? last(indicators.ma().ma20()) : null;

        Double periodChange = null;
        Integer position = null;
        if (!bars.isEmpty()) {
            double first = bars.get(0).close();
            double close = bars.get(bars.size() - 1).close();
            double high = bars.get(0).high();
            double low = bars.get(0).low();
            for (Bar bar : bars) {
                high = Math.max(high, bar.high());
                low = Math.min(low, bar.low());
            }
            periodChange = first == 0 ? 0.0 : (close - first) / first * 100;
            position = percentile(close, low, high);
        }

        return new AnalysisReport(
                quote.code(),
                quote.name(),
                now(),
                ruleThesis(quote, f, periodChange, position),
                ruleBullCase(quote, f, rsi, ma20, periodChange),
                ruleBearCase(f, rsi, position, periodChange),
                genericRisks(),
                ruleValuationNote(f),
                "以上为规则化数据的客观归纳，不含方向性判断。配置大模型 API Key 后可获得结合走势与估值的综合结论。",
                RULE_BASED_MODEL,
                false,
                input.basis(),
                RULE_DISCLAIMER + " 未调用大模型的原因：" + reason + "。");
    }

    private String ruleThesis(Quote quote, FundamentalOverview f,
                              Double periodChange, Integer position) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(quote.name()).append("（").append(quote.code()).append("）最新价 ")
                .append(num(quote.price())).append("，当日 ")
                .append(signed(quote.changePct())).append("%");
        if (periodChange != null) {
            sb.append("；近 ").append(CONTEXT_BAR_DAYS).append(" 个交易日累计 ")
                    .append(signed(periodChange)).append("%");
            if (position != null) {
                sb.append("，处于区间 ").append(position).append("% 分位");
            }
        } else {
            sb.append("；未获取到 K 线数据，无法评估区间走势");
        }
        sb.append("。");
        if (f != null && "estimated".equals(f.dataSource())) {
            sb.append("财务数据为估算值，不可作为判断依据。");
        }
        return sb.toString();
    }

    private List<String> ruleBullCase(Quote quote, FundamentalOverview f,
                                      Double rsi, Double ma20, Double periodChange) {
        List<String> items = new ArrayList<>(4);
        if (periodChange != null && periodChange > 0) {
            items.add("近 " + CONTEXT_BAR_DAYS + " 个交易日累计上涨 "
                    + num(periodChange) + "%，中期走势偏强");
        }
        if (ma20 != null && quote.price() > ma20) {
            items.add("现价站上 MA20（" + num(ma20) + "），中期均线支撑有效");
        }
        if (rsi != null && rsi < 50) {
            items.add("RSI(14) 为 " + num(rsi) + "，未进入超买区间");
        }
        if (f != null && isReal(f) && f.roe() != null && f.roe() >= 15) {
            items.add("ROE " + num(f.roe()) + "%，盈利能力处于较高水平");
        }
        if (f != null && isReal(f) && f.peTtm() != null && f.industryPe() != null
                && f.industryPe() > 0 && f.peTtm() < f.industryPe()) {
            items.add("PE(TTM) " + num(f.peTtm()) + " 低于行业均值 "
                    + num(f.industryPe()) + "，估值相对折价");
        }
        if (f != null && isReal(f) && f.debtRatio() != null && f.debtRatio() < 40) {
            items.add("资产负债率 " + num(f.debtRatio()) + "%，财务结构稳健");
        }
        if (items.isEmpty()) {
            items.add("本次可用数据未呈现明确的多头逻辑，需补充数据后重新评估");
        }
        return List.copyOf(items.size() > MAX_LIST_ITEMS ? items.subList(0, MAX_LIST_ITEMS) : items);
    }

    private List<String> ruleBearCase(FundamentalOverview f, Double rsi,
                                      Integer position, Double periodChange) {
        List<String> items = new ArrayList<>(4);
        if (periodChange != null && periodChange < 0) {
            items.add("近 " + CONTEXT_BAR_DAYS + " 个交易日累计下跌 "
                    + num(Math.abs(periodChange)) + "%，中期走势偏弱");
        }
        if (rsi != null && rsi > 70) {
            items.add("RSI(14) 为 " + num(rsi) + "，处于超买区间，短期回调风险上升");
        }
        if (position != null && position > 80) {
            items.add("现价处于区间 " + position + "% 分位，接近区间上沿");
        }
        if (f != null && isReal(f) && f.peTtm() != null && f.industryPe() != null
                && f.industryPe() > 0 && f.peTtm() > f.industryPe()) {
            items.add("PE(TTM) " + num(f.peTtm()) + " 高于行业均值 "
                    + num(f.industryPe()) + "，估值相对溢价");
        }
        if (f != null && isReal(f) && f.debtRatio() != null && f.debtRatio() >= 70) {
            items.add("资产负债率 " + num(f.debtRatio()) + "%，杠杆水平偏高");
        }
        if (items.isEmpty()) {
            items.add("本次可用数据未呈现明确的空头逻辑，需补充数据后重新评估");
        }
        return List.copyOf(items.size() > MAX_LIST_ITEMS ? items.subList(0, MAX_LIST_ITEMS) : items);
    }

    /**
     * 通用风险。
     *
     * <p>刻意保持「通用」：规则化逻辑没有能力识别公司特有的风险事件，
     * 写死一个具体的「某产品销量下滑风险」就是编造。这里列出的是
     * 任何个股都适用的系统性风险，措辞上不涉及具体事实主张。
     */
    private static List<String> genericRisks() {
        return List.of(
                "市场整体波动与流动性风险",
                "所属行业景气度变化风险",
                "公司经营与业绩不及预期风险",
                "规则化分析无法识别公司特有风险事件");
    }

    private String ruleValuationNote(FundamentalOverview f) {
        if (f == null || f.peTtm() == null) {
            return "未获取到估值数据，不做估值判断";
        }
        StringBuilder sb = new StringBuilder(96);
        sb.append("PE(TTM) ").append(num(f.peTtm()));
        if (f.pb() != null) {
            sb.append("，PB ").append(num(f.pb()));
        }
        if (isReal(f) && f.industryPe() != null && f.industryPe() > 0) {
            sb.append("；行业均值 ").append(num(f.industryPe()));
        }
        if (!isReal(f)) {
            sb.append("（估算值，非真实数据）");
        }
        return sb.toString();
    }

    /** 只有真实披露的数据才允许进入结论性表述。 */
    private static boolean isReal(FundamentalOverview f) {
        return "eastmoney".equals(f.dataSource());
    }

    // ==================================================================
    // 存档
    // ==================================================================

    private Optional<AnalysisReport> loadLatest(String code) {
        AiReport row;
        try {
            row = aiReportMapper.selectLatestByCode(code);
        } catch (RuntimeException ex) {
            log.warn("读取最新报告失败 code={}：{}", code, ex.getMessage());
            return Optional.empty();
        }
        if (row == null || row.getReportJson() == null || row.getReportJson().isBlank()) {
            return Optional.empty();
        }
        AnalysisReport parsed = deserialize(row.getReportJson(), code);
        return parsed == null ? Optional.empty() : Optional.of(withArchiveFlag(parsed, true));
    }

    /**
     * 反序列化存档报告。
     *
     * <p>反序列化失败必须吞掉并返回 {@code null}：报告结构在迭代中会演进，
     * 历史行可能不再兼容。此时正确的行为是「当作没有存档，重新生成」，
     * 而不是让整个接口 500。
     */
    private AnalysisReport deserialize(String json, String code) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, AnalysisReport.class);
        } catch (RuntimeException ex) {
            log.warn("历史报告反序列化失败 code={}，将忽略该条记录：{}", code, ex.getMessage());
            return null;
        }
    }

    private void archive(String code, String userId, AnalysisReport report) {
        try {
            AiReport entity = new AiReport();
            entity.setUserId(userId);
            entity.setCode(code);
            entity.setModel(report.model());
            entity.setReportJson(objectMapper.writeValueAsString(report));
            aiReportMapper.insert(entity);
        } catch (RuntimeException ex) {
            // 归档失败不该影响已经生成好的报告
            log.warn("AI 报告归档失败 code={}：{}", code, ex.getMessage());
        }
    }

    /**
     * 拷贝报告并改写 {@code fromArchive} 标记。
     *
     * <p>record 是不可变的，因此只能重建。这里显式列出每个字段而不用反射，
     * 好处是未来新增字段时编译器会强制你在这里做决定。
     */
    private static AnalysisReport withArchiveFlag(AnalysisReport r, boolean fromArchive) {
        if (r.fromArchive() == fromArchive) {
            return r;
        }
        return new AnalysisReport(
                r.code(), r.name(), r.generatedAt(), r.thesis(),
                r.bullCase(), r.bearCase(), r.risks(),
                r.valuationNote(), r.actionHint(), r.model(),
                fromArchive, r.dataBasis(), r.notice());
    }

    // ==================================================================
    // JSON 解析
    // ==================================================================

    /**
     * 从模型输出中解析 JSON。
     *
     * <p>即使开了 {@code response_format=json_object}，也不是所有服务商都会严格遵守
     * （尤其千问的部分模型），常见偏差是包一层 markdown 围栏或在 JSON 前后
     * 加一段「好的，以下是分析：」。因此做三级尝试：
     * <ol>
     *   <li>直接解析</li>
     *   <li>剥掉围栏后解析</li>
     *   <li>截取第一个 <code>{</code> 到最后一个 <code>}</code> 之间解析</li>
     * </ol>
     *
     * <p>原实现的围栏剥离用 {@code text.strip("`")}——这是按<b>字符集</b>剥离而非按前缀，
     * 若 JSON 内含反引号会被误伤。这里改为显式判断前缀并成对剥离。
     *
     * <p>三级都失败时抛 {@code LLM_UPSTREAM_ERROR}，由上层统一降级为规则化报告。
     */
    private JsonNode parseJson(String content) {
        String text = content.trim();

        JsonNode node = tryParse(text);
        if (node == null && text.startsWith("```")) {
            node = tryParse(stripCodeFence(text));
        }
        if (node == null) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                node = tryParse(text.substring(start, end + 1));
            }
        }
        if (node == null || !node.isObject()) {
            throw new BizException(ErrorCode.LLM_UPSTREAM_ERROR,
                    "模型输出无法解析为约定的 JSON 结构");
        }
        return node;
    }

    private JsonNode tryParse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String stripCodeFence(String text) {
        String result = text;
        if (result.startsWith("```")) {
            result = result.substring(3);
            if (result.startsWith("json") || result.startsWith("JSON")) {
                result = result.substring(4);
            }
            if (result.endsWith("```")) {
                result = result.substring(0, result.length() - 3);
            }
        }
        return result.trim();
    }

    private static String readText(JsonNode root, String field, String fallback) {
        JsonNode value = root.path(field);
        if (value.isString()) {
            String text = value.asString().trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return fallback;
    }

    /**
     * 读取字符串数组。
     *
     * <p>做三件事：过滤非字符串项、去重、截断到 {@value #MAX_LIST_ITEMS} 条。
     * 模型偶尔会把数组写成一个字符串或塞进十几条，直接透传会把前端布局撑坏。
     */
    private static List<String> readList(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isArray()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isString()) {
                String text = item.asString().trim();
                if (!text.isEmpty() && !items.contains(text)) {
                    items.add(text);
                }
            }
            if (items.size() >= MAX_LIST_ITEMS) {
                break;
            }
        }
        return List.copyOf(items);
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

    private static Double last(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(values.size() - 1);
    }

    /** 当前价在区间中的百分位（0=区间最低，100=区间最高）。 */
    private static int percentile(double value, double low, double high) {
        if (high <= low) {
            return 50;
        }
        double ratio = (value - low) / (high - low) * 100;
        return (int) Math.round(Math.max(0, Math.min(100, ratio)));
    }

    private static void appendMetric(StringBuilder sb, String label, Double value) {
        appendMetric(sb, label, value, "");
    }

    private static void appendMetric(StringBuilder sb, String label, Double value, String unit) {
        if (value != null) {
            sb.append(label).append(" ").append(num(value)).append(unit).append(" ");
        }
    }

    private static boolean allNull(Double... values) {
        for (Double value : values) {
            if (value != null) {
                return false;
            }
        }
        return true;
    }

    private static String num(double value) {
        return String.format("%.2f", value);
    }

    private static String num(Double value) {
        return value == null ? "未获取到" : String.format("%.2f", value);
    }

    private static String signed(double value) {
        return String.format("%+.2f", value);
    }
}
