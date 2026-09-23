package com.stocklab.analyst.modules.holding.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 买入 / 补仓请求。
 *
 * @param code   股票代码
 * @param name   股票名称，留空则由服务端从行情源补全
 * @param shares 买入股数，必须 &gt; 0
 * @param price  买入单价，必须 &gt; 0
 * @param buyDate 买入日期（YYYY-MM-DD），可空，缺省为当天（支持补录历史买点）
 */
public record HoldingBuyRequest(
        @NotBlank(message = "股票代码不能为空")
        @Pattern(regexp = "^\\d{6}([.](SH|SZ|BJ|sh|sz|bj))?$", message = "股票代码格式不正确")
        String code,

        String name,

        @NotNull(message = "买入股数不能为空")
        @Min(value = 1, message = "买入股数必须大于 0")
        Integer shares,

        @NotNull(message = "买入单价不能为空")
        @DecimalMin(value = "0.01", message = "买入单价必须大于 0")
        Double price,

        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "买入日期格式应为 YYYY-MM-DD")
        String buyDate
) {
}
