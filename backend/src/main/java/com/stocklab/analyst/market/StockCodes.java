package com.stocklab.analyst.market;

import com.stocklab.analyst.market.model.Exchange;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 股票代码解析工具。
 *
 * <p>需要处理的输入形式（用户在搜索框、前端路由、URL 里都可能这样写）：
 * <ul>
 *   <li>{@code 600519}</li>
 *   <li>{@code 600519.SH} / {@code 600519.sh}</li>
 *   <li>{@code sh600519} / {@code SH600519}</li>
 *   <li>{@code 000001.SH}（上证指数，与深市 000001 平安银行同码不同标的）</li>
 * </ul>
 *
 * <p>关键点：<b>代码本身不足以唯一确定标的</b>。{@code 000001} 既可能是
 * 深市平安银行，也可能是上证指数——前者必须靠后缀显式区分。
 * 因此带后缀时以后缀为准，只有无后缀时才退化为按前缀推断，
 * 并把推断结果标注为「不确定」以便调用方在必要时纠错。
 */
public final class StockCodes {

    private static final Pattern SIX_DIGITS = Pattern.compile("^\\d{6}$");
    private static final Pattern PREFIXED = Pattern.compile("^(sh|sz|bj)(\\d{6})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUFFIXED = Pattern.compile("^(\\d{6})[.](sh|ss|sz|bj)$", Pattern.CASE_INSENSITIVE);

    private StockCodes() {
    }

    /**
     * 解析结果。
     *
     * @param code       6 位纯数字代码
     * @param exchange   交易所
     * @param explicit   交易所是否由显式后缀（{@code .SH} / {@code sh} 前缀）给出
     */
    public record ParsedCode(String code, Exchange exchange, boolean explicit) {

        /** 东方财富 secid，形如 {@code 1.600519}。 */
        public String secId() {
            return exchange.upstreamMarket() + "." + code;
        }
    }

    /**
     * 解析股票代码。
     *
     * @return 解析结果；无法识别时返回 {@code null}
     */
    public static ParsedCode parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().replace(" ", "");
        if (value.isEmpty()) {
            return null;
        }

        var suffixMatcher = SUFFIXED.matcher(value);
        if (suffixMatcher.matches()) {
            Exchange exchange = Exchange.fromDisplay(suffixMatcher.group(2));
            return exchange == null ? null : new ParsedCode(suffixMatcher.group(1), exchange, true);
        }

        var prefixMatcher = PREFIXED.matcher(value);
        if (prefixMatcher.matches()) {
            Exchange exchange = Exchange.fromDisplay(prefixMatcher.group(1));
            return exchange == null ? null : new ParsedCode(prefixMatcher.group(2), exchange, true);
        }

        if (SIX_DIGITS.matcher(value).matches()) {
            Exchange inferred = inferExchange(value);
            return inferred == null ? null : new ParsedCode(value, inferred, false);
        }
        return null;
    }

    /**
     * 按代码前缀推断交易所（仅在未显式给出交易所时使用）。
     *
     * <p>规则来自各交易所的代码段分配：
     * <ul>
     *   <li>沪市：60x（主板）、68x（科创板）、9xx（B 股）</li>
     *   <li>深市：00x（主板）、30x（创业板）、20x（B 股）</li>
     *   <li>北交所：43x / 83x / 87x / 88x / 920x</li>
     * </ul>
     *
     * <p>{@code 000xxx} 与 {@code 399xxx} 属于上证/深证指数代码段，
     * 与深市主板个股（000xxx）和创业板指（399xxx）重合。
     * 这里按「个股优先」推断为 SZ，调用方需要指数时必须显式带后缀。
     */
    public static Exchange inferExchange(String code) {
        if (!SIX_DIGITS.matcher(code).matches()) {
            return null;
        }
        char first = code.charAt(0);
        return switch (first) {
            case '6', '9' -> Exchange.SH;
            case '0', '2', '3' -> Exchange.SZ;
            case '4', '8' -> Exchange.BJ;
            default -> null;
        };
    }

    public static boolean isSixDigitCode(String raw) {
        return raw != null && SIX_DIGITS.matcher(raw.trim()).matches();
    }

    /** 归一化为大写展示形式，如 {@code 600519.SH}。 */
    public static String display(String code, Exchange exchange) {
        return code + "." + exchange.display();
    }

    /** 去掉大小写差异的辅助方法。 */
    static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
