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
 * <h2>为什么要有 marketCap 与 delayMinutes</h2>
 * 个股页报价头需要「总市值」与「市盈率 TTM」（对标主流行情产品的头部信息区），
 * 以及一条合规硬要求：<b>使用非实时行情时必须在价格附近明示延迟时长</b>。
 * 这两件事都不能由前端推断——总市值无法从价格算出，
 * 延迟时长必须由数据源声明。
 *
 * <p><b>拿不到就必须是 null / 0，不能编造</b>：
 * <ul>
 *   <li>{@code marketCap} 为 {@code null} 时前端渲染 {@code --}。
 *       各数据源能力不同（并非都能取到总市值），与其给一个
 *       「看起来正常其实无关的数字」，不如明确标注取不到。
 *       mock 源一律返回 {@code null}——演示数据配一个市值等于凭空造数。</li>
 *   <li>{@code delayMinutes} 是<b>声明值</b>不是实测值，0 表示数据源声明为实时。
 *       真正的「数据是否新鲜」由 {@code ts} 与当前时间的差值判断，
 *       前端据此再叠加「已收盘 / 数据陈旧」提示，两者互不替代。</li>
 * </ul>
 *
 * @param code         6 位股票代码
 * @param name         股票名称
 * @param exchange     交易所：SH / SZ / BJ
 * @param price        最新价
 * @param change       涨跌额
 * @param changePct    涨跌幅（百分比数值，如 0.71 表示 +0.71%）
 * @param open         今开
 * @param high         最高
 * @param low          最低
 * @param prevClose    昨收
 * @param volume       成交量（手）
 * @param turnover     换手率（百分比数值）
 * @param amount       成交额（元），上游缺字段时为 null
 * @param ts           快照时间戳（秒）
 * @param marketCap    总市值（元）；不可得时为 null
 * @param delayMinutes 行情延迟分钟数（数据源声明）；0 表示实时
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
        long ts,
        Double marketCap,
        int delayMinutes
) {

    /**
     * 兼容构造：无市值、声明为实时。
     *
     * <p>只用于「按成交价构造估值代理」这类场景（见 {@code HoldingService}）——
     * 那里没有真实行情，给市值或延迟声明都是编造，因此留空最诚实。
     */
    public Quote(
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
        this(code, name, exchange, price, change, changePct, open, high, low, prevClose,
                volume, turnover, amount, ts, null, 0);
    }

    /**
     * 返回一份盖了延迟声明的副本。
     *
     * <p>其余字段一笔不动——延迟是「这条数据怎么来的」的元信息，
     * 与具体数值无关，混在一起改反而容易出错。
     */
    public Quote withDelayMinutes(int minutes) {
        return new Quote(code, name, exchange, price, change, changePct, open, high, low,
                prevClose, volume, turnover, amount, ts, marketCap, Math.max(0, minutes));
    }
}
