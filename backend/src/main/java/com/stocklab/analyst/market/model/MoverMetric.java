package com.stocklab.analyst.market.model;

/**
 * 市场榜单的排序维度。
 *
 * <p>当前只有两个维度是有真实上游数据支撑的：
 * <ul>
 *   <li>{@link #CHANGE_PCT} 涨跌幅——配合升序即「跌幅榜」</li>
 *   <li>{@link #AMOUNT} 成交额</li>
 * </ul>
 *
 * <p>枚举里携带了真实源（东方财富 {@code clist/get}）的排序字段码，
 * 让「排序维度 → 上游参数」的映射只存在一处，上游换字段时不用翻实现类。
 */
public enum MoverMetric {

    /** 按涨跌幅排序（百分比数值，如 3.42 表示 +3.42%）。 */
    CHANGE_PCT("f3"),

    /** 按成交额排序（单位：元）。 */
    AMOUNT("f6");

    private final String eastmoneyFid;

    MoverMetric(String eastmoneyFid) {
        this.eastmoneyFid = eastmoneyFid;
    }

    /** 真实源（东财 {@code clist/get}）对应的 {@code fid} 排序字段码。 */
    public String eastmoneyFid() {
        return eastmoneyFid;
    }

    /**
     * 宽容解析请求参数，非法值返回 {@code null} 而不是抛异常，
     * 让 Controller 决定如何把 400 说清楚。
     *
     * <p>比较时忽略大小写并<b>忽略下划线</b>：对外参数是 {@code changePct}
     * （小驼峰），而枚举常量名是 {@code CHANGE_PCT}（带下划线）。
     * 直接 {@code valueOf(raw.toUpperCase())} 会让合法参数
     * {@code changePct} 变成 400——这个坑在联调时真实发生过，
     * 当时 {@code amount} 恰好能走通，问题被它的成功掩盖了。
     */
    public static MoverMetric parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().replace("_", "").toUpperCase();
        for (MoverMetric metric : values()) {
            if (metric.name().replace("_", "").equals(normalized)) {
                return metric;
            }
        }
        return null;
    }
}
