package com.stocklab.analyst.modules.analysis.support;

/**
 * 分析链路共用的取值上限。
 *
 * <p>之所以单独成类而不是散落在各协作者里：这些数字（要取多少根 K 线、
 * 观点数组最多几条）会同时出现在「取数」「拼提示词」「兜底报告」三处，
 * 各写一份的话一旦调整就会出现「提示词说 60 日、报告文案写 30 日」这种
 * 口径不一致，而且不会有编译错误提醒你。
 */
public final class AnalysisLimits {

    private AnalysisLimits() {
    }

    /** 喂给模型的 K 线天数。60 个交易日约等于一个季度，覆盖一次中期波段。 */
    public static final int CONTEXT_BAR_DAYS = 60;

    /** 指标计算使用的 K 线根数。需大于 60 才能让 MA60 有值。 */
    public static final int INDICATOR_BAR_LIMIT = 120;

    /** 单个观点数组最多保留的条数，防止模型输出失控。 */
    public static final int MAX_LIST_ITEMS = 6;

    /** 历史报告一次最多返回的条数。 */
    public static final int MAX_HISTORY = 50;
}
