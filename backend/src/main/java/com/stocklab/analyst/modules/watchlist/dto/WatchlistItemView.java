package com.stocklab.analyst.modules.watchlist.dto;

/**
 * 自选股条目视图（含实时行情）。
 *
 * <p>原 FastAPI 版本只返回 {@code id/code/group_id/sort_order}，
 * 前端要展示价格与涨跌幅就必须对每条自选单独再发一次报价请求——
 * 20 条自选就是 21 次请求。这里在服务端合并行情，
 * 前端一次拿到可直接渲染的列表。
 *
 * <p>{@code price} 等行情字段可为 {@code null}：行情源暂时不可用时，
 * 自选列表仍应正常返回（只是不显示价格），而不是整列表失败。
 *
 * @param id        条目 ID
 * @param code      股票代码
 * @param name      股票名称
 * @param groupId   所属分组 ID
 * @param sortOrder 排序值
 * @param createdAt 加入时间（秒级时间戳）
 * @param price     最新价
 * @param change    涨跌额
 * @param changePct 涨跌幅（百分比数值）
 */
public record WatchlistItemView(
        String id,
        String code,
        String name,
        String groupId,
        Integer sortOrder,
        Long createdAt,
        Double price,
        Double change,
        Double changePct
) {
}
