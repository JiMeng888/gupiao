package com.stocklab.analyst.modules.fundamental.dto;

/**
 * 杜邦分析：把 ROE 拆解为三个驱动因子。
 *
 * <p>恒等式：{@code ROE = 销售净利率 × 总资产周转率 × 权益乘数}
 *
 * <p>本实现的计算方式值得说明：净利率、ROE、资产负债率均取自真实披露数据，
 * 其中
 * <pre>
 *   权益乘数     = 1 / (1 − 资产负债率)
 *   总资产周转率 = ROE / (净利率 × 权益乘数)
 * </pre>
 * 即周转率是<b>反推</b>出来的。这样做的原因是恒等式必须严格成立——
 * 如果三个因子各自独立取数，由于口径与报告期不同，三者相乘
 * 往往得不到披露的 ROE（可能差几个百分点），页面上就会出现
 * 「拆解结果和 ROE 对不上」的硬伤。反推一个因子可以让恒等式精确闭合，
 * 同时把误差集中到周转率这一个本身语义最模糊的指标上。
 *
 * <p>对比原 FastAPI 版本：原实现返回的是写死的常量
 * （{@code roe=25.6, net_margin=32.1, asset_turnover=0.52, equity_multiplier=1.53}），
 * 所有股票都一样。虽然那组常量恰好满足恒等式，但与任何一家真实公司无关。
 *
 * @param code            股票代码
 * @param name            股票名称
 * @param roe             净资产收益率（%）
 * @param netMargin       销售净利率（%）
 * @param assetTurnover   总资产周转率（次）
 * @param equityMultiplier 权益乘数
 * @param interpretation  一句话解读（哪个因子在驱动 ROE）
 * @param dataSource      数据来源标识：eastmoney / estimated
 */
public record DupontAnalysis(
        String code,
        String name,
        Double roe,
        Double netMargin,
        Double assetTurnover,
        Double equityMultiplier,
        String interpretation,
        String dataSource
) {
}
