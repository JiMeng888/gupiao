package com.stocklab.analyst.market.model;

/**
 * 实时报价快照。
 *
 * <p>字段与前端展示项一一对应，避免前端再做二次计算。
 * {@code volume} 单位为「手」（1 手 = 100 股），与国内行情软件口径一致；
 * {@code amount} 单位为「元」。
 *
 * <p>用 record 而非可变 POJO：行情快照一旦生成就不应被修改，
 * 多线程消费（WebSocket 广播）时天然线程安全，不需要防御性拷贝。
 *
 * @param code      6 位股票代码
 * @param name      股票名称
 * @param exchange  交易所：SH / SZ / BJ
 * @param price     最新价
 * @param change    涨跌额
 * @param changePct 涨跌幅（百分比数值，如 0.71 表示 +0.71%）
 * @param open      今开
 * @param high      最高
 * @param low       最低
 * @param prevClose 昨收
 * @param volume    成交量（手）
 * @param turnover  换手率（百分比数值）
 * @param amount    成交额（元），上游缺字段时为 null
 * @param ts        快照时间戳（秒）
 */
public record Quote(
        String code,
        String name,
        String exchange,
        double price,
        double change,
        double changePct,
        double open,
        double high,
        double low,
        double prevClose,
        long volume,
        double turnover,
        Double amount,
        long ts
) {
}
