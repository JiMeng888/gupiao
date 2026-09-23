package com.stocklab.analyst.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <p>校验规则写在 DTO 上而不是 Service 里：这样 Swagger 文档会自动带上约束，
 * 且 {@code @Valid} 在进入业务代码前就拦掉非法输入，
 * Service 只需处理「合法但业务上不允许」的情况（如手机号已注册）。
 *
 * @param phone    11 位手机号
 * @param password 密码，6~64 位
 * @param nickname 昵称，可选
 */
public record RegisterRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确，需为 1 开头的 11 位数字")
        String phone,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需在 6~64 位之间")
        String password,

        @Size(max = 32, message = "昵称最长 32 个字符")
        String nickname
) {
}
