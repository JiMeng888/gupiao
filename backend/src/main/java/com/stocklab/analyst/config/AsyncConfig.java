package com.stocklab.analyst.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池装配。
 *
 * <p>用于「资讯多频道并发拉取」这类 IO 密集任务。每个线程池都显式声明了
 * 名称前缀、上限、队列容量、拒绝策略与关闭语义——这些项缺任何一个都会
 * 在生产排查时变得困难：
 * <ul>
 *   <li>没有名称前缀：jstack 里全是 {@code pool-3-thread-1}，定位不到来源</li>
 *   <li>没有上限：突发流量下线程数无界增长，最终 OOM</li>
 *   <li>没有队列容量（默认 Integer.MAX_VALUE）：任务无限堆积，内存被吃掉</li>
 *   <li>没有关闭语义：优雅停机时正在执行的任务被丢弃</li>
 * </ul>
 *
 * <p>队列容量与最大线程数的关系：只有当队列<b>满了</b>才会创建超过核心数的线程。
 * 因此 queueCapacity 设置为有限值（这里是 200）是让 maxPoolSize 真正生效的前提。
 *
 * <p>拒绝策略选 {@code CallerRunsPolicy}：任务由提交线程（即处理该 HTTP 请求的
 * Tomcat 线程）自己执行，形成天然的背压——不会丢任务，同时让上游请求变慢，
 * 而不是静默失败。若这是纯后台任务、允许丢弃，应改用 {@code DiscardPolicy}
 * 并配套监控。
 */
@Configuration
public class AsyncConfig {

    /** 行情 / 资讯拉取线程池：IO 密集型，线程数可适当放大。 */
    @Bean("marketFetchExecutor")
    public ThreadPoolTaskExecutor marketFetchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("market-fetch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * AI 分析任务线程池：与行情拉取线程池<b>隔离</b>。
     *
     * <p>分析任务单次耗时 10~40 秒（大模型生成），远长于行情拉取的几十毫秒。
     * 混在同一个池子里时，少数几个分析任务就会长时间占住行情线程，
     * 把「行情页卡顿」和「分析变慢」两个互不相干的问题耦合在一起。
     *
     * <p>Bean 名刻意用 {@code analysisTaskThreadPool} 而不是 {@code analysisTaskExecutor}：
     * 后者会与 {@code AnalysisTaskExecutor} 组件（类名首字母小写即同名）冲突，
     * 触发 {@code BeanDefinitionOverrideException}。
     *
     * <p>拒绝策略刻意选 {@code CallerRunsPolicy} 之外的默认
     * {@code AbortPolicy}：异步化之后，请求线程已经释放了，
     * 没有「由调用者同步兜底」这个选项；任务满了应该明确拒绝（记 FAILED），
     * 而不是让提交它的那一次 HTTP 请求替它等 40 秒——那等于异步化白做。
     * 拒绝由 {@code AnalysisTaskExecutor} 捕获并写回任务 FAILED。
     */
    @Bean("analysisTaskThreadPool")
    public ThreadPoolTaskExecutor analysisTaskThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(120);
        executor.setThreadNamePrefix("analysis-task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
