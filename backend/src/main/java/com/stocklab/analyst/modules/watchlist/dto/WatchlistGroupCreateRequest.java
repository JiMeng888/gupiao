package com.stocklab.analyst.modules.watchlist.dto;

import jakarta.validation.constraints.Size;

/**
 * 创建自选分组请求。
 *
 * @param name      分组名称
 * @param sortOrder 排序值，越小越靠前
 * @param parentId  父分组 ID，可选（支持两级分组）
 */
public record WatchlistGroupCreateRequest(
        @Size(min = 1, max = 32, message = "分组名称长度需在 1~32 字之间")
        String name,

        Integer sortOrder,

        String parentId
) {
}
