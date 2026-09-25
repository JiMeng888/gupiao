package com.stocklab.analyst.modules.fundamental.dto;

import java.util.List;

/**
 * 业绩报表响应。
 *
 * <p>与原 FastAPI 版本的差异需要明确说明：原实现的
 * {@code GET /fundamental/{code}/statements?type=income} 返回的是
 * 编造的三表数据（营收固定 {@code [1000, 1120, 1240]}，
 * 净利润固定 {@code [200, 225, 250]}，与股票无关），
 * 且 {@code type} 参数实际上没有被使用——无论传 income 还是 cashflow，
 * 返回的都是同一份假数据。
 *
 * <p>本实现改为返回<b>真实的逐期业绩摘要</b>，并移除了无效的 {@code type} 参数。
 * 不做「利润表 / 资产负债表 / 现金流量表」三表拆分，因为上游内部接口
 * 只提供业绩摘要（营收、利润、ROE、每股指标、分红方案），
 * 不提供完整三表的全部科目。与其编造科目明细，不如如实提供一个
 * 有真实数据支撑的业绩视图。
 *
 * @param code        股票代码
 * @param name        股票名称
 * @param industry    所属行业
 * @param dataSource  数据来源标识
 * @param periods     逐期报表（按报告期倒序，最新在前）
 * @param notice      说明文案
 */
public record StatementsResponse(
        String code,
        String name,
        String industry,
        String dataSource,
        List<FinancialPeriodView> periods,
        String notice
) {
}
