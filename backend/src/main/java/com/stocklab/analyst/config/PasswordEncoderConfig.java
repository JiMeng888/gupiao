package com.stocklab.analyst.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器装配。
 *
 * <p>用 {@link BCryptPasswordEncoder} 而不是 MD5 / SHA-256 加盐：
 * <ul>
 *   <li>bcrypt 内置盐值（输出前 29 字节含盐与代价因子），不需要自己管理盐</li>
 *   <li>bcrypt 的代价因子可调，计算慢，能显著抬高离线爆破成本；
 *       SHA-256 的盐只能防彩虹表，无法防「单账号定向爆破」</li>
 *   <li>{@code matches} 内部是常量时间比较，避免时序侧信道</li>
 * </ul>
 *
 * <p>强度使用默认值 10（2^10 次迭代，单次约 50~100ms）。
 * 不要为了「更安全」无脑调到 14+——登录接口会因此把 CPU 打满，
 * 且密码哈希的耗时直接体现在注册 / 登录响应时间上。
 * 正确做法是随着硬件升级逐步提高，并需要重新哈希旧密码的迁移方案。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
