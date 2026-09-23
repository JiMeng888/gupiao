package com.stocklab.analyst.market.model;

/**
 * 交易所。
 *
 * <p>{@code upstreamMarket} 是东方财富 secid 的市场前缀：
 * {@code 1} = 上交所，{@code 0} = 深交所 / 北交所。
 * 北交所在该体系中复用 {@code 0}，因此不能反过来用市场前缀推断交易所。
 */
public enum Exchange {

    /** 上海证券交易所。 */
    SH("1", "SH"),

    /** 深圳证券交易所。 */
    SZ("0", "SZ"),

    /** 北京证券交易所。 */
    BJ("0", "BJ");

    private final String upstreamMarket;
    private final String display;

    Exchange(String upstreamMarket, String display) {
        this.upstreamMarket = upstreamMarket;
        this.display = display;
    }

    public String upstreamMarket() {
        return upstreamMarket;
    }

    public String display() {
        return display;
    }

    public static Exchange fromDisplay(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toUpperCase()) {
            case "SH", "SS", "SSE", "1" -> SH;
            case "SZ", "SZSE", "0" -> SZ;
            case "BJ", "BSE" -> BJ;
            default -> null;
        };
    }
}
