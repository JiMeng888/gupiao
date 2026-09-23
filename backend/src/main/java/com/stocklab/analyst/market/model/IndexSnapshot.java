package com.stocklab.analyst.market.model;

/**
 * 大盘指数快照。
 *
 * @param code      指数代码（如 {@code 000001.SH}）
 * @param name      指数名称
 * @param value     最新点位
 * @param change    涨跌点数
 * @param changePct 涨跌幅（百分比数值）
 */
public record IndexSnapshot(
        String code,
        String name,
        double value,
        double change,
        double changePct
) {
}
