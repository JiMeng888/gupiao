package com.stocklab.analyst.modules.watchlist.dto;

/**
 * 自选分组视图。
 *
 * @param id        分组 ID
 * @param name      分组名称
 * @param sortOrder 排序值
 * @param parentId  父分组 ID
 * @param itemCount 组内自选股数量（前端侧栏展示角标，免去额外请求）
 */
public record WatchlistGroupView(
        String id,
        String name,
        Integer sortOrder,
        String parentId,
        long itemCount
) {
}
