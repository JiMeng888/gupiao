package com.stocklab.analyst.market;

import com.stocklab.analyst.config.properties.MarketDataProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 启动后异步预热行情数据源。
 *
 * <p>为什么用 {@link ApplicationReadyEvent} + {@code @Async}，而不是在
 * {@code ApplicationRunner} 或 Bean 初始化阶段同步预热：
 * <ol>
 *   <li><b>就绪优先</b>：预热要访问外网，最坏情况会占用满超时时间。
 *       放在启动路径上会让健康检查在这段时间内失败，K8s 可能因此重启容器，
 *       形成「启动越慢越被重启」的雪崩。</li>
 *   <li><b>失败不致命</b>：预热只影响搜索性能（走本地清单还是上游兜底），
 *       不该阻断整个应用启动。</li>
 * </ol>
 *
 * <p>代价是启动后极短时间内搜索会走上游兜底接口，属于可接受的权衡。
 */
@Component
public class MarketDataWarmupRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketDataWarmupRunner.class);

    private final MarketDataProvider marketDataProvider;
    private final MarketDataProperties properties;

    public MarketDataWarmupRunner(MarketDataProvider marketDataProvider, MarketDataProperties properties) {
        this.marketDataProvider = marketDataProvider;
        this.properties = properties;
    }

    @Async("marketFetchExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        if (!properties.warmupOnBoot()) {
            log.info("已关闭启动预热（app.market-data.warmup-on-boot=false）");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            marketDataProvider.warmup();
            log.info("行情预热结束，耗时 {} ms，当前数据源 {}",
                    System.currentTimeMillis() - start, marketDataProvider.name());
        } catch (RuntimeException ex) {
            // 预热失败只降级为告警：应用本身仍可用
            log.warn("行情预热失败（不影响服务，搜索将回退上游接口）：{}", ex.getMessage());
        }
    }
}
