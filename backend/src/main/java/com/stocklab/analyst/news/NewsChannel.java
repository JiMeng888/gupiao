package com.stocklab.analyst.news;

import java.util.Locale;

/**
 * 新浪财经资讯频道。
 *
 * <p>上游用 {@code lid} 区分频道。这些 lid 是新浪 feed 接口的固定栏目编号，
 * 不是自增 ID，改动频率极低（其中 2516 已稳定运行十年以上）。
 *
 * <p><b>为什么用枚举而不是字符串常量</b>：频道标识会在「前端传参 → Service 校验 →
 * 上游 URL 拼接」三处出现。用字符串时，任何一处写错都只是表现为「这个频道没数据」，
 * 不报错；用枚举可以在编译期拦住，且 {@code fromId} 能把非法输入明确转成 400。
 */
public enum NewsChannel {

    FINANCE("finance", "2516", "财经要闻", "财经"),
    A_SHARES("a-shares", "2517", "A股", "A股"),
    HK("hk", "2518", "港股", "港股"),
    US("us", "2519", "美股", "美股"),
    GLOBAL("global", "2514", "全球市场", "国际");

    private final String id;
    private final String lid;
    private final String displayName;
    private final String tag;

    NewsChannel(String id, String lid, String displayName, String tag) {
        this.id = id;
        this.lid = lid;
        this.displayName = displayName;
        this.tag = tag;
    }

    /** 前端使用的频道标识，如 {@code a-shares}。 */
    public String id() {
        return id;
    }

    /** 上游栏目编号。 */
    public String lid() {
        return lid;
    }

    /** 中文频道名，同时会作为第一条标签写进新闻条目。 */
    public String displayName() {
        return displayName;
    }

    /** 聚合视图下展示用的短标签。 */
    public String tag() {
        return tag;
    }

    /**
     * 按前端标识解析频道，忽略大小写与首尾空白。
     *
     * @return 匹配的频道；无法匹配时返回 {@code null}（由调用方决定是报错还是回退）
     */
    public static NewsChannel fromId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (NewsChannel channel : values()) {
            if (channel.id.equals(normalized)) {
                return channel;
            }
        }
        return null;
    }
}
