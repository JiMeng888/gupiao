package com.stocklab.analyst.modules.news.dto;

/**
 * 资讯频道元信息（供前端渲染频道标签页）。
 *
 * @param id   前端传参用的频道标识，如 {@code all} / {@code a-shares}
 * @param name 频道中文名
 * @param tag  短标签
 */
public record NewsChannelView(String id, String name, String tag) {
}
