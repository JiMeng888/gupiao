package com.stocklab.analyst.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改密码请求。
 *
 * @param oldPassword 原密码
 * @param newPassword 新密码
 */
public record ChangePasswordRequest(
        @NotBlank(message = "原密码不能为空")
        String oldPassword,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 64, message = "新密码长度需在 6~64 位之间")
        String newPassword
) {
}
