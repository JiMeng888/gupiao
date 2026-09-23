package com.stocklab.analyst.modules.holding.dto;

/**
 * 组合总览。
 *
 * @param count       持仓只数
 * @param marketValue 总市值
 * @param costTotal   总成本
 * @param pnl         总浮动盈亏
 * @param pnlPct      总收益率（百分比数值）
 * @param dayPnl      当日总盈亏
 */
public record PortfolioSummary(
        int count,
        double marketValue,
        double costTotal,
        double pnl,
        double pnlPct,
        double dayPnl
) {
    /** 空仓时的零值。 */
    public static PortfolioSummary empty() {
        return new PortfolioSummary(0, 0, 0, 0, 0, 0);
    }
}
