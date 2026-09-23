package com.stocklab.analyst.modules.holding.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 卖出请求。
 *
 * @param code   股票代码
 * @param shares 卖出股数，必须 &gt; 0 且不超过持有数量
 * @param price  卖出单价，用于计算本次卖出的收益
 */
public record HoldingSellRequest(
        @NotBlank(message = "股票代码不能为空")
        String code,

        @NotNull(message = "卖出股数不能为空")
        @Min(value = 1, message = "卖出股数必须大于 0")
        Integer shares,

        @NotNull(message = "卖出单价不能为空")
        @DecimalMin(value = "0.01", message = "卖出单价必须大于 0")
        Double price
) {
}
