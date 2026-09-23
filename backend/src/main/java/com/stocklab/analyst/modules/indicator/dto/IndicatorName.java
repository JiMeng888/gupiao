package com.stocklab.analyst.modules.indicator.dto;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 支持的技术指标名称。
 *
 * <p>用枚举而不是字符串集合来维护白名单：新增指标时编译器会提示
 * 所有需要同步修改的位置（如 {@code IndicatorService} 的 switch），
 * 而字符串写法只会静默地少算一个指标。
 */
public enum IndicatorName {

    MACD,
    RSI,
    BOLL,
    KDJ,
    MA;

    /**
     * 解析逗号分隔的指标名，去重并保持请求顺序。
     *
     * @throws BizException 含不支持的指标名时抛 INVALID_INDICATOR，
     *                      并在消息里列出具体是哪些——比笼统报「参数错误」更容易定位
     */
    public static Set<IndicatorName> parseAll(String raw) {
        Set<IndicatorName> result = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        Set<String> invalid = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String name = token.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            try {
                result.add(IndicatorName.valueOf(name));
            } catch (IllegalArgumentException ex) {
                invalid.add(name);
            }
        }
        if (!invalid.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_INDICATOR,
                    "不支持的指标: " + String.join(",", invalid));
        }
        return result;
    }
}
