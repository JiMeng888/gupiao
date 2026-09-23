package com.stocklab.analyst.modules.holding.dto;

/**
 * 持仓视图（含实时行情与盈亏）。
 *
 * <p>所有金额字段由服务端计算，前端不做二次运算——
 * 盈亏这类涉及金额的算术如果前后端各算一遍，
 * 早晚会因为四舍五入规则不一致而出现「两处数字对不上」。
 *
 * <p>行情相关字段可为 {@code null}（行情源不可用时），
 * 此时仍返回持仓本身，前端显示「--」而不是整列表报错。
 *
 * <h2>卖出响应与查询响应的语义差异</h2>
 * 「卖出」接口的返回体也用本结构，但含义有两处不同，前端展示时必须区分：
 * <ul>
 *   <li>{@code currentPrice} 是<b>成交价</b>而不是市价，因此
 *       {@code marketValue} / {@code pnl} 是「按卖出价估算的剩余持仓盈亏」，
 *       不代表真实市值。</li>
 *   <li>{@code dayPnl} 固定为 {@code null}。<b>这一点是相对原 FastAPI 版本的修正</b>：
 *       原实现把成交价当成最新价、把平均成本当成昨收，
 *       于是 {@code dayPnl = 剩余股数 × (成交价 − 平均成本)} ——
 *       这是「累计浮盈」，被贴上「当日盈亏」的标签展示出去，
 *       两个数字含义完全不同。用成交价根本推不出当日盈亏，因此不再硬凑。</li>
 * </ul>
 * 卖出时真正需要的是「本次卖出的已实现盈亏」，即
 * {@code realizedPnl = 卖出股数 × (成交价 − 平均成本)}，见下方字段。
 *
 * @param id           持仓记录 ID
 * @param code         股票代码
 * @param name         股票名称
 * @param shares       持有股数（卖出接口返回的是卖出<b>之后</b>的剩余股数）
 * @param avgCost      加权平均成本（元/股）
 * @param buyDate      买入日期（YYYY-MM-DD）；补仓时保留首次买入日期
 * @param createdAt    建仓时间（秒级时间戳）
 * @param updatedAt    最后变动时间（秒级时间戳）
 * @param currentPrice 最新价；卖出接口返回成交价
 * @param marketValue  持仓市值 = 股数 × 最新价
 * @param costTotal    持仓成本 = 股数 × 平均成本
 * @param pnl          浮动盈亏 = 市值 − 成本
 * @param pnlPct       浮动盈亏率（百分比数值）
 * @param dayPnl       当日盈亏 = 股数 × (最新价 − 昨收)；卖出接口恒为 {@code null}
 * @param realizedPnl  本次卖出的已实现盈亏；仅卖出接口返回，其余场景为 {@code null}
 * @param soldShares   本次卖出股数；仅卖出接口返回，其余场景为 {@code null}
 */
public record HoldingView(
        String id,
        String code,
        String name,
        Integer shares,
        Double avgCost,
        String buyDate,
        Long createdAt,
        Long updatedAt,
        Double currentPrice,
        Double marketValue,
        Double costTotal,
        Double pnl,
        Double pnlPct,
        Double dayPnl,
        Double realizedPnl,
        Integer soldShares
) {
}
