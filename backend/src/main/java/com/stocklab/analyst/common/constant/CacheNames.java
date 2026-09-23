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

    private CacheNames() {
    }
}
