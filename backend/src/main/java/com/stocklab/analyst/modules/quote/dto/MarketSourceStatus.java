package com.stocklab.analyst.modules.quote.dto;

import java.util.Map;

/**
 * 数据源状态。
 *
 * <p>供前端在顶栏展示「真实行情 / 演示数据」标识。
 * 原 FastAPI 版本没有这个端点，前端只能靠「能不能连上后端」来粗略判断；
 * 有了它，即使后端存活但已降级到演示数据，前端也能如实告知用户。
 *
 * <p><b>本 DTO 要解决的一个真实误导场景</b>：早期版本只返回
 * 「主数据源是谁 + 是否就绪」两个字段，于是出现过这样的响应——
 * {@code provider=eastmoney, ready=false, displayName=东方财富（真实行情）}，
 * 而同一时刻返回给用户的报价其实是内置模拟值。
 * 前端照着 {@code displayName} 渲染，就会在编造的价格旁边打上
 * 「真实行情」标签。对股票应用来说，这比直接报错危险得多。
 *
 * <p>根因是<b>降级是逐次调用发生的</b>：真实环境里完全可能
 * K 线走真实源、实时报价走降级源。用「一个全局数据源标识」描述
 * 这种状态，无论填哪个都会误导一半的调用方。因此这里拆成：
 * <ul>
 *   <li>{@link #provider} / {@link #ready} —— <b>配置的</b>主数据源及其就绪情况（诊断用）</li>
 *   <li>{@link #degraded} —— 当前是否<b>真的有</b>操作在由降级源服务（判定用）</li>
 *   <li>{@link #servingByAction} —— 每个操作实际由谁服务（定位用）</li>
 *   <li>{@link #notice} —— 给用户看的原因说明，无需降级时为 {@code null}</li>
 * </ul>
 *
 * <p><b>前端渲染规则</b>：
 * <pre>
 *   degraded == true                    → 展示警示样式 + notice（可信度最低）
 *   degraded == false &amp;&amp; ready == false  → 展示「数据源未就绪」，行情可能不完整
 *   degraded == false &amp;&amp; ready == true   → 展示 displayName（真实行情）
 * </pre>
 * 不要把 {@code displayName} 单独当作「当前数据来源」使用。
 *
 * @param provider         配置的主数据源标识
 * @param ready            主数据源是否已就绪（预热完成）
 * @param displayName      主数据源展示名；仅在未降级且已就绪时才代表当前数据来源
 * @param degraded         当前是否存在由降级源提供服务的操作
 * @param servingByAction  各操作当前实际服务源，如 {@code {"getQuote": "mock", "getBars": "eastmoney"}}
 * @param notice           面向用户的提示；无需提示时为 {@code null}
 */
public record MarketSourceStatus(
        String provider,
        boolean ready,
        String displayName,
        boolean degraded,
        Map<String, String> servingByAction,
        String notice
) {

    /** 降级源标识。 */
    private static final String FALLBACK = "mock";

    public static MarketSourceStatus of(String provider,
                                        boolean ready,
                                        boolean degraded,
                                        Map<String, String> servingByAction) {
        String display = displayNameOf(provider);
        return new MarketSourceStatus(
                provider,
                ready,
                display,
                degraded,
                servingByAction == null ? Map.of() : Map.copyOf(servingByAction),
                buildNotice(provider, ready, degraded));
    }

    private static String displayNameOf(String provider) {
        return switch (provider == null ? "" : provider) {
            case "eastmoney" -> "东方财富（真实行情）";
            case FALLBACK -> "内置演示数据";
            default -> provider;
        };
    }

    private static String buildNotice(String provider, boolean ready, boolean degraded) {
        if (degraded) {
            // 关键：把「哪些数据是模拟的」直接说清楚，而不是笼统提示「数据源异常」
            return "上游「" + displayNameOf(provider) + "」不可用，标记为「"
                    + displayNameOf(FALLBACK) + "」的操作正在返回内置模拟数据，"
                    + "请勿据此做出投资决策。详见 servingByAction。";
        }
        if (!ready) {
            return "数据源「" + displayNameOf(provider) + "」尚未就绪（预热未完成），"
                    + "搜索结果可能不完整。";
        }
        return null;
    }
}
