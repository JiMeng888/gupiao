package com.stocklab.analyst.market.fundamental;

/**
 * 单期业绩报表原始值。
 *
 * <p>来自东方财富数据中心的历史业绩接口，覆盖近 25 年、100+ 期报告，
 * 是<b>真实披露数据</b>，不是估算。
 *
 * @param reportDate   报告期（如 {@code 2026-06-30}）
 * @param periodLabel  报告期中文描述（如「2026年 半年报」）
 * @param quarter      报告期标识（如 {@code 2026Q2}）
 * @param revenue      营业总收入（元）
 * @param netProfit    归母净利润（元）
 * @param roe          加权 ROE（%）
 * @param revenueYoy   营业总收入同比（%）
 * @param netProfitYoy 归母净利润同比（%）
 * @param grossMargin  销售毛利率（%）
 * @param bps          每股净资产（元）
 * @param eps          基本每股收益（元）
 * @param cashflowPerShare 每股经营现金流（元）
 * @param dividendPlan 分红方案描述
 * @param boardCode    所属行业板块代码（如 {@code BK1277}），用于取行业估值
 * @param boardName    所属行业板块名称（如「白酒Ⅱ」）
 */
public record RawFinancialPeriod(
        String reportDate,
        String periodLabel,
        String quarter,
        Double revenue,
        Double netProfit,
        Double roe,
        Double revenueYoy,
        Double netProfitYoy,
        Double grossMargin,
        Double bps,
        Double eps,
        Double cashflowPerShare,
        String dividendPlan,
        String boardCode,
        String boardName
) {
}
