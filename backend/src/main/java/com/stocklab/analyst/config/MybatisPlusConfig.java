package com.stocklab.analyst.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.Instant;

/**
 * MyBatis-Plus 装配。
 *
 * <p>拦截器顺序有讲究：分页必须在其它改写 SQL 的拦截器之前加入，
 * 否则分页 SQL 可能被后续拦截器二次改写导致 count 语句错误。
 * 这里分页在前、防全表更新在后。
 */
@Configuration
@EnableTransactionManagement
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 分页插件：不指定 DbType，由 MyBatis-Plus 从连接元数据自动识别，
        // 这样同一套代码在 H2（开发）与 MySQL（生产）下都能正确生成方言。
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());

        // 防全表更新/删除：拦截没有 WHERE 条件的 UPDATE / DELETE，
        // 避免一次误操作清空整张表（生产事故里出现频率很高的一类）
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }

    /**
     * 自动填充时间戳。
     *
     * <p>统一在 ORM 层填 {@code createdAt} / {@code updatedAt}，
     * 而不是在每个 Service 里手写——后者只要漏一处就会出现
     * 「同为创建时间，有的表有值有的为 null」的数据不一致。
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                long now = Instant.now().getEpochSecond();
                strictInsertFill(metaObject, "createdAt", Long.class, now);
                strictInsertFill(metaObject, "updatedAt", Long.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updatedAt", Long.class, Instant.now().getEpochSecond());
            }
        };
    }
}
