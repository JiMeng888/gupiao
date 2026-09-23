package com.stocklab.analyst.market.model;

/**
 * 单根 K 线。
 *
 * <p>{@code prevClose} 用于计算该根 K 线的涨跌幅。上游日线接口不逐根返回昨收，
 * 由 {@link com.stocklab.analyst.market.MarketDataProvider} 在组装序列时
 * 用「前一根的收盘价」回填；序列首根的昨收取上游给出的基准价。
 *
 * @param date      日期或日期时间（日线及以上为 {@code yyyy-MM-dd}，分钟线为 {@code yyyy-MM-dd HH:mm}）
 * @param open      开盘价
 * @param high      最高价
 * @param low       最低价
 * @param close     收盘价
 * @param volume    成交量（手）
 * @param amount    成交额（元）
 * @param prevClose 昨收价，用于计算当日涨跌幅
 */
public record Bar(
        String date,
        double open,
        double high,
        double low,
        double close,
        long volume,
        Double amount,
        Double prevClose
) {
}
