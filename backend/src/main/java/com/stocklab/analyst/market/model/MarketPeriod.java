package com.stocklab.analyst.market.model;

import java.util.Locale;
import java.util.Optional;

/**
 * K 线周期。
 *
 * <p>{@code upstreamCode} 是东方财富 {@code klt} 参数值。
 * 把上游编码收敛在枚举里，业务层与前端只使用 {@code daily / weekly / ...}
 * 这类语义别名——上游若更换服务商，改动只落在这个枚举。
 *
 * <p>关于「分时」：原 FastAPI 版本的分时是一条连接最新价的走势线，
 * 需要单独的趋势接口。这里同样通过 {@code trends2} 端点实现
 * （{@link #TREND}），而不是用 1 分钟 K 线近似，以保证前端形态一致。
 */
public enum MarketPeriod {

    /** 分时（当日逐分钟成交，含均价线）。 */
    TREND("trend"),

    /** 1 分钟 K 线。 */
    MIN1("1"),

    /** 5 分钟 K 线。 */
    MIN5("5"),

    /** 15 分钟 K 线。 */
    MIN15("15"),

    /** 30 分钟 K 线。 */
    MIN30("30"),

    /** 60 分钟 K 线。 */
    MIN60("60"),

    /** 日 K。 */
    DAILY("101"),

    /** 周 K。 */
    WEEKLY("102"),

    /** 月 K。 */
    MONTHLY("103");

    private final String upstreamCode;

    MarketPeriod(String upstreamCode) {
        this.upstreamCode = upstreamCode;
    }

    public String upstreamCode() {
        return upstreamCode;
    }

    /** 是否为分钟级（含分时）：分钟级数据的日期字段带时间部分。 */
    public boolean isIntraday() {
        return this == TREND || name().startsWith("MIN");
    }

    /**
     * 解析前端传入的周期别名。
     *
     * <p>兼容多种写法（{@code daily / day / d / 101}），
     * 避免前端大小写或命名差异导致 400，同时保持服务端为唯一事实来源。
     */
    public static Optional<MarketPeriod> fromAlias(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(DAILY);
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "trend", "min", "fenshi", "intraday", "分时" -> Optional.of(TREND);
            case "1", "1min", "min1", "m1" -> Optional.of(MIN1);
            case "5", "5min", "min5", "m5" -> Optional.of(MIN5);
            case "15", "15min", "min15", "m15" -> Optional.of(MIN15);
            case "30", "30min", "min30", "m30" -> Optional.of(MIN30);
            case "60", "60min", "min60", "m60", "hour", "1h" -> Optional.of(MIN60);
            case "101", "daily", "day", "d", "日", "日k" -> Optional.of(DAILY);
            case "102", "weekly", "week", "w", "周", "周k" -> Optional.of(WEEKLY);
            case "103", "monthly", "month", "mo", "月", "月k" -> Optional.of(MONTHLY);
            default -> Optional.empty();
        };
    }
}
