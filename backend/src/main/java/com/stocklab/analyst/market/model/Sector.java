package com.stocklab.analyst.market.model;

/**
 * 板块榜单条目。
 *
 * <p>对标主流行情产品右侧「热门板块」区：板块名 + 涨跌幅 + 领涨股。
 * 只展示<b>可核验</b>的聚合信息，不给出「推荐买入」之类的结论。
 *
 * <h2>为什么上升/下跌家数要一起返回</h2>
 * 单看板块涨跌幅无法判断「是普涨还是被一两只权重股拉起来的」。
 * 上涨家数与下跌家数是对板块涨跌幅的<b>自校验</b>：
 * 一个 +3% 但下跌家数远大于上涨家数的板块，说明内部严重分化。
 * 缺了这两个数字，涨跌幅这个指标就容易被误读。
 *
 * @param code       板块代码（如 {@code BK1295}）
 * @param name       板块名称
 * @param changePct  板块涨跌幅（百分比数值）
 * @param upCount    成分股上涨家数；上游缺失时为 null
 * @param downCount  成分股下跌家数；上游缺失时为 null
 * @param leaderName 领涨股名称；上游缺失时为 null
 * @param leaderCode 领涨股 6 位代码；上游缺失时为 null。
 *                   有了它前端才能从板块直接跳到个股——板块榜的价值一半在
 *                   「哪个方向在涨」，另一半在「谁在带」，只能看不能点等于只给了一半
 * @param leaderPct  领涨股涨跌幅（百分比数值）；上游缺失时为 null
 */
public record Sector(
        String code,
        String name,
        double changePct,
        Integer upCount,
        Integer downCount,
        String leaderName,
        String leaderCode,
        Double leaderPct
) {
}
