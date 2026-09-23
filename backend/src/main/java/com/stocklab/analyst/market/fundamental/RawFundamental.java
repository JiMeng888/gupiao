package com.stocklab.analyst.market.fundamental;

/**
 * 实时财务指标原始值（来自东方财富行情接口的扩展字段）。
 *
 * <p>所有字段可空：不同行业的披露口径不同，缺失是常态而非异常。
 * 最典型的是<b>银行股没有「毛利率」</b>这个概念，上游返回 0——
 * 因此「0」在这里表示「不适用」，需要与「真的是 0%」区分开，
 * 由上层决定如何展示（应展示为「--」而不是「0%」）。
 *
 * @param code           股票代码
 * @param name           股票名称
 * @param industry       所属行业（如「白酒Ⅱ」）
 * @param peDynamic      市盈率（动态）
 * @param peStatic       市盈率（静态）
 * @param peTtm          市盈率（TTM）
 * @param pb             市净率
 * @param roe            净资产收益率（加权，%）
 * @param grossMargin    销售毛利率（%），银行等金融股为不适用
 * @param netMargin      销售净利率（%）
 * @param debtRatio      资产负债率（%）
 * @param revenue        营业总收入（元）
 * @param netProfit      归母净利润（元）
 * @param revenueYoy     营业总收入同比（%）
 * @param netProfitYoy   归母净利润同比（%）
 * @param bps            每股净资产（元）
 * @param eps            基本每股收益（元）
 */
public record RawFundamental(
        String code,
        String name,
        String industry,
        Double peDynamic,
        Double peStatic,
        Double peTtm,
        Double pb,
        Double roe,
        Double grossMargin,
        Double netMargin,
        Double debtRatio,
        Double revenue,
        Double netProfit,
        Double revenueYoy,
        Double netProfitYoy,
        Double bps,
        Double eps
) {
    /** 毛利率是否可用（银行等金融股不适用）。 */
    public boolean grossMarginApplicable() {
        return grossMargin != null && grossMargin > 0;
    }
}
