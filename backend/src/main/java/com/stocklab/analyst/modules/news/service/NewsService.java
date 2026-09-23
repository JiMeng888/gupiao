package com.stocklab.analyst.modules.news.service;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.modules.news.dto.NewsChannelView;
import com.stocklab.analyst.news.NewsChannel;
import com.stocklab.analyst.news.NewsItem;
import com.stocklab.analyst.news.SinaNewsClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财经资讯聚合服务。
 *
 * <p>职责只有两件事：把 {@code all} 展开成五频道聚合，以及把条数限制应用到结果上。
 * 真正的抓取与缓存都在 {@link SinaNewsClient} 里，这里不做 IO。
 */
@Service
public class NewsService {

    /** {@code all} 频道对应的标识。 */
    public static final String CHANNEL_ALL = "all";

    private final SinaNewsClient newsClient;

    public NewsService(SinaNewsClient newsClient) {
        this.newsClient = newsClient;
    }

    /**
     * 获取资讯列表。
     *
     * @param channelId 频道标识；{@code all} 表示聚合五个频道
     * @param limit     返回条数上限
     * @throws BizException 频道标识非法时抛 {@code INVALID_PARAMS}（400），
     *                      而不是静默返回空列表——前端把参数拼错了却看到「暂无资讯」，
     *                      排查成本远高于直接报错
     */
    public List<NewsItem> list(String channelId, int limit) {
        if (isAll(channelId)) {
            return aggregate(limit);
        }

        NewsChannel channel = NewsChannel.fromId(channelId);
        if (channel == null) {
            throw new BizException(ErrorCode.INVALID_PARAMS,
                    "不支持的资讯频道：" + channelId);
        }

        List<NewsItem> items = newsClient.fetchChannel(channel);
        return items.size() > limit ? List.copyOf(items.subList(0, limit)) : items;
    }

    /**
     * 聚合所有频道。
     *
     * <p>去重按链接进行：同一条稿件常会同时出现在「财经要闻」与「A股」两个栏目里，
     * 不去重时列表里会出现两条标题完全一样、只有频道标签不同的内容。
     * 同一链接保留发布时间较新的那条（各栏目的 ctime 可能有几秒差异）。
     *
     * <p>链接为空的条目退化为按 id 去重——不能把 {@code null} 当 key，
     * {@code LinkedHashMap} 允许 null key，但多条空链接会被误判成重复。
     */
    private List<NewsItem> aggregate(int limit) {
        Map<String, NewsItem> deduped = new LinkedHashMap<>();
        for (NewsChannel channel : NewsChannel.values()) {
            for (NewsItem item : newsClient.fetchChannel(channel)) {
                String key = item.url() != null && !item.url().isBlank() ? item.url() : item.id();
                NewsItem existing = deduped.get(key);
                if (existing == null || item.ctime() > existing.ctime()) {
                    deduped.put(key, item);
                }
            }
        }

        List<NewsItem> merged = new ArrayList<>(deduped.values());
        merged.sort(Comparator.comparingLong(NewsItem::ctime).reversed());
        return merged.size() > limit ? List.copyOf(merged.subList(0, limit)) : List.copyOf(merged);
    }

    /** 频道清单，{@code all} 排在最前。 */
    public List<NewsChannelView> channels() {
        List<NewsChannelView> views = new ArrayList<>(NewsChannel.values().length + 1);
        views.add(new NewsChannelView(CHANNEL_ALL, "全部", "综合"));
        for (NewsChannel channel : NewsChannel.values()) {
            views.add(new NewsChannelView(channel.id(), channel.displayName(), channel.tag()));
        }
        return List.copyOf(views);
    }

    private static boolean isAll(String channelId) {
        return channelId == null
                || channelId.isBlank()
                || CHANNEL_ALL.equalsIgnoreCase(channelId.trim());
    }
}
