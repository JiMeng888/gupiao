package com.stocklab.analyst.modules.fundamental.dto;

/**
 * 单期业绩报表。
 *
 * <p>真实披露数据，非估算。注意 A 股财报的「营业收入」「净利润」在
 * 一季报 / 半年报 / 三季报 / 年报中是<b>年内累计值</b>而非单季值，
 * 例如半年报的营收是 1~6 月合计。前端画趋势图时必须按同比口径比较
 * （同比同报告期），不能直接拿一季报与年报对比。
 *
 * @param reportDate       报告期（{@code yyyy-MM-dd}）
 * @param periodLabel      报告期名称（如「2026年 半年报」）
 * @param quarter          报告期标识（如 {@code 2026Q2}）
 * @param revenue          营业总收入（元，年内累计）
 * @param netProfit        归母净利润（元，年内累计）
 * @param roe              加权 ROE（%）
 * @param revenueYoy       营业总收入同比（%）
 * @param netProfitYoy     归母净利润同比（%）
 * @param grossMargin      销售毛利率（%）
 * @param bps              每股净资产（元）
 * @param eps              基本每股收益（元，年内累计）
 * @param cashflowPerShare 每股经营现金流（元）
 * @param dividendPlan     分红方案
 */
public record FinancialPeriodView(
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
        String dividendPlan
) {
}
