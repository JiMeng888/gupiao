package com.stocklab.analyst.market.model;

/**
 * 市场榜单条目：按某一维度排序后的一只股票。
 *
 * <p>与 {@link Quote} 分开建模型而不是复用它：榜单只需要 6 个展示字段，
 * 而 Quote 有 14 个（其中 open/high/low 等对榜单毫无意义），
 * 且真实源的榜单接口一次返回、不提供全部报价字段——
 * 为了凑齐一个 Quote 而对前 20 名逐个再发一次请求，是拿体验换教条。
 *
 * @param code      6 位股票代码
 * @param name      股票名称
 * @param price     最新价
 * @param change    涨跌额
 * @param changePct 涨跌幅（百分比数值，如 3.42 表示 +3.42%）
 * @param amount    成交额（元），上游缺字段时为 0
 */
public record Mover(
        String code,
        String name,
        double price,
        double change,
        double changePct,
        double amount
) {
}
