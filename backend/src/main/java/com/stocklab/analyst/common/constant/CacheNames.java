package com.stocklab.analyst.common.constant;

/**
 * 缓存名称常量。
 *
 * <p>集中定义避免 {@code @Cacheable("quote")} 这种字面量散落各处——
 * 一旦拼写不一致，缓存会静默地命中不了，且没有任何报错。
 */
public final class CacheNames {

    /** 全 A 股清单（代码 / 名称 / 交易所），搜索用。变化频率极低。 */
    public static final String STOCK_LIST = "stockList";

    /** 实时报价快照。TTL 很短，主要是削掉同一秒内的重复上游请求。 */
    public static final String QUOTE = "quote";

    /** K 线序列。TTL 较长。 */
    public static final String BARS = "bars";

    /** 财务基本面。披露频率为季度，TTL 可以很长。 */
    public static final String FUNDAMENTAL = "fundamental";

    /** 财经资讯列表。 */
    public static final String NEWS = "news";

    /**
     * 板块榜单。盘中会持续变化，但不必秒级刷新。
     *
     * <p><b>本缓存的 key 必须带数据源前缀</b>（{@code eastmoney:10} / {@code tencent:10}）：
     * 两个源的板块口径不同（东财 496 个混层级板块 vs 腾讯 31 个一级行业），
     * 而它们共用这个缓存名。若沿用裸 {@code limit} 作为 key，一旦东财失败、腾讯
     * 写入 key {@code 10}，东财恢复后就会<b>命中腾讯的列表并当成自己的结果返回</b>，
     * 面板的板块粒度会随缓存状态漂移，且没有任何报错。
     *
     * <p>报价与 K 线的 key 没加前缀：那边两侧都是真实行情、语义相同，
     * 碰撞最多让 {@code servingByAction} 的来源标注偏一次 TTL，不影响数据含义。
     * 两类数据的处理不同是刻意的，不要「顺手统一」。
     */
    public static final String SECTORS = "sectors";

    private CacheNames() {
    }
}
