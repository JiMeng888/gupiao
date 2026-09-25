package com.stocklab.analyst.modules.analysis.support;

import java.util.List;

/**
 * 数值到文案的格式化。
 *
 * <p>提示词与规则化兜底报告共用同一套写法，是刻意的：两处如果各写一份
 * 保留位数，就会出现「喂给模型的是 12.3456、兜底报告写的是 12.35」这类
 * 同一份数据两种精度的现象，用户对比两份报告时会以为数据对不上。
 *
 * <p>注意 {@link #num(Double)} 对 null 返回的是「未获取到」——这不是一个
 * 通用的空值占位，而是给提示词用的：模型看到「未获取到」才会按系统提示词
 * 的要求声明数据缺失，看到留白则倾向于自行补全。
 */
public final class Formats {

    private Formats() {
    }

    /** 保留两位小数。 */
    public static String num(double value) {
        return String.format("%.2f", value);
    }

    /**
     * 保留两位小数；{@code null} 时返回「未获取到」。
     *
     * <p>语义上是「该项无数据」，因此只用于面向模型与用户的文本，
     * 不要拿它做数值展示的通用兜底。
     */
    public static String num(Double value) {
        return value == null ? "未获取到" : String.format("%.2f", value);
    }

    /** 带符号的两位小数，用于涨跌幅这类需要区分正负的数值。 */
    public static String signed(double value) {
        return String.format("%+.2f", value);
    }

    /** 列表末值；空列表或 null 返回 null。 */
    public static Double last(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(values.size() - 1);
    }

    /** 当前值在区间中的百分位（0=区间最低，100=区间最高）。 */
    public static int percentile(double value, double low, double high) {
        if (high <= low) {
            return 50;
        }
        double ratio = (value - low) / (high - low) * 100;
        return (int) Math.round(Math.max(0, Math.min(100, ratio)));
    }

    /** 追加「标签 数值」；值为 null 时整段跳过，不留空标签。 */
    public static void appendMetric(StringBuilder sb, String label, Double value) {
        appendMetric(sb, label, value, "");
    }

    /** 追加「标签 数值单位」；值为 null 时整段跳过，不留空标签。 */
    public static void appendMetric(StringBuilder sb, String label, Double value, String unit) {
        if (value != null) {
            sb.append(label).append(" ").append(num(value)).append(unit).append(" ");
        }
    }

    /** 全部为 null 时返回 true。用于判断「整组指标都没取到」。 */
    public static boolean allNull(Double... values) {
        for (Double value : values) {
            if (value != null) {
                return false;
            }
        }
        return true;
    }
}
