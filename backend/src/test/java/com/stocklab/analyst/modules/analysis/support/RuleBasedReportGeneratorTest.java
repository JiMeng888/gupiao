package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.analysis.dto.AnalysisReport;
import com.stocklab.analyst.modules.fundamental.dto.FundamentalOverview;
import com.stocklab.analyst.modules.indicator.dto.IndicatorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.stocklab.analyst.modules.analysis.support.RuleBasedReportGenerator.RULE_BASED_MODEL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则化兜底报告。
 *
 * <p>这个类的存在理由是「大模型不可用时页面不能空着」，但它同时承担一个
 * 更重的责任：<b>不能编造</b>。原 FastAPI 版本的兜底报告写死了
 * 「当前 PE 约 28x，行业均值约 32x」，与标的毫无关系，用户却无从分辨。
 *
 * <p>因此这里的断言重点不在文案措辞，而在两条纪律：
 * 估算值不得进入结论性表述；取不到的数据必须写成「未获取到」。
 */
@DisplayName("规则化兜底报告")
class RuleBasedReportGeneratorTest {

    private static final String GENERATED_AT = "2026-09-24T10:30:00+08:00";

    @Nested
    @DisplayName("数据可信度约束")
    class DataTrust {

        @Test
        @DisplayName("估算财务数据不得进入看多逻辑")
        void estimatedFinancialsNeverReachConclusion() {
            AnalysisReport report = RuleBasedReportGenerator.generate(
                    input(fundamental("estimated", 25.0, 30.0, 10.0, 20.0), barsUp(), null),
                    "未配置大模型 API Key", GENERATED_AT);

            // ROE 25 / 资产负债率 30 / PE 低于行业，若不加门槛这三条都会被写进看多逻辑
            assertThat(report.bullCase())
                    .noneMatch(item -> item.contains("盈利能力处于较高水平"))
                    .noneMatch(item -> item.contains("财务结构稳健"))
                    .noneMatch(item -> item.contains("估值相对折价"));
            // 走势类判断不依赖财务数据，应当保留
            assertThat(report.bullCase()).anyMatch(item -> item.contains("累计上涨"));
        }

        @Test
        @DisplayName("真实披露数据才允许得出财务类结论")
        void realFinancialsReachConclusion() {
            AnalysisReport report = RuleBasedReportGenerator.generate(
                    input(fundamental("eastmoney", 25.0, 30.0, 10.0, 20.0), barsUp(), null),
                    "未配置大模型 API Key", GENERATED_AT);

            assertThat(report.bullCase())
                    .anyMatch(item -> item.contains("盈利能力处于较高水平"))
                    .anyMatch(item -> item.contains("财务结构稳健"))
                    .anyMatch(item -> item.contains("估值相对折价"));
        }

        @Test
        @DisplayName("估算值出现在估值说明时必须标注「非真实数据」")
        void valuationNoteMarksEstimated() {
            AnalysisReport estimated = RuleBasedReportGenerator.generate(
                    input(fundamental("estimated", 25.0, 30.0, 10.0, 20.0), barsUp(), null),
                    "上游超时", GENERATED_AT);
            AnalysisReport real = RuleBasedReportGenerator.generate(
                    input(fundamental("eastmoney", 25.0, 30.0, 10.0, 20.0), barsUp(), null),
                    "上游超时", GENERATED_AT);

            assertThat(estimated.valuationNote()).contains("（估算值，非真实数据）");
            assertThat(real.valuationNote()).doesNotContain("估算值");
        }
    }

    @Nested
    @DisplayName("缺数时的措辞")
    class MissingData {

        @Test
        @DisplayName("无 K 线时明说无法评估区间走势，不给方向")
        void declaresMissingBars() {
            AnalysisReport report = RuleBasedReportGenerator.generate(
                    input(null, List.of(), null), "未配置大模型 API Key", GENERATED_AT);

            assertThat(report.thesis()).contains("未获取到 K 线数据");
            assertThat(report.valuationNote()).isEqualTo("未获取到估值数据，不做估值判断");
        }

        @Test
        @DisplayName("没有任何可推导项时给出「需补充数据」而不是编一条结论")
        void neverLeavesListEmpty() {
            AnalysisReport report = RuleBasedReportGenerator.generate(
                    input(null, List.of(), null), "未配置大模型 API Key", GENERATED_AT);

            assertThat(report.bullCase()).hasSize(1);
            assertThat(report.bearCase()).hasSize(1);
            assertThat(report.bullCase().get(0)).contains("未呈现明确的多头逻辑");
            assertThat(report.bearCase().get(0)).contains("未呈现明确的空头逻辑");
        }

        @Test
        @DisplayName("风险项是通用系统性风险，不含任何公司特有的事实主张")
        void risksStayGeneric() {
            AnalysisReport report = RuleBasedReportGenerator.generate(
                    input(fundamental("eastmoney", 25.0, 30.0, 10.0, 20.0), barsUp(), null),
                    "上游超时", GENERATED_AT);

            assertThat(report.risks()).containsExactly(
                    "市场整体波动与流动性风险",
                    "所属行业景气度变化风险",
                    "公司经营与业绩不及预期风险",
                    "规则化分析无法识别公司特有风险事件");
        }
    }

    @Nested
    @DisplayName("由数据推导的结论")
    class DerivedConclusions {

        @Test
        @DisplayName("RSI 超买进入看空逻辑")
        void overboughtRaisesBearCase() {
            IndicatorResponse rsi75 = new IndicatorResponse(
                    null, List.of(60.0, 75.0), null, null, null, null);

            AnalysisReport report = RuleBasedReportGenerator.generate(
                    input(null, barsUp(), rsi75), "上游超时", GENERATED_AT);

            assertThat(report.bearCase()).anyMatch(item -> item.contains("超买区间"));
        }

        @Test
        @DisplayName("现价贴近区间上沿进入看空逻辑")
        void nearRangeTopRaisesBearCase() {
            // 区间 [10, 12]，末值 11.9 → 约 95% 分位
            List<Bar> bars = List.of(
                    new Bar("2026-07-01", 10.0, 10.5, 9.8, 10.0, 1000L, 1.0e6, 9.9),
                    new Bar("2026-07-02", 11.0, 12.0, 10.9, 11.9, 1000L, 1.0e6, 11.0));

            AnalysisReport report = RuleBasedReportGenerator.generate(
                    input(null, bars, null), "上游超时", GENERATED_AT);

            assertThat(report.bearCase()).anyMatch(item -> item.contains("接近区间上沿"));
        }
    }

    @Nested
    @DisplayName("必须展示给用户的元信息")
    class Metadata {

        @Test
        @DisplayName("model 标记为 rule-based，fromArchive 为 false")
        void marksRuleBased() {
            AnalysisReport report = RuleBasedReportGenerator.generate(
                    input(fundamental("eastmoney", 25.0, 30.0, 10.0, 20.0), barsUp(), null),
                    "上游超时", GENERATED_AT);

            assertThat(report.model()).isEqualTo(RULE_BASED_MODEL);
            assertThat(report.fromArchive()).isFalse();
            assertThat(report.generatedAt()).isEqualTo(GENERATED_AT);
            assertThat(report.code()).isEqualTo("600519");
            assertThat(report.name()).isEqualTo("贵州茅台");
        }

        @Test
        @DisplayName("降级原因必须写进 notice，否则用户会把规则化文案当成大模型分析")
        void carriesDowngradeReason() {
            AnalysisReport report = RuleBasedReportGenerator.generate(
                    input(fundamental("eastmoney", 25.0, 30.0, 10.0, 20.0), barsUp(), null),
                    "大模型调用超时", GENERATED_AT);

            assertThat(report.notice())
                    .contains("非大模型输出")
                    .contains("大模型调用超时");
        }

        @Test
        @DisplayName("数据清单原样带入报告")
        void keepsDataBasis() {
            AnalysisReport report = RuleBasedReportGenerator.generate(
                    input(fundamental("eastmoney", 25.0, 30.0, 10.0, 20.0), barsUp(), null),
                    "上游超时", GENERATED_AT);

            assertThat(report.dataBasis()).containsExactly("实时报价", "近 2 个交易日 K 线");
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static AnalysisInput input(FundamentalOverview fundamental, List<Bar> bars,
                                       IndicatorResponse indicators) {
        return new AnalysisInput(quote(), bars, indicators, fundamental,
                List.of("实时报价", "近 " + bars.size() + " 个交易日 K 线"));
    }

    private static Quote quote() {
        return new Quote("600519", "贵州茅台", "SH",
                12.0, 0.5, 4.35, 11.6, 12.1, 11.5, 11.5,
                32100L, 0.26, 5.4e9, 1758600000L);
    }

    /** 两段上涨 K 线：10 → 12，累计 +20%。 */
    private static List<Bar> barsUp() {
        return List.of(
                new Bar("2026-07-01", 10.0, 10.5, 9.8, 10.0, 1000L, 1.0e6, 9.9),
                new Bar("2026-07-02", 11.5, 12.1, 11.5, 12.0, 1000L, 1.0e6, 11.0));
    }

    private static FundamentalOverview fundamental(String dataSource, double roe, double debtRatio,
                                                  double peTtm, double industryPe) {
        return new FundamentalOverview(
                "600519", "贵州茅台", "白酒",
                null, null, peTtm, 8.2,
                roe, 91.2, true, 52.1, debtRatio,
                1.2e11, 6.0e10, 12.5, 15.2, 150.2, 52.4, industryPe, 26.0,
                dataSource, "来源说明");
    }
}
