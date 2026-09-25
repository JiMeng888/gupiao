package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.fundamental.dto.FundamentalOverview;
import com.stocklab.analyst.modules.indicator.dto.IndicatorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分析提示词构造。
 *
 * <p>提示词此前是 950 行上帝类里的一段私有字符串拼接，没有任何断言。
 * 它最关键的约束不是「长得对」，而是<b>数据缺失时必须写明缺失</b>——
 * 一旦退化成留白，模型会用自己的常识补上 PE、行业增速这类具体数字，
 * 而用户完全看不出来那是编的。因此这里把「缺项必须显式声明」
 * 作为主要断言对象，而不是只检查段落标题存在。
 */
@DisplayName("分析提示词构造")
class PromptBuilderTest {

    @Nested
    @DisplayName("数据齐全")
    class WithFullData {

        @Test
        @DisplayName("包含全部数据段落与标的标识")
        void containsAllSections() {
            String prompt = PromptBuilder.buildUserPrompt(fullInput());

            assertThat(prompt)
                    .contains("## 标的")
                    .contains("贵州茅台（600519，SH）")
                    .contains("## 实时行情")
                    .contains("## 近期走势")
                    .contains("## 技术指标（最近一日）")
                    .contains("## 财务指标")
                    .contains("## 输出要求");
        }

        @Test
        @DisplayName("技术指标的形态解读一并交给模型")
        void includesIndicatorSignals() {
            String prompt = PromptBuilder.buildUserPrompt(fullInput());

            assertThat(prompt).contains("形态解读").contains("均线多头排列");
        }

        @Test
        @DisplayName("均线末值取自序列最后一位而非第一位")
        void usesLastIndicatorValue() {
            String prompt = PromptBuilder.buildUserPrompt(fullInput());

            // ma20 序列是 [1600, 1640]，末值应为 1640
            assertThat(prompt).contains("MA20 1640.00");
            assertThat(prompt).doesNotContain("MA20 1600.00");
        }

        @Test
        @DisplayName("输出约束用 camelCase 字段名，不残留原 Python 版的 snake_case")
        void outputSchemaUsesCamelCase() {
            String prompt = PromptBuilder.buildUserPrompt(fullInput());

            assertThat(prompt)
                    .contains("bullCase").contains("bearCase")
                    .contains("valuationNote").contains("actionHint")
                    .doesNotContain("bull_case").doesNotContain("valuation_note")
                    .doesNotContain("action_hint");
        }
    }

    @Nested
    @DisplayName("数据缺失")
    class WithMissingData {

        @Test
        @DisplayName("三块数据全缺时逐项写明「未获取到」而不是留白")
        void declaresEveryMissingBlock() {
            AnalysisInput input = new AnalysisInput(quote(), List.of(), null, null, List.of("实时报价"));

            String prompt = PromptBuilder.buildUserPrompt(input);

            assertThat(prompt)
                    .contains("未获取到 K 线数据")
                    .contains("未获取到技术指标数据")
                    .contains("未获取到财务与估值数据");
        }

        @Test
        @DisplayName("缺 K 线时明确要求模型不要对走势下判断")
        void forbidsTrendConclusionWithoutBars() {
            String block = PromptBuilder.barsBlock(List.of());

            assertThat(block).contains("请勿对走势做出任何判断");
        }

        @Test
        @DisplayName("估值整组为空时写出「未获取到」，不留一个空标签")
        void writesMissingWhenAllValuationNull() {
            FundamentalOverview empty = new FundamentalOverview(
                    "600519", "贵州茅台", "白酒",
                    null, null, null, null,
                    null, null, true, null, null,
                    null, null, null, null, null, null, null, null,
                    "eastmoney", null);

            String block = PromptBuilder.fundamentalBlock(empty);

            assertThat(block).contains("- 估值：未获取到");
        }
    }

    @Nested
    @DisplayName("数据可信度")
    class DataTrust {

        @Test
        @DisplayName("估算值必须带「不是真实披露数据」的警示")
        void warnsOnEstimatedData() {
            FundamentalOverview estimated = new FundamentalOverview(
                    "600519", "贵州茅台", "白酒",
                    32.0, 33.0, 31.5, 8.2,
                    28.4, 91.2, true, 52.1, 18.3,
                    1.2e11, 6.0e10, 12.5, 15.2, 150.2, 52.4, 25.0, 26.0,
                    "estimated", "上游不可用时估算");

            String block = PromptBuilder.fundamentalBlock(estimated);

            assertThat(block).contains("估算值");
            assertThat(block).contains("不是真实披露数据");
        }

        @Test
        @DisplayName("真实披露数据不加估算警示")
        void noWarningForRealData() {
            String block = PromptBuilder.fundamentalBlock(fundamental("eastmoney"));

            assertThat(block).doesNotContain("不是真实披露数据");
        }

        @Test
        @DisplayName("金融类标的毛利率不适用时写明原因，不显示为 0")
        void handlesNonApplicableGrossMargin() {
            FundamentalOverview bank = new FundamentalOverview(
                    "600036", "招商银行", "银行",
                    6.0, 6.2, 5.9, 0.9,
                    16.1, null, false, 45.0, 91.5,
                    3.4e11, 1.4e11, 3.0, 5.0, 38.0, 5.6, 5.5, 9.1,
                    "eastmoney", null);

            String block = PromptBuilder.fundamentalBlock(bank);

            assertThat(block).contains("毛利率不适用（金融类）");
            assertThat(block).doesNotContain("毛利率 0");
        }
    }

    @Test
    @DisplayName("系统提示词钉死四条边界规则")
    void systemPromptPinsBoundaries() {
        assertThat(PromptBuilder.SYSTEM_PROMPT)
                .contains("不得引入未提供的任何数据")
                .contains("禁止推测或编造具体数值")
                .contains("严格输出 JSON 对象");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static AnalysisInput fullInput() {
        return new AnalysisInput(
                quote(),
                List.of(bar("2026-07-01", 10.0), bar("2026-07-02", 11.0), bar("2026-07-03", 12.0)),
                indicators(),
                fundamental("eastmoney"),
                List.of("实时报价", "近 3 个交易日 K 线"));
    }

    private static Quote quote() {
        return new Quote("600519", "贵州茅台", "SH",
                1680.5, 12.3, 0.74, 1668.0, 1690.0, 1660.0, 1668.2,
                32100L, 0.26, 5.4e9, 1758600000L);
    }

    private static Bar bar(String date, double close) {
        return new Bar(date, close, close + 0.5, close - 0.5, close, 10000L, 1.0e6, close - 0.1);
    }

    private static IndicatorResponse indicators() {
        return new IndicatorResponse(
                new IndicatorResponse.MacdSeries(List.of(1.0, 1.2), List.of(0.8, 1.0), List.of(0.1, 0.2)),
                List.of(50.0, 58.3),
                new IndicatorResponse.BollSeries(List.of(1800.0), List.of(1700.0), List.of(1600.0)),
                new IndicatorResponse.KdjSeries(List.of(70.0), List.of(65.0), List.of(80.0)),
                new IndicatorResponse.MaSeries(List.of(1670.0), List.of(1650.0),
                        List.of(1600.0, 1640.0), List.of(1580.0, 1600.0)),
                Map.of("ma", "均线多头排列"));
    }

    private static FundamentalOverview fundamental(String dataSource) {
        return new FundamentalOverview(
                "600519", "贵州茅台", "白酒",
                32.0, 33.0, 31.5, 8.2,
                28.4, 91.2, true, 52.1, 18.3,
                1.2e11, 6.0e10, 12.5, 15.2, 150.2, 52.4, 25.0, 26.0,
                dataSource, "来源说明");
    }
}
