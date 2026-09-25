package com.stocklab.analyst.news;

import java.util.Locale;

/**
 * 新浪财经资讯频道。
 *
 * <p>上游 feed 用<b>两个独立参数</b>定位栏目：{@code pageid}（站点/页面分组）与
 * {@code lid}（栏目号）。二者必须成对匹配才注册，任取错一个都只会得到
 * {@code code=11 "列表和页面没有经过注册！"}，而不是报错——所以两者都要逐频道配置。
 *
 * <p><b>实测背景</b>：{@code pageid=153} 下只注册了 2509~2518 这一批栏目；
 * 美股页 {@code finance.sina.com.cn/stock/usstock/} 挂的是 {@code pageid=384&lid=2672}。
 * 原先代码把 {@code pageid=153} 硬编码在 URL 里，因此无法表达这种跨 pageid 的频道，
 * 美股频道（原 lid=2519）也就一直取不到数据。
 *
 * <p><b>为什么不选 2673</b>：{@code pageid=384&lid=2673} 标题确实是纯美股/中概股，
 * 看起来更贴合「美股」这个名字，但它最新一条停留在 2025-04，已停更一年多。
 * 首页「最新资讯」展示陈旧新闻比展示偏宏观的内容更糟，故采用仍在日更的 2672。
 *
 * <p><b>为什么用枚举而不是字符串常量</b>：频道标识会在「前端传参 → Service 校验 →
 * 上游 URL 拼接」三处出现。用字符串时，任何一处写错都只是表现为「这个频道没数据」，
 * 不报错；用枚举可以在编译期拦住，且 {@code fromId} 能把非法输入明确转成 400。
 */
public enum NewsChannel {

    FINANCE("finance", "153", "2516", "财经要闻", "财经"),
    A_SHARES("a-shares", "153", "2517", "A股", "A股"),
    HK("hk", "153", "2518", "港股", "港股"),
    // pageid 不是 153，且 2672 是「环球市场播报」而非纯美股：见类注释
    US("us", "384", "2672", "美股", "美股"),
    GLOBAL("global", "153", "2514", "全球市场", "国际");

    private final String id;
    private final String pageid;
    private final String lid;
    private final String displayName;
    private final String tag;

    NewsChannel(String id, String pageid, String lid, String displayName, String tag) {
        this.id = id;
        this.pageid = pageid;
        this.lid = lid;
        this.displayName = displayName;
        this.tag = tag;
    }

    /** 前端使用的频道标识，如 {@code a-shares}。 */
    public String id() {
        return id;
    }

    /** 上游页面分组编号。与 {@link #lid()} 成对使用，缺一不可。 */
    public String pageid() {
        return pageid;
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
