package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.analysis.dto.AnalysisReport;
import com.stocklab.analyst.modules.analysis.service.LlmClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static com.stocklab.analyst.modules.analysis.support.LlmJsonParser.readList;
import static com.stocklab.analyst.modules.analysis.support.LlmJsonParser.readText;
import static com.stocklab.analyst.modules.analysis.support.PromptBuilder.SYSTEM_PROMPT;

/**
 * 分析报告的生成器——大模型优先，失败降级规则化。
 *
 * <p>从 {@code AnalysisService} 拆出，是为了让「同步分析」与「异步任务执行器」
 * 复用同一段生成逻辑，而不必让执行器反过来依赖整个编排服务（那会形成循环依赖）。
 * 拆出后它是一个无状态的 Bean：输入 {@link AnalysisInput}，输出 {@link AnalysisReport}。
 *
 * <p>关于同步阻塞的说明见 {@link com.stocklab.analyst.modules.analysis.service.AnalysisTaskExecutor}：
 * 生成器本身仍是同步调用大模型的，异步化发生在<b>编排层</b>——由执行器把这段
 * 同步等待挪到后台线程，而不是把它变成真正的非阻塞 I/O。
 */
@Component
public class AnalysisGenerator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisGenerator.class);

    private static final String AI_DISCLAIMER = "本分析由 AI 生成，不构成投资建议。";

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final LlmClient llmClient;
    private final LlmJsonParser jsonParser;
    private final MeterRegistry meterRegistry;

    public AnalysisGenerator(LlmClient llmClient, LlmJsonParser jsonParser, MeterRegistry meterRegistry) {
        this.llmClient = llmClient;
        this.jsonParser = jsonParser;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 生成分析报告。大模型优先，失败或未配置时降级为规则化报告。
     *
     * <p>这是同步阻塞调用：大模型最多等待 {@code app.llm.timeout}（默认 45 秒）。
     * 同步入口（{@code AnalysisService#analyze}）与异步入口（
     * {@code AnalysisTaskExecutor}）都调用本方法，差异只在「谁在等」——
     * 前者是 HTTP 请求线程，后者是专用后台线程。
     */
    public AnalysisReport generate(AnalysisInput input) {
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

    private static String now() {
        return TIMESTAMP.format(Instant.now().atZone(SHANGHAI));
    }
}
