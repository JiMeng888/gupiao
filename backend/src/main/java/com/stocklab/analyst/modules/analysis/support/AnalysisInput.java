package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.fundamental.dto.FundamentalOverview;
import com.stocklab.analyst.modules.indicator.dto.IndicatorResponse;

import java.util.List;

/**
 * 一次分析用到的全部输入。
 *
 * <p>它是「取数」与「生成」两个阶段之间的唯一契约：前者负责把四块数据
 * （报价 / K 线 / 指标 / 财务）取齐并容忍缺项，后者只读这个对象，
 * 不再直接碰任何远端接口。因此大模型提示词与规则化兜底报告看到的数据
 * 必然一致——这正是不用两套取数逻辑的意义。
 *
 * @param quote       实时报价，必不为 null（取不到报价时分析入口会直接 404）
 * @param bars        近期 K 线；取数失败时为空列表而非 null
 * @param indicators  技术指标；取数失败时为 null
 * @param fundamental 财务与估值；取数失败时为 null
 * @param basis       本次真正取到的数据项清单，用于向用户如实交代数据基础
 */
public record AnalysisInput(
        Quote quote,
        List<Bar> bars,
        IndicatorResponse indicators,
        FundamentalOverview fundamental,
        List<String> basis
) {
}
