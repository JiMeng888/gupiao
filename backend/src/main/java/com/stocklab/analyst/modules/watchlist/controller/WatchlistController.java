package com.stocklab.analyst.modules.watchlist.controller;

import com.stocklab.analyst.common.api.ApiResult;
import com.stocklab.analyst.common.ratelimit.RateLimit;
import com.stocklab.analyst.common.ratelimit.RateLimitScope;
import com.stocklab.analyst.modules.watchlist.dto.WatchlistGroupCreateRequest;
import com.stocklab.analyst.modules.watchlist.dto.WatchlistGroupView;
import com.stocklab.analyst.modules.watchlist.dto.WatchlistItemCreateRequest;
import com.stocklab.analyst.modules.watchlist.dto.WatchlistItemView;
import com.stocklab.analyst.modules.watchlist.service.WatchlistService;
import com.stocklab.analyst.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 自选股接口（全部需要登录）。
 *
 * <p>所有方法通过 {@code @CurrentUserId} 获取用户 ID，
 * 该参数在缺失或无效时由参数解析器直接抛 401，
 * 因此 Service 层可以放心地把 userId 当作非空使用。
 */
@RestController
@RequestMapping("/api/v1/watchlist")
@Tag(name = "自选股", description = "自选分组与条目的增删查")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @GetMapping("/groups")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "分组列表", description = "用户首次访问时自动创建默认分组")
    public ApiResult<List<WatchlistGroupView>> listGroups(@CurrentUserId String userId) {
        return ApiResult.ok(watchlistService.listGroups(userId));
    }

    @PostMapping("/groups")
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "创建分组")
    public ApiResult<WatchlistGroupView> createGroup(@CurrentUserId String userId,
                                                     @Valid @RequestBody WatchlistGroupCreateRequest request) {
        return ApiResult.ok(watchlistService.createGroup(userId, request));
    }

    @DeleteMapping("/groups/{groupId}")
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "删除分组", description = "级联删除组内全部自选")
    public ApiResult<Map<String, Boolean>> deleteGroup(@CurrentUserId String userId,
                                                       @PathVariable String groupId) {
        watchlistService.deleteGroup(userId, groupId);
        return ApiResult.ok(Map.of("ok", true));
    }

    @GetMapping("/items")
    @RateLimit(RateLimitScope.LOOSE)
    @Operation(summary = "自选列表", description = "已合并实时行情，可直接渲染")
    public ApiResult<List<WatchlistItemView>> listItems(
            @CurrentUserId String userId,
            @RequestParam(required = false) String groupId) {
        return ApiResult.ok(watchlistService.listItems(userId, groupId));
    }

    @PostMapping("/items")
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "加入自选", description = "groupId 可省略，缺省时加入默认分组")
    public ApiResult<WatchlistItemView> addItem(@CurrentUserId String userId,
                                                @Valid @RequestBody WatchlistItemCreateRequest request) {
        return ApiResult.ok(watchlistService.addItem(userId, request));
    }

    @DeleteMapping("/items/{itemId}")
    @RateLimit(RateLimitScope.STRICT)
    @Operation(summary = "移除自选")
    public ApiResult<Map<String, Boolean>> removeItem(@CurrentUserId String userId,
                                                      @PathVariable String itemId) {
        watchlistService.removeItem(userId, itemId);
        return ApiResult.ok(Map.of("ok", true));
    }
}
