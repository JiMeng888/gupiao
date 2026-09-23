package com.stocklab.analyst.modules.auth.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用户表。
 *
 * <p>密码只存 bcrypt 哈希，永不存明文、永不回传前端。
 * 手机号建唯一索引——注册的并发去重最终要由数据库约束保证，
 * 「先查再插」在并发下会漏（两个请求可能同时查到不存在）。
 */
@TableName("users")
public class User {

    /** 主键：32 位无连字符 UUID，由 MyBatis-Plus 生成。 */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String phone;

    /** bcrypt 哈希（含盐），长度 60 左右。 */
    private String passwordHash;

    private String nickname;

    @TableField(fill = FieldFill.INSERT)
    private Long createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
