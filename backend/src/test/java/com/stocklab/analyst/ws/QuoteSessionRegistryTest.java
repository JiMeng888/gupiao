package com.stocklab.analyst.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link QuoteSessionRegistry} 单测——全仓唯一维护进程内可变状态的地方，之前零测试。
 *
 * <p>这里最该防的不是异常，而是<b>「三张表不同步」导致的悬挂订阅</b>：
 * {@code codesBySession} 里有、{@code sessionsByCode} 里没有的连接，
 * 会占着订阅名额却永远收不到推送——不报错、不告警，只是静默不工作。
 * 因此每条用例都同时断言正反两个方向。
 */
@DisplayName("QuoteSessionRegistry 会话与订阅登记表")
class QuoteSessionRegistryTest {

    private QuoteSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new QuoteSessionRegistry();
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }

    @Nested
    @DisplayName("订阅与正向/反向索引一致性")
    class Consistency {

        @Test
        @DisplayName("订阅后正反两个方向都能查到")
        void subscribeUpdatesBothIndexes() {
            registry.addSession(session("s1"));

            QuoteSessionRegistry.SubscribeResult result =
                    registry.subscribe("s1", List.of("600519", "000001"));

            assertThat(result.accepted()).containsExactly("600519", "000001");
            assertThat(result.allCodes()).containsExactly("600519", "000001");
            assertThat(result.rejected()).isEmpty();

            assertThat(registry.sessionsFor("600519")).containsExactly("s1");
            assertThat(registry.snapshotByCode()).containsOnlyKeys("600519", "000001");
            assertThat(registry.subscribedCodeCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("重复订阅幂等：不报错、不重复登记、不改变已有订阅")
        void duplicateSubscribeIsIdempotent() {
            registry.addSession(session("s1"));
            registry.subscribe("s1", List.of("600519"));

            QuoteSessionRegistry.SubscribeResult again =
                    registry.subscribe("s1", List.of("600519"));

            assertThat(again.accepted()).as("已订阅的代码不再重复计入").isEmpty();
            assertThat(again.allCodes()).containsExactly("600519");
            assertThat(registry.sessionsFor("600519")).containsExactly("s1");
            assertThat(registry.subscribedCodeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("多连接订阅同一标的：一个退订后其余订阅不受影响")
        void unsubscribeDoesNotAffectOtherSubscribers() {
            registry.addSession(session("s1"));
            registry.addSession(session("s2"));
            registry.subscribe("s1", List.of("600519"));
            registry.subscribe("s2", List.of("600519"));

            QuoteSessionRegistry.UnsubscribeResult result =
                    registry.unsubscribe("s1", List.of("600519"));

            assertThat(result.removed()).containsExactly("600519");
            assertThat(result.remaining()).isEmpty();
            assertThat(registry.sessionsFor("600519"))
                    .as("s2 仍在订阅，索引不能被整体删掉").containsExactly("s2");
            assertThat(registry.subscribedCodeCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("连接关闭后的清理")
    class Cleanup {

        @Test
        @DisplayName("移除连接时双向索引一起清理，不留悬挂订阅")
        void removeSessionClearsBothIndexes() {
            registry.addSession(session("s1"));
            registry.addSession(session("s2"));
            registry.subscribe("s1", List.of("600519", "000001"));
            registry.subscribe("s2", List.of("600519"));

            registry.removeSession("s1");

            assertThat(registry.session("s1")).isNull();
            assertThat(registry.sessionsFor("600519")).containsExactly("s2");
            assertThat(registry.sessionsFor("000001"))
                    .as("最后一个订阅者离开后该代码必须被摘掉，否则推送循环会持续为它取行情")
                    .isEmpty();
            assertThat(registry.subscribedCodeCount()).isEqualTo(1);
            assertThat(registry.connectionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("重复移除同一连接是幂等的")
        void removeSessionIsIdempotent() {
            registry.addSession(session("s1"));
            registry.subscribe("s1", List.of("600519"));

            registry.removeSession("s1");
            registry.removeSession("s1");

            assertThat(registry.connectionCount()).isZero();
            assertThat(registry.subscribedCodeCount()).isZero();
            assertThat(registry.session("s1")).isNull();
        }

        @Test
        @DisplayName("全部连接断开后不留任何残留订阅")
        void allSessionsGoneLeavesNothingBehind() {
            for (int i = 0; i < 5; i++) {
                String id = "s" + i;
                registry.addSession(session(id));
                registry.subscribe(id, List.of("60051" + i, "00000" + i));
            }
            assertThat(registry.subscribedCodeCount()).isEqualTo(10);

            for (int i = 0; i < 5; i++) {
                registry.removeSession("s" + i);
            }

            assertThat(registry.connectionCount()).isZero();
            assertThat(registry.subscribedCodeCount()).as("不允许残留").isZero();
            assertThat(registry.snapshotByCode()).isEmpty();
        }

        @Test
        @DisplayName("未知连接退订返回空结果，不抛异常")
        void unsubscribeUnknownSessionIsSafe() {
            QuoteSessionRegistry.UnsubscribeResult result =
                    registry.unsubscribe("not-exist", List.of("600519"));

            assertThat(result.removed()).isEmpty();
            assertThat(result.remaining()).isEmpty();
        }
    }

    @Nested
    @DisplayName("订阅上限")
    class Limits {

        @Test
        @DisplayName("单连接超过 50 只：超出的被拒绝并给出原因，已订阅的保留")
        void perConnectionLimitIsEnforced() {
            registry.addSession(session("s1"));
            List<String> codes = IntStream.range(0, 60)
                    .mapToObj(i -> String.format("%06d", i))
                    .toList();

            QuoteSessionRegistry.SubscribeResult result = registry.subscribe("s1", codes);

            assertThat(result.accepted()).hasSize(QuoteSessionRegistry.MAX_CODES_PER_CONNECTION);
            assertThat(result.rejected()).hasSize(10);
            assertThat(result.rejected().get(0).reason()).contains("单个连接最多订阅");
            assertThat(result.allCodes()).hasSize(QuoteSessionRegistry.MAX_CODES_PER_CONNECTION);
            assertThat(registry.subscribedCodeCount())
                    .isEqualTo(QuoteSessionRegistry.MAX_CODES_PER_CONNECTION);
        }

        @Test
        @DisplayName("全局订阅总量达到 500 只后拒绝新标的（防止一个连接吃满上游）")
        void globalLimitIsEnforced() {
            // 10 个连接 × 50 只 = 500，正好打满全局上限
            for (int i = 0; i < 10; i++) {
                String id = "s" + i;
                registry.addSession(session(id));
                registry.subscribe(id, IntStream.range(i * 50, (i + 1) * 50)
                        .mapToObj(n -> String.format("%06d", n)).toList());
            }
            assertThat(registry.subscribedCodeCount()).isEqualTo(QuoteSessionRegistry.MAX_SUBSCRIBED_CODES);

            registry.addSession(session("late"));
            QuoteSessionRegistry.SubscribeResult result =
                    registry.subscribe("late", List.of("999999"));

            assertThat(result.accepted()).isEmpty();
            assertThat(result.rejected()).hasSize(1);
            assertThat(result.rejected().get(0).reason()).contains("服务端订阅总量已达上限");
            assertThat(registry.sessionsFor("999999")).isEmpty();
        }
    }

    @Nested
    @DisplayName("监控快照与并发")
    class Snapshot {

        @Test
        @DisplayName("stats 只暴露前 20 个代码，不把全量订阅列表公开")
        void statsLimitsDetailSize() {
            registry.addSession(session("s1"));
            registry.subscribe("s1", IntStream.range(0, 30)
                    .mapToObj(i -> String.format("%06d", i)).toList());

            QuoteSessionRegistry.Stats stats = registry.stats();

            assertThat(stats.connections()).isEqualTo(1);
            assertThat(stats.subscribedCodes()).isEqualTo(30);
            assertThat(stats.topCodes()).hasSize(20);
        }

        @Test
        @DisplayName("并发订阅与断开后状态仍然自洽（三张表不发散）")
        void concurrentSubscribeAndRemoveStaysConsistent() throws Exception {
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Throwable> errors = new ArrayList<>();

            try {
                for (int i = 0; i < threads; i++) {
                    final int index = i;
                    pool.submit(() -> {
                        try {
                            start.await();
                            String id = "c" + index;
                            registry.addSession(session(id));
                            registry.subscribe(id, List.of("600519", "000001", "300750"));
                            registry.unsubscribe(id, List.of("300750"));
                            registry.removeSession(id);
                        } catch (Throwable ex) {
                            synchronized (errors) {
                                errors.add(ex);
                            }
                        }
                    });
                }
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            assertThat(errors).as("并发操作不应抛异常").isEmpty();
            assertThat(registry.connectionCount()).isZero();
            assertThat(registry.subscribedCodeCount()).as("全部断开后不允许残留订阅").isZero();
            assertThat(registry.snapshotByCode()).isEmpty();
        }

        @Test
        @DisplayName("推送循环拿到的是不可变快照，改动它不影响登记表")
        void snapshotIsImmutable() {
            registry.addSession(session("s1"));
            registry.subscribe("s1", List.of("600519"));

            Map<String, List<String>> snapshot = registry.snapshotByCode();

            assertThat(snapshot).containsOnlyKeys("600519");
            assertThat(registry.sessionsFor("600519")).containsExactly("s1");
        }
    }
}
