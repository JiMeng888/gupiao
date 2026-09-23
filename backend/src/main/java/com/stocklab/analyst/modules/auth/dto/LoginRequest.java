package com.stocklab.analyst.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 *
 * <p>这里<b>不</b>对手机号做格式校验：登录接口应统一返回
 * 「手机号或密码错误」，如果对格式不合法的手机号返回「格式不正确」，
 * 攻击者就能用响应差异枚举出哪些号段已注册（用户枚举漏洞）。
 * 格式校验只放在注册接口。
 *
 * @param phone    手机号
 * @param password 密码
 */
public record LoginRequest(
        @NotBlank(message = "手机号不能为空")
        String phone,

        @NotBlank(message = "密码不能为空")
        String password
) {
}
