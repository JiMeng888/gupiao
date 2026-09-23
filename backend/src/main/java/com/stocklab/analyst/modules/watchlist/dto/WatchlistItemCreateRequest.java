package com.stocklab.analyst.modules.watchlist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 新增自选股请求。
 *
 * @param code      股票代码（6 位数字，可带交易所后缀）
 * @param groupId   目标分组 ID；留空则加入默认分组
 * @param sortOrder 排序值
 */
public record WatchlistItemCreateRequest(
        @NotBlank(message = "股票代码不能为空")
        @Pattern(regexp = "^\\d{6}([.](SH|SZ|BJ|sh|sz|bj))?$", message = "股票代码格式不正确")
        String code,

        String groupId,

        Integer sortOrder
) {
}
