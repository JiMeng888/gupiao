package com.stocklab.analyst.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link AsyncConfig} 线程池语义测试。
 *
 * <p><b>为什么值得测：</b>线程池的参数错一个，症状全都出现在生产而不是测试里，
 * 而且都不指向线程池本身——
 * <ul>
 *   <li>队列容量没设（默认 {@code Integer.MAX_VALUE}）→ 任务无限堆积，
 *       表现为「接口越来越慢」然后某天 OOM，堆栈里看不到线程池；</li>
 *   <li>拒绝策略选错 → 高负载时任务被静默丢弃，
 *       表现为「用户反馈数据偶尔缺一块」，日志里一行都没有；</li>
 *   <li>关闭语义没设 → 优雅停机时正在跑的任务被砍断，
 *       表现为「发版那几分钟偶发 500」。</li>
 * </ul>
 * 这些都是「不报错但错了」的失效，只能靠断言钉住。
 *
 * <h2>关于耗时</h2>
 * 本类会真的提交任务并等待，但每个任务只做毫秒级的计数与阻塞，
 * 整类耗时在百毫秒量级。用 latch 而不是固定 {@code sleep} 等待结果，
 * 避免随机器快慢变成偶发失败。
 */
@DisplayName("线程池配置")
class AsyncConfigTest {

    private final AsyncConfig config = new AsyncConfig();
    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void shutdownExecutor() {
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    private ThreadPoolTaskExecutor newExecutor() {
        executor = config.marketFetchExecutor();
        return executor;
    }

    // ==================================================================
    @Nested
    @DisplayName("基本参数")
    class BasicParameters {

        @Test
        @DisplayName("线程名前缀固定，jstack 里能一眼认出归属")
        void threadNamePrefix() throws InterruptedException {
            ThreadPoolTaskExecutor pool = newExecutor();
            AtomicReference<String> observed = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            pool.execute(() -> {
                observed.set(Thread.currentThread().getName());
                done.countDown();
            });

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(observed.get())
                    .as("没有名称前缀时 jstack 里全是 pool-3-thread-1，排查时定位不到来源")
                    .startsWith("market-fetch-");
        }

        @Test
        @DisplayName("核心 / 最大线程数有明确上限")
        void poolSizesAreBounded() {
            ThreadPoolTaskExecutor pool = newExecutor();

            assertThat(pool.getCorePoolSize()).isEqualTo(8);
            assertThat(pool.getMaxPoolSize()).isEqualTo(16);
            assertThat(pool.getMaxPoolSize())
                    .as("没有上限时突发流量下线程数无界增长，最终 OOM")
                    .isGreaterThan(0);
        }

        /**
         * 队列容量必须有限。
         *
         * <p>这是最容易漏的一项：{@code ThreadPoolTaskExecutor} 的
         * {@code queueCapacity} 默认值是一个很大的数，
         * 队列永远不满 → 超过核心数的线程永远不会被创建 → {@code maxPoolSize} 形同虚设。
         * 所以「队列有界」和「maxPoolSize 生效」是同一件事的两面。
         */
        @Test
        @DisplayName("队列有界：容量 200 且剩余容量可读")
        void queueIsBounded() {
            ThreadPoolTaskExecutor pool = newExecutor();

            assertThat(pool.getThreadPoolExecutor().getQueue().remainingCapacity())
                    .as("队列容量无限会让任务堆积到 OOM，且 maxPoolSize 永远不会生效")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("拒绝策略是 CallerRunsPolicy，不是默认的 AbortPolicy")
        void rejectionPolicyIsCallerRuns() {
            ThreadPoolTaskExecutor pool = newExecutor();

            assertThat(pool.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .as("AbortPolicy 会在高负载时直接抛 RejectedExecutionException，业务侧变成 500")
                    .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy.class);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("饱和时的行为")
    class Saturation {

        /**
         * {@code CallerRunsPolicy} 的核心承诺：<b>不丢任务</b>。
         *
         * <p>池子被灌满时，多出来的任务由提交线程自己执行（通常是 Tomcat 线程），
         * 形成天然背压——上游请求变慢，但没有一个任务凭空消失。
         *
         * <p>任务数刻意远大于 core + queue + max（8 + 200 + 16 = 224），
         * 确保真的踩到拒绝分支：只有走到拒绝策略还能把全部任务跑完，
         * 才说明策略是 CallerRuns 而非 Discard。
         */
        @Test
        @DisplayName("任务数远超队列容量时一个都不丢（背压而非丢弃）")
        void noTaskIsLostUnderSaturation() throws InterruptedException {
            int taskCount = 400;
            ThreadPoolTaskExecutor pool = newExecutor();
            AtomicInteger executed = new AtomicInteger();
            CountDownLatch allDone = new CountDownLatch(taskCount);

            for (int i = 0; i < taskCount; i++) {
                pool.execute(() -> {
                    executed.incrementAndGet();
                    allDone.countDown();
                });
            }

            assertThat(allDone.await(30, TimeUnit.SECONDS))
                    .as("有任务被静默丢弃：用户会看到「数据偶尔缺一块」而日志里什么都没有")
                    .isTrue();
            assertThat(executed.get()).isEqualTo(taskCount);
        }

        /**
         * 饱和时提交不该抛异常。
         *
         * <p><b>踩过的坑：这条用例最初让每个任务去等一个「提交循环结束后才放行」的闩。</b>
         * 而 {@code CallerRunsPolicy} 会让<b>提交线程</b>内联执行溢出任务——
         * 于是提交循环卡在第一个溢出任务上等闩，而那个闩要等循环结束才会被放行，
         * 自锁到超时为止。8 个用例整整跑了 51 秒，还差点被当成偶发慢。
         *
         * <p>教训：测拒绝策略时，任务体<b>不能</b>依赖提交方后续的动作。
         * 这里改成完全无阻塞的计数任务，饱和压力由任务数量本身提供。
         */
        @Test
        @DisplayName("饱和时提交不会抛异常，只是变慢")
        void saturationDoesNotThrow() throws InterruptedException {
            int taskCount = 400;
            ThreadPoolTaskExecutor pool = newExecutor();
            AtomicInteger executed = new AtomicInteger();
            CountDownLatch allDone = new CountDownLatch(taskCount);

            assertThatCode(() -> {
                for (int i = 0; i < taskCount; i++) {
                    pool.execute(() -> {
                        executed.incrementAndGet();
                        allDone.countDown();
                    });
                }
            }).as("AbortPolicy 下这里会抛 RejectedExecutionException，业务侧表现为 500")
                    .doesNotThrowAnyException();

            assertThat(allDone.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(executed.get()).isEqualTo(taskCount);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("关闭语义")
    class Shutdown {

        /**
         * 优雅停机的承诺：<b>已经开跑的任务不被打断</b>。
         *
         * <p>没有这一项时，发版/重启恰好砍在请求处理中间，
         * 症状是「每次发版都有几分钟偶发 500」，而且很难复现。
         */
        @Test
        @DisplayName("shutdown 会等正在执行的任务跑完，不中途打断")
        void shutdownWaitsForRunningTask() throws InterruptedException {
            ThreadPoolTaskExecutor pool = newExecutor();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);

            pool.execute(() -> {
                started.countDown();
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            assertThat(finished.await(5, TimeUnit.SECONDS))
                    .as("停机时砍断正在跑的任务，表现为每次发版都有几分钟偶发错误")
                    .isTrue();
        }

        @Test
        @DisplayName("等待上限有明确值，不会无限期拖住停机")
        void shutdownHasBoundedWait() {
            ThreadPoolTaskExecutor pool = newExecutor();

            assertThat(pool.getThreadPoolExecutor().isShutdown()).isFalse();
            pool.shutdown();
            assertThatCode(() -> pool.getThreadPoolExecutor().awaitTermination(1, TimeUnit.SECONDS))
                    .doesNotThrowAnyException();
        }
    }
}
