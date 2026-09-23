package com.stocklab.analyst.modules.indicator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stocklab.analyst.modules.indicator.service.IndicatorCalculator;

import java.util.List;
import java.util.Map;

/**
 * 技术指标响应。
 *
 * <p>每个字段可为 {@code null}，配合 {@link JsonInclude.Include#NON_NULL}
 * 实现「只返回被请求的指标」——与原 FastAPI 版本按需返回字典的行为一致，
 * 同时保留 Java 的强类型。
 *
 * <p>{@code signals} 是对指标末值的一句话解读（超买 / 超卖 / 多头排列等）。
 * <b>这是相对原实现的新增项</b>：原 Python 版只返回数值序列，
 * 功能文档中承诺的「量化信号与超买超卖解读」实际未实现，这里补齐。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndicatorResponse(
        MacdSeries macd,
        List<Double> rsi,
        BollSeries boll,
        KdjSeries kdj,
        MaSeries ma,
        Map<String, String> signals
) {

    /** MACD：DIF 快线、DEA 慢线（信号线）、MACD 柱。 */
    public record MacdSeries(
            List<Double> dif,
            List<Double> dea,
            /** 柱状值，字段名与国内软件口径一致。 */
            @JsonProperty("macd") List<Double> hist
    ) {
    }

    /** 布林带：上轨 / 中轨 / 下轨。 */
    public record BollSeries(List<Double> upper, List<Double> mid, List<Double> lower) {
    }

    /** KDJ：K 线、D 线、J 线。 */
    public record KdjSeries(List<Double> k, List<Double> d, List<Double> j) {
    }

    /** 四条均线。 */
    public record MaSeries(List<Double> ma5, List<Double> ma10, List<Double> ma20, List<Double> ma60) {
    }

    /** 便捷构造：把计算器的数组结果转成响应结构。 */
    public static MacdSeries ofMacd(IndicatorCalculator.Macd source) {
        return new MacdSeries(
                IndicatorCalculator.toList(source.dif()),
                IndicatorCalculator.toList(source.dea()),
                IndicatorCalculator.toList(source.hist()));
    }

    public static BollSeries ofBoll(IndicatorCalculator.Boll source) {
        return new BollSeries(
                IndicatorCalculator.toList(source.upper()),
                IndicatorCalculator.toList(source.mid()),
                IndicatorCalculator.toList(source.lower()));
    }

    public static KdjSeries ofKdj(IndicatorCalculator.Kdj source) {
        return new KdjSeries(
                IndicatorCalculator.toList(source.k()),
                IndicatorCalculator.toList(source.d()),
                IndicatorCalculator.toList(source.j()));
    }
}
