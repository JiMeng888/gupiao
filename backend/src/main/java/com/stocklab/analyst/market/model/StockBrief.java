package com.stocklab.analyst.market.model;

/**
 * 股票检索结果条目（搜索结果 / 自选股展示用）。
 *
 * @param code     6 位股票代码
 * @param name     股票名称
 * @param exchange 交易所：SH / SZ / BJ
 */
public record StockBrief(String code, String name, String exchange) {
}
