package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.analysis.dto.AnalysisReport;
import com.stocklab.analyst.modules.fundamental.dto.FundamentalOverview;
import com.stocklab.analyst.modules.indicator.dto.IndicatorResponse;

import java.util.ArrayList;
import java.util.List;

import static com.stocklab.analyst.modules.analysis.support.AnalysisLimits.CONTEXT_BAR_DAYS;
import static com.stocklab.analyst.modules.analysis.support.AnalysisLimits.MAX_LIST_ITEMS;
import static com.stocklab.analyst.modules.analysis.support.Formats.last;
import static com.stocklab.analyst.modules.analysis.support.Formats.num;
import static com.stocklab.analyst.modules.analysis.support.Formats.percentile;
import static com.stocklab.analyst.modules.analysis.support.Formats.signed;

/**
 * 规则化兜底报告。
 *
 * <h2>它存在的意义，以及它<b>不允许</b>做什么</h2>
 * 大模型未配置或调用失败时，页面不能空着，于是用规则把真实数据客观归纳一遍。
 * 这里有一条硬约束：<b>不含任何编造的具体数值</b>。
 *
 * <p>原 FastAPI 实现的 {@code _mock_report} 违反了这条：
 * 它算了 {@code up = change_pct >= 0} 却没用在任何地方，
 * 估值说明写死「当前 PE 约 28x，行业均值约 32x」——那是一段与标的无关的固定文案。
 * 用户无从分辨那是真实数据还是模板，危害比「什么都不显示」更大。
 *
 * <h2>两道防线</h2>
 * <ul>
 *   <li>取不到的数据就明说取不到，绝不用「约」「可能」含糊过去；</li>
 *   <li>{@link #isReal} 把「估算值」挡在结论性表述之外——只有
 *       {@code eastmoney} 来源的真实披露数据才允许进入看多 / 看空逻辑。</li>
 * </ul>
 *
 * <p>拆为独立类之后它是纯函数（给定输入与时间戳即得确定报告），
 * 因此「ROE 估算值不得进入看多逻辑」这类约束可以被测试直接钉住。
 */
public final class RuleBasedReportGenerator {

    private RuleBasedReportGenerator() {
    }

    /** 规则化报告的 model 字段值。前端据此把规则化文案与大模型输出区分开。 */
    public static final String RULE_BASED_MODEL = "rule-based";

    private static final String RULE_DISCLAIMER =
            "本内容由规则化逻辑根据实时行情与财务数据生成（非大模型输出），不构成投资建议。";

    /**
     * 生成规则化报告。
     *
     * @param input       本次取到的全部数据
     * @param reason      未调用大模型的原因，会写进 {@code notice} 展示给用户
     * @param generatedAt 生成时间（ISO-8601 带时区偏移）；由调用方传入而非在此取当前时间，
     *                    这样测试可以断言确定的时间字符串
     */
    public static AnalysisReport generate(AnalysisInput input, String reason, String generatedAt) {
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
                generatedAt,
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

    static String ruleThesis(Quote quote, FundamentalOverview f,
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

    /**
     * 看多逻辑。
     *
     * <p>注意每一条财务类判断前都带 {@link #isReal} 门槛：估算出来的 ROE 好看
     * 不代表事实好看，把它写成「盈利能力处于较高水平」就是拿假数据下真结论。
     */
    static List<String> ruleBullCase(Quote quote, FundamentalOverview f,
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

    static List<String> ruleBearCase(FundamentalOverview f, Double rsi,
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
    static List<String> genericRisks() {
        return List.of(
                "市场整体波动与流动性风险",
                "所属行业景气度变化风险",
                "公司经营与业绩不及预期风险",
                "规则化分析无法识别公司特有风险事件");
    }

    static String ruleValuationNote(FundamentalOverview f) {
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
    static boolean isReal(FundamentalOverview f) {
        return "eastmoney".equals(f.dataSource());
    }
}
