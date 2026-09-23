package com.stocklab.analyst.modules.auth.dto;

/**
 * 当前用户信息。
 *
 * <p>刻意<b>不</b>包含 passwordHash 与 createdAt：
 * 返回体只放前端需要的字段，是最省事的防泄漏手段——
 * 比在序列化时加 {@code @JsonIgnore} 更可靠，因为不容易漏。
 *
 * @param id       用户 ID
 * @param phone    手机号
 * @param nickname 昵称
 */
public record UserProfile(String id, String phone, String nickname) {
}
