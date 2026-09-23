package com.stocklab.analyst;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * A 股行情分析平台启动类。
 *
 * <p>装配说明：
 * <ul>
 *   <li>{@code @ConfigurationPropertiesScan} —— 扫描 type-safe 配置类，避免散落的 {@code @Value}</li>
 *   <li>{@code @EnableCaching} —— 开启 Caffeine 本地缓存（行情快照 / 新闻列表）</li>
 *   <li>{@code @EnableAsync} —— 开启异步执行（新闻多频道并发拉取）</li>
 *   <li>{@code @EnableScheduling} —— 开启定时任务（行情推送调度）</li>
 * </ul>
 *
 * <p>Mapper 扫描依赖 MyBatis-Plus starter 的自动配置 + 各接口上的 {@code @Mapper} 注解，
 * 不使用 {@code @MapperScan} 通配包路径，避免包重构后扫描失效。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
@EnableAsync
@EnableScheduling
public class StockAnalystApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockAnalystApplication.class, args);
    }
}
