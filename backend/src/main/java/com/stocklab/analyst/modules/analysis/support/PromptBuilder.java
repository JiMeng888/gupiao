package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.fundamental.dto.FundamentalOverview;
import com.stocklab.analyst.modules.indicator.dto.IndicatorResponse;

import java.util.List;
import java.util.Map;

import static com.stocklab.analyst.modules.analysis.support.Formats.allNull;
import static com.stocklab.analyst.modules.analysis.support.Formats.appendMetric;
import static com.stocklab.analyst.modules.analysis.support.Formats.num;
import static com.stocklab.analyst.modules.analysis.support.Formats.percentile;
import static com.stocklab.analyst.modules.analysis.support.Formats.signed;

/**
 * 大模型提示词构造。
 *
 * <h2>为什么这一整块要从 AnalysisService 里拆出来</h2>
 * 提示词是这套系统里唯一「改一个字就可能改变输出质量」的部分，也是最需要
 * 独立演进、独立评审、独立写测试的部分。它此前和取数、归档、降级逻辑挤在
 * 同一个类里，任何一处改动都要在近千行代码里定位，且没有一条针对提示词
 * 内容的断言——「数据缺失时是否写了未获取到」这种关键约束完全没有护栏。
 * 拆出来之后它是纯函数：给定 {@link AnalysisInput}，产出确定字符串。
 *
 * <h2>核心设计：先把数据摆全，再要求结论</h2>
 * 原 FastAPI 实现要求模型输出 {@code valuation_note}（估值参考），
 * 但上下文里<b>只有 60 日 K 线的粗略摘要</b>，没有任何 PE / PB / ROE 数据。
 * 模型只能凭常识编一个「当前 PE 约 28x，行业均值约 32x」。
 * 因此这里的顺序是「数据在前、任务在后」，且对缺失项显式写「未获取到」——
 * 留白会被模型当作「无需处理」，写明缺失才会让它声明缺失。
 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    /** 系统提示词：约束模型的输出边界与格式。 */
    public static final String SYSTEM_PROMPT = """
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
     * <p>拼进这里的字符串全部来自上游接口（股票名来自行情源，数值来自财务接口），
     * 没有任何一段是用户自由文本——用户只能提供股票代码，且代码在上游已被校验成
     * 6 位数字。这是刻意设计：一旦允许用户文本进入 prompt，就等于给出了提示注入面。
     */
    public static String buildUserPrompt(AnalysisInput input) {
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
    static String barsBlock(List<Bar> bars) {
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

    static String indicatorBlock(IndicatorResponse indicators) {
        if (indicators == null) {
            return "- 未获取到技术指标数据，请勿对指标形态做出判断\n";
        }
        StringBuilder sb = new StringBuilder(512);

        if (indicators.macd() != null) {
            sb.append("- MACD：DIF ").append(num(Formats.last(indicators.macd().dif())))
                    .append(" / DEA ").append(num(Formats.last(indicators.macd().dea())))
                    .append(" / 柱 ").append(num(Formats.last(indicators.macd().hist()))).append("\n");
        }
        if (indicators.rsi() != null) {
            sb.append("- RSI(14)：").append(num(Formats.last(indicators.rsi()))).append("\n");
        }
        if (indicators.boll() != null) {
            sb.append("- BOLL：上轨 ").append(num(Formats.last(indicators.boll().upper())))
                    .append(" / 中轨 ").append(num(Formats.last(indicators.boll().mid())))
                    .append(" / 下轨 ").append(num(Formats.last(indicators.boll().lower()))).append("\n");
        }
        if (indicators.kdj() != null) {
            sb.append("- KDJ：K ").append(num(Formats.last(indicators.kdj().k())))
                    .append(" / D ").append(num(Formats.last(indicators.kdj().d())))
                    .append(" / J ").append(num(Formats.last(indicators.kdj().j()))).append("\n");
        }
        if (indicators.ma() != null) {
            sb.append("- 均线：MA5 ").append(num(Formats.last(indicators.ma().ma5())))
                    .append(" / MA10 ").append(num(Formats.last(indicators.ma().ma10())))
                    .append(" / MA20 ").append(num(Formats.last(indicators.ma().ma20())))
                    .append(" / MA60 ").append(num(Formats.last(indicators.ma().ma60()))).append("\n");
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

    static String fundamentalBlock(FundamentalOverview f) {
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
}
