package com.stocklab.analyst.modules.holding.dto;

/**
 * 组合总览。
 *
 * @param count       参与本次统计的持仓只数（即取到行情的只数）
 * @param totalCount  持仓总只数（不含已清仓）。与 {@code count} 的差值即
 *                     「本次没取到行情的只数」——行情整体不可用时
 *                     {@code count} 会是 0 而 {@code totalCount} 仍是真实只数，
 *                     调用方据此提示「行情暂不可用」，而不是把归零误读成空仓
 * @param marketValue 总市值
 * @param costTotal   总成本
 * @param pnl         总浮动盈亏
 * @param pnlPct      总收益率（百分比数值）
 * @param dayPnl      当日总盈亏
 */
public record PortfolioSummary(
        int count,
        int totalCount,
        double marketValue,
        double costTotal,
        double pnl,
        double pnlPct,
        double dayPnl
) {
    /** 空仓时的零值。 */
    public static PortfolioSummary empty() {
        return new PortfolioSummary(0, 0, 0, 0, 0, 0, 0);
    }
}
