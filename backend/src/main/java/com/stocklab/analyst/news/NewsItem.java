package com.stocklab.analyst.news;

import java.util.List;

/**
 * 单条财经资讯。
 *
 * @param id       唯一标识（上游 docid，缺失时由时间戳 + 标题生成）
 * @param title    标题
 * @param url      原文链接（PC 版）
 * @param intro    摘要。上游可能在 {@code intro} 与 {@code wapsummary} 之间只填一个，
 *                 解析时已做兜底；两者都为空时是空字符串，不是 {@code null}
 * @param source   媒体名，如「证券时报」。上游缺失时兜底为「新浪财经」
 * @param tags     标签列表，频道名固定为第一个，其余来自上游 keywords（最多 5 个）
 * @param ctime    发布时间，Unix 秒。<b>用 long 而不是格式化的字符串</b>，
 *                 方便前端按本地时区自行格式化与做「几小时前」的相对时间
 * @param timeText 服务端按北京时间预格式化的 {@code MM-dd HH:mm}
 * @param channel  所属频道中文名；聚合视图下同一批次可能来自不同频道
 */
public record NewsItem(
        String id,
        String title,
        String url,
        String intro,
        String source,
        List<String> tags,
        long ctime,
        String timeText,
        String channel
) {
}
