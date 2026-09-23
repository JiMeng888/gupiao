package com.stocklab.analyst.modules.quote.dto;

/**
 * 首页轮播卡片。
 *
 * @param code      股票代码
 * @param name      股票名称
 * @param price     最新价
 * @param change    涨跌额
 * @param changePct 涨跌幅（百分比数值）
 * @param highlight 一句话看点文案
 */
public record CarouselItem(
        String code,
        String name,
        double price,
        double change,
        double changePct,
        String highlight
) {
}
