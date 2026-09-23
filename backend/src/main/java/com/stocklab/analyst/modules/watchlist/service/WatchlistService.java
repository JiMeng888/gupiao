package com.stocklab.analyst.modules.watchlist.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.watchlist.dto.WatchlistGroupCreateRequest;
import com.stocklab.analyst.modules.watchlist.dto.WatchlistGroupView;
import com.stocklab.analyst.modules.watchlist.dto.WatchlistItemCreateRequest;
import com.stocklab.analyst.modules.watchlist.dto.WatchlistItemView;
import com.stocklab.analyst.modules.watchlist.entity.WatchlistGroup;
import com.stocklab.analyst.modules.watchlist.entity.WatchlistItem;
import com.stocklab.analyst.modules.watchlist.mapper.WatchlistGroupMapper;
import com.stocklab.analyst.modules.watchlist.mapper.WatchlistItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自选股服务。
 *
 * <p><b>越权防护是本类的第一要务</b>。所有查询与写入都必须带
 * {@code user_id} 条件；按 ID 定位条目时，条件必须是
 * 「id = ? AND user_id = ?」而不是「先查出来再判断 user_id 是否匹配」——
 * 前者是一条语句的原子判断，后者需要额外的代码路径，
 * 任何一处忘记判断就是越权漏洞。
 *
 * <p>修复了原 FastAPI 版本的一个真实缺陷：原前端硬编码
 * {@code group_id: 'g-default'}，但后端的分组 ID 是 UUID，
 * 从不存在 {@code g-default} 这个分组，导致「加入自选」实际会写入
 * 一个悬空的分组 ID（数据写得进去，但按分组查询永远查不到）。
 * 这里改为<b>懒创建默认分组</b>：分组为空时自动建一个，
 * {@code groupId} 缺省时落到默认分组。
 */
@Service
public class WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);

    /** 默认分组名称。 */
    public static final String DEFAULT_GROUP_NAME = "我的自选";

    /** 单用户自选条目上限：防止恶意脚本无限写入把库撑大。 */
    private static final int MAX_ITEMS_PER_USER = 500;

    /** 列表查询时最多附带多少条行情，避免一次打爆上游。 */
    private static final int MAX_QUOTE_ENRICH = 100;

    private final WatchlistGroupMapper groupMapper;
    private final WatchlistItemMapper itemMapper;
    private final MarketDataProvider marketDataProvider;

    public WatchlistService(WatchlistGroupMapper groupMapper,
                            WatchlistItemMapper itemMapper,
                            MarketDataProvider marketDataProvider) {
        this.groupMapper = groupMapper;
        this.itemMapper = itemMapper;
        this.marketDataProvider = marketDataProvider;
    }

    // ==================================================================
    // 分组
    // ==================================================================

    /**
     * 列出分组（含条目数）。用户从未建过分组时，懒创建一个默认分组。
     *
     * <p>懒创建是幂等的：并发首次访问时两个请求可能同时尝试创建，
     * 靠 {@code uk_watchlist_group_user_name} 唯一索引兜底，
     * 冲突方捕获 {@link DuplicateKeyException} 后改为查询已有分组，
     * 因此不会出现重复默认分组，也不会报错给用户。
     */
    public List<WatchlistGroupView> listGroups(String userId) {
        List<WatchlistGroup> groups = selectGroups(userId);
        if (groups.isEmpty()) {
            ensureDefaultGroup(userId);
            groups = selectGroups(userId);
        }
        return toGroupViews(userId, groups);
    }

    public WatchlistGroupView createGroup(String userId, WatchlistGroupCreateRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAMS, "分组名称不能为空");
        }

        WatchlistGroup group = new WatchlistGroup();
        group.setUserId(userId);
        group.setName(name);
        group.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        group.setParentId(request.parentId());

        try {
            groupMapper.insert(group);
        } catch (DuplicateKeyException ex) {
            throw new BizException(ErrorCode.DUPLICATE_ITEM, "同名分组已存在");
        }
        return new WatchlistGroupView(group.getId(), group.getName(), group.getSortOrder(),
                group.getParentId(), 0);
    }

    /**
     * 删除分组及其下全部自选。
     *
     * <p>加 {@code @Transactional} 是必要的：这里包含「删条目」+「删分组」
     * 两条写语句，不加事务时若第二条失败，会留下一个没有分组的孤儿条目，
     * 用户既看不到也删不掉。
     */
    @Transactional
    public void deleteGroup(String userId, String groupId) {
        // 必须校验归属：否则可以传别人的 groupId 删掉别人的自选
        if (groupMapper.countOwnedBy(groupId, userId) == 0) {
            throw new BizException(ErrorCode.GROUP_NOT_FOUND);
        }
        itemMapper.delete(Wrappers.<WatchlistItem>lambdaQuery()
                .eq(WatchlistItem::getUserId, userId)
                .eq(WatchlistItem::getGroupId, groupId));
        groupMapper.delete(Wrappers.<WatchlistGroup>lambdaQuery()
                .eq(WatchlistGroup::getId, groupId)
                .eq(WatchlistGroup::getUserId, userId));
        log.info("删除自选分组 userId={} groupId={}", userId, groupId);
    }

    // ==================================================================
    // 自选条目
    // ==================================================================

    /**
     * 列出自选条目（附带实时行情）。
     *
     * <p>行情获取失败只影响该条的展示字段，不影响条目本身返回——
     * 自选列表是高频页面，不能因为上游抖动就整页失败。
     */
    public List<WatchlistItemView> listItems(String userId, String groupId) {
        List<WatchlistItem> items = itemMapper.selectList(Wrappers.<WatchlistItem>lambdaQuery()
                .eq(WatchlistItem::getUserId, userId)
                .eq(groupId != null && !groupId.isBlank(), WatchlistItem::getGroupId, groupId)
                .orderByAsc(WatchlistItem::getSortOrder)
                .orderByAsc(WatchlistItem::getCreatedAt));

        if (items.isEmpty()) {
            return List.of();
        }

        // 同一批里同一个 code 只查一次行情（不同分组可能持有同一只股票）
        Map<String, Quote> quoteCache = new HashMap<>();
        int enriched = 0;
        for (WatchlistItem item : items) {
            if (enriched >= MAX_QUOTE_ENRICH) {
                break;
            }
            if (!quoteCache.containsKey(item.getCode())) {
                try {
                    quoteCache.put(item.getCode(), marketDataProvider.getQuote(item.getCode()));
                } catch (RuntimeException ex) {
                    quoteCache.put(item.getCode(), null);
                    log.debug("自选行情获取失败 code={}：{}", item.getCode(), ex.getMessage());
                }
                enriched++;
            }
        }

        List<WatchlistItemView> views = new ArrayList<>(items.size());
        for (WatchlistItem item : items) {
            Quote quote = quoteCache.get(item.getCode());
            views.add(new WatchlistItemView(
                    item.getId(),
                    item.getCode(),
                    quote != null ? quote.name() : null,
                    item.getGroupId(),
                    item.getSortOrder(),
                    item.getCreatedAt(),
                    quote != null ? quote.price() : null,
                    quote != null ? quote.change() : null,
                    quote != null ? quote.changePct() : null));
        }
        return views;
    }

    /**
     * 添加自选。
     *
     * <p>重复添加的判定交给唯一索引 + 捕获冲突，而不是「先 SELECT 再 INSERT」：
     * 后者在并发下两个请求可能同时查到「不存在」然后双双插入，
     * 出现重复数据。数据库唯一约束是唯一可靠的去重手段。
     *
     * <p>同时校验目标分组的归属，避免把股票写进别人的分组。
     */
    public WatchlistItemView addItem(String userId, WatchlistItemCreateRequest request) {
        String code = normalizeCode(request.code());
        String groupId = resolveGroupId(userId, request.groupId());

        long currentCount = itemMapper.selectCount(Wrappers.<WatchlistItem>lambdaQuery()
                .eq(WatchlistItem::getUserId, userId));
        if (currentCount >= MAX_ITEMS_PER_USER) {
            throw new BizException(ErrorCode.INVALID_PARAMS,
                    "自选股数量已达上限（" + MAX_ITEMS_PER_USER + " 条）");
        }

        WatchlistItem item = new WatchlistItem();
        item.setUserId(userId);
        item.setGroupId(groupId);
        item.setCode(code);
        item.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());

        try {
            itemMapper.insert(item);
        } catch (DuplicateKeyException ex) {
            throw new BizException(ErrorCode.DUPLICATE_ITEM);
        }

        Quote quote = null;
        try {
            quote = marketDataProvider.getQuote(code);
        } catch (RuntimeException ex) {
            log.debug("新增自选后取价失败 code={}：{}", code, ex.getMessage());
        }

        return new WatchlistItemView(item.getId(), item.getCode(),
                quote != null ? quote.name() : null,
                item.getGroupId(), item.getSortOrder(), item.getCreatedAt(),
                quote != null ? quote.price() : null,
                quote != null ? quote.change() : null,
                quote != null ? quote.changePct() : null);
    }

    /**
     * 删除自选。
     *
     * <p>删除条件同时包含 {@code id} 与 {@code user_id}，
     * 一条语句完成「存在性 + 归属」判断。受影响行数为 0 即视为不存在，
     * 统一返回 404——不区分「不存在」与「不属于你」，
     * 避免通过错误码差异探测他人数据的存在性。
     */
    public void removeItem(String userId, String itemId) {
        int affected = itemMapper.delete(Wrappers.<WatchlistItem>lambdaQuery()
                .eq(WatchlistItem::getId, itemId)
                .eq(WatchlistItem::getUserId, userId));
        if (affected == 0) {
            throw new BizException(ErrorCode.ITEM_NOT_FOUND);
        }
    }

    // ==================================================================

    private List<WatchlistGroup> selectGroups(String userId) {
        return groupMapper.selectList(Wrappers.<WatchlistGroup>lambdaQuery()
                .eq(WatchlistGroup::getUserId, userId)
                .orderByAsc(WatchlistGroup::getSortOrder)
                .orderByAsc(WatchlistGroup::getName));
    }

    private List<WatchlistGroupView> toGroupViews(String userId, List<WatchlistGroup> groups) {
        List<WatchlistItem> allItems = itemMapper.selectList(Wrappers.<WatchlistItem>lambdaQuery()
                .eq(WatchlistItem::getUserId, userId)
                .select(WatchlistItem::getGroupId));
        Map<String, Long> countByGroup = new HashMap<>();
        for (WatchlistItem item : allItems) {
            countByGroup.merge(item.getGroupId(), 1L, Long::sum);
        }
        return groups.stream()
                .map(g -> new WatchlistGroupView(g.getId(), g.getName(), g.getSortOrder(),
                        g.getParentId(), countByGroup.getOrDefault(g.getId(), 0L)))
                .toList();
    }

    /** 懒创建默认分组（幂等）。 */
    private void ensureDefaultGroup(String userId) {
        WatchlistGroup group = new WatchlistGroup();
        group.setUserId(userId);
        group.setName(DEFAULT_GROUP_NAME);
        group.setSortOrder(0);
        try {
            groupMapper.insert(group);
            log.info("为用户 {} 创建默认自选分组", userId);
        } catch (DuplicateKeyException ex) {
            // 并发首次访问：已有请求建好了，忽略即可
            log.debug("默认分组已存在，忽略重复创建 userId={}", userId);
        }
    }

    /** 解析目标分组：显式传入则校验归属，缺省则用默认分组。 */
    private String resolveGroupId(String userId, String requestedGroupId) {
        if (requestedGroupId != null && !requestedGroupId.isBlank()) {
            if (groupMapper.countOwnedBy(requestedGroupId, userId) == 0) {
                throw new BizException(ErrorCode.GROUP_NOT_FOUND);
            }
            return requestedGroupId;
        }

        List<WatchlistGroup> groups = selectGroups(userId);
        if (groups.isEmpty()) {
            ensureDefaultGroup(userId);
            groups = selectGroups(userId);
        }
        return groups.get(0).getId();
    }

    /** 统一代码形式：只保留 6 位数字，去掉可能的大小写后缀差异。 */
    private String normalizeCode(String raw) {
        String trimmed = raw.trim();
        int dot = trimmed.indexOf('.');
        return dot > 0 ? trimmed.substring(0, dot) : trimmed;
    }
}
