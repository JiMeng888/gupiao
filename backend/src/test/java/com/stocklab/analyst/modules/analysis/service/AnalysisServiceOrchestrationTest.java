package com.stocklab.analyst.modules.analysis.service;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.analysis.dto.AnalysisReport;
import com.stocklab.analyst.modules.analysis.support.AiReportArchiver;
import com.stocklab.analyst.modules.analysis.support.AnalysisInput;
import com.stocklab.analyst.modules.analysis.support.AnalysisInputLoader;
import com.stocklab.analyst.modules.analysis.support.LlmJsonParser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
 * {@link AnalysisService} 编排层单测——钉住「读存档还是重新生成」这条分支判定。
 *
 * <p>协作者（取数 / 提示词 / 解析 / 兜底报告 / 存档）在拆分时已经各自有测试
 * （见 support 包下三个测试类），这里只测<b>分支决策本身</b>：
 * <ul>
 *   <li>存档命中且未要求强刷 → 直接返回存档，一分钱模型费用都不花；</li>
 *   <li>{@code forceRefresh} → 忽略存档，重新生成并归档；</li>
 *   <li>取不到报价 → 在进入模型调用<b>之前</b>就 404（不存在的代码不烧钱）；</li>
 *   <li>模型失败 → 降级规则化报告，且失败原因必须带到响应里，不能静默。</li>
 * </ul>
 *
 * <p>这类判定失效时接口照样返回 200，日志一行 ERROR 都没有——
 * 存档逻辑坏了表现为「每次都重新生成，账单变贵」，降级坏了表现为
 * 「用户把规则化文案当成大模型分析」。只有对分支本身的断言能拦住。
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceOrchestrationTest {

    private static final String CODE = "600519";

    @Mock
    private MarketDataProvider marketDataProvider;
    @Mock
    private AnalysisInputLoader inputLoader;
    @Mock
    private LlmClient llmClient;
    @Mock
    private LlmJsonParser jsonParser;
    @Mock
    private AiReportArchiver archiver;

    private SimpleMeterRegistry meterRegistry;
    private AnalysisService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new AnalysisService(
                marketDataProvider, inputLoader, llmClient, jsonParser, archiver, meterRegistry);
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

    /** 走到生成分支所需的最小桩：报价可用、取数成功、模型未配置。 */
    private void stubGenerationPathWithLlmDisabled() {
        Quote q = quote(CODE);
        when(marketDataProvider.getQuote(CODE)).thenReturn(q);
        when(inputLoader.load(eq(CODE), any(Quote.class))).thenReturn(input(q));
        when(llmClient.enabled()).thenReturn(false);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("存档命中与强制刷新")
    class ArchiveBranching {

        @Test
        @DisplayName("存档命中时直接返回，不取数、不调模型、不写存档")
        void archiveHitSkipsEverything() {
            AnalysisReport archived = archivedReport(CODE);
            when(archiver.loadLatest(CODE)).thenReturn(Optional.of(archived));

            AnalysisReport result = service.analyze(CODE, "u1", false);

            assertThat(result).isSameAs(archived);
            // 核心：一次存档命中意味着零次上游取数、零次模型调用、零次写库
            verifyNoInteractions(marketDataProvider, inputLoader, llmClient);
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

            verifyNoInteractions(marketDataProvider, inputLoader, llmClient, archiver);
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

            // 断钱断在取报价这一步：后面的取数、模型、归档都不该发生
            verifyNoInteractions(inputLoader, llmClient);
            verify(archiver, never()).archive(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("大模型失败降级为规则化报告，失败原因写入 notice 并计数")
        void llmFailureDegradesWithVisibleReason() {
            when(archiver.loadLatest(CODE)).thenReturn(Optional.empty());
            Quote q = quote(CODE);
            when(marketDataProvider.getQuote(CODE)).thenReturn(q);
            when(inputLoader.load(eq(CODE), any(Quote.class))).thenReturn(input(q));
            when(llmClient.enabled()).thenReturn(true);
            when(llmClient.complete(anyString(), anyString()))
                    .thenThrow(new BizException(ErrorCode.LLM_UPSTREAM_ERROR, "大模型调用失败（HTTP 502）"));

            AnalysisReport result = service.analyze(CODE, "u1", false);

            // 降级不能伪装成功：model 与 notice 都必须如实标注
            assertThat(result.model()).isEqualTo("rule-based");
            assertThat(result.notice()).contains("大模型调用失败");
            // 降级必须可观测：指标带上错误码，运维能在监控里看到降级率
            assertThat(meterRegistry.counter("app.analysis.fallback", "reason",
                    ErrorCode.LLM_UPSTREAM_ERROR.name()).count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("大模型未配置时直接走规则化，不产生失败计数")
        void llmDisabledIsNotAFailure() {
            stubGenerationPathWithLlmDisabled();

            AnalysisReport result = service.analyze(CODE, "u1", true);

            assertThat(result.model()).isEqualTo("rule-based");
            assertThat(result.notice()).contains("未配置");
            assertThat(meterRegistry.find("app.analysis.fallback").meter()).isNull();
        }
    }

    @Nested
    @DisplayName("大模型成功路径与历史查询")
    class LlmSuccessAndHistory {

        @Test
        @DisplayName("大模型成功时报告带模型名且 fromArchive=false")
        void llmSuccessProducesFreshReport() {
            when(archiver.loadLatest(CODE)).thenReturn(Optional.empty());
            Quote q = quote(CODE);
            when(marketDataProvider.getQuote(CODE)).thenReturn(q);
            when(inputLoader.load(eq(CODE), any(Quote.class))).thenReturn(input(q));
            when(llmClient.enabled()).thenReturn(true);
            when(llmClient.modelName()).thenReturn("qwen-plus");
            when(llmClient.complete(anyString(), anyString())).thenReturn("{}");
            JsonNode node = JsonMapper.builder().build().readTree(
                    "{\"thesis\":\"看好\",\"bullCase\":[\"b1\",\"b2\"],\"bearCase\":[],"
                            + "\"risks\":[\"r1\"],\"valuationNote\":\"v\",\"actionHint\":\"a\"}");
            when(jsonParser.parse("{}")).thenReturn(node);

            AnalysisReport result = service.analyze(CODE, "u1", false);

            assertThat(result.model()).isEqualTo("qwen-plus");
            assertThat(result.fromArchive()).isFalse();
            assertThat(result.thesis()).isEqualTo("看好");
            assertThat(result.bullCase()).containsExactly("b1", "b2");
        }

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
