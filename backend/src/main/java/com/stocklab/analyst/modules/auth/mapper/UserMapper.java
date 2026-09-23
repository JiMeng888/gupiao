package com.stocklab.analyst.modules.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stocklab.analyst.modules.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper。
 *
 * <p>继承 {@code BaseMapper} 已获得单表 CRUD，这里只补充两处需要
 * 显式 SQL 的查询。
 *
 * <p>用 {@code @Mapper} 注解而不是 {@code @MapperScan} 通配包路径：
 * 后者在包结构调整后会静默失效（启动正常、运行时才报找不到 Mapper），
 * 而注解与接口始终在同一个文件里，不会漏。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 按手机号查询。
     *
     * <p>用注解 SQL 而不是 {@code LambdaQueryWrapper}，是为了让
     * 「这条查询命中了唯一索引 {@code uk_users_phone}」这件事一眼可见。
     */
    @Select("SELECT id, phone, password_hash, nickname, created_at FROM users WHERE phone = #{phone}")
    User selectByPhone(@Param("phone") String phone);

    /**
     * 统计手机号是否已存在。
     *
     * <p>注册流程用它做前置检查（快速失败、给用户友好提示），
     * 但<b>不能作为唯一性保证</b>——并发下两个请求可能同时得到 0。
     * 真正的兜底是数据库唯一索引 + 插入时捕获
     * {@code DuplicateKeyException}，见 {@code AuthService#register}。
     */
    @Select("SELECT COUNT(1) FROM users WHERE phone = #{phone}")
    int countByPhone(@Param("phone") String phone);
}
