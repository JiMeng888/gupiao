package com.stocklab.analyst.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新令牌请求。
 *
 * @param refreshToken 登录 / 注册时下发的 refreshToken
 */
public record RefreshRequest(
        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken
) {
}
