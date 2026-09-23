package com.stocklab.analyst.modules.fundamental.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 财务基本面概览。
 *
 * <p><b>数据来源标注</b>：{@code dataSource} 字段用于区分数据可信度。
 * <ul>
 *   <li>{@code eastmoney} —— 东方财富真实披露数据</li>
 *   <li>{@code estimated} —— 上游不可用时的估算值（由代码推导，与实际公司无关）</li>
 * </ul>
 * 前端必须据此外显标注。财务数据会被用户当作真实信息用于投资判断，
 * 把估算值当作真实值展示是产品层面的严重问题——
 * 这也是本项目相对原 FastAPI 版本的核心修正：原实现的这些字段
 * 全部由股票代码哈希推算得出，与真实公司财务状况无关。
 *
 * @param code                  股票代码
 * @param name                  股票名称
 * @param industry               所属行业
 * @param pe                    市盈率（动态）
 * @param peStatic              市盈率（静态）
 * @param peTtm                 市盈率（TTM）
 * @param pb                    市净率
 * @param roe                   净资产收益率（加权，%）
 * @param grossMargin           销售毛利率（%）
 * @param grossMarginApplicable 毛利率是否适用（银行为 false，前端应显示「--」）
 * @param netMargin             销售净利率（%）
 * @param debtRatio             资产负债率（%）
 * @param revenue               营业总收入（元）
 * @param netProfit             归母净利润（元）
 * @param revenueYoy            营业总收入同比（%）
 * @param netProfitYoy          归母净利润同比（%）
 * @param bps                   每股净资产（元）
 * @param eps                   基本每股收益（元）
 * @param industryPe            行业市盈率
 * @param pePremiumToIndustry   个股 PE 相对行业 PE 的溢价率（%）
 * @param dataSource            数据来源标识：eastmoney / estimated
 * @param notice                数据来源说明，前端直接展示
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FundamentalOverview(
        String code,
        String name,
        String industry,
        Double pe,
        Double peStatic,
        Double peTtm,
        Double pb,
        Double roe,
        Double grossMargin,
        boolean grossMarginApplicable,
        Double netMargin,
        Double debtRatio,
        Double revenue,
        Double netProfit,
        Double revenueYoy,
        Double netProfitYoy,
        Double bps,
        Double eps,
        Double industryPe,
        Double pePremiumToIndustry,
        String dataSource,
        String notice
) {
}
