package com.stocklab.analyst.ws;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.market.MarketDataProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 行情推送器：定时取价并分发给订阅者。
 *
 * <h2>相对原 FastAPI 版本的修正</h2>
 * <ol>
 *   <li><b>串行取价 → 分块并行。</b>原实现逐个 {@code await} 取行情，
 *       100 只标的、每只 200ms 就是 20 秒，远超过 3 秒的推送周期，
 *       推送会越积越慢。这里按 {@value #FETCH_CHUNK_SIZE} 一批并行取，
 *       分块是为了给并发设上限：一次把 500 只标的全丢给线程池会让
 *       队列堆积、挤占其它业务的取数请求。</li>
 *   <li><b>幂等取价。</b>同一只标的有 N 个订阅者时只向上游取一次，
 *       再分发给 N 个连接——原实现也是这个思路，这里保持并显式化。</li>
 *   <li><b>慢客户端隔离。</b>发送失败或缓冲超限时立即关闭该连接并注销，
 *       不让一个卡住的客户端拖住整个推送周期。</li>
 * </ol>
 *
 * <h2>用 fixedDelay 而不是 fixedRate</h2>
 * 单线程调度器下 {@code fixedRate} 在一次执行超时后会「补偿式」连续触发，
 * 形成紧贴的突发推送；{@code fixedDelay} 保证上一轮结束后再等一个周期，
 * 天然限流。<b>代价是推送间隔会随负载拉长</b>——这是刻意的取舍：
 * 行情晚几百毫秒送达可以接受，推送雪崩不可以。
 *
 * <h2>已知边界</h2>
 * 分发是在调度线程上逐个 session 顺序调用 {@code sendMessage} 的。
 * 真正卡住的连接需要等到
 * {@link QuoteWebSocketHandler} 设置的发送超时被触发才会被关闭，
 * 这期间后续连接会一并延后。要做到完全隔离需要每个连接一个发送线程
 * （或用响应式 WebSocket），对当前规模不值得。此处如实标注。
 */
@Component
public class QuotePusher {

    private static final Logger log = LoggerFactory.getLogger(QuotePusher.class);

    /** 每批并行取价的标的数。取值略大于取数线程池核心线程数（8）。 */
    private static final int FETCH_CHUNK_SIZE = 24;

    private final MarketDataProvider marketDataProvider;
    private final QuoteSessionRegistry registry;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Executor fetchExecutor;

    public QuotePusher(MarketDataProvider marketDataProvider,
                       QuoteSessionRegistry registry,
                       ObjectMapper objectMapper,
                       MeterRegistry meterRegistry,
                       @Qualifier("marketFetchExecutor") Executor fetchExecutor) {
        this.marketDataProvider = marketDataProvider;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.fetchExecutor = fetchExecutor;
    }

    // ==================================================================
    // 定时推送
    // ==================================================================

    @Scheduled(fixedDelayString = "${app.ws.push-interval:3s}")
    public void pushQuotes() {
        Map<String, List<String>> subscribers = registry.snapshotByCode();
        if (subscribers.isEmpty()) {
            return;
        }

        try {
            FetchResult fetched = fetchQuotes(subscribers.keySet());
            int delivered = 0;

            for (Map.Entry<String, List<String>> entry : subscribers.entrySet()) {
                String code = entry.getKey();
                WsProtocol.QuotePayload payload = fetched.quotes().get(code);
                Object frame = payload != null
                        ? payload
                        : WsProtocol.Error.of(code,
                        fetched.failures().getOrDefault(code, "行情数据源暂不可用"));

                delivered += sendToSessions(entry.getValue(), frame);
            }

            meterRegistry.counter("app.ws.push.codes").increment(subscribers.size());
            log.debug("行情推送完成：标的 {} 只，送达 {} 次", subscribers.size(), delivered);
        } catch (RuntimeException ex) {
            // 定时任务内未捕获的异常会被 Spring 反复记录；这里收敛成一条日志，
            // 并保证下一轮仍然执行
            log.warn("行情推送周期异常：{}", ex.toString());
        }
    }

    // ==================================================================
    // 取价
    // ==================================================================

    /** 取价结果：成功的行情 + 失败的代码与原因。 */
    public record FetchResult(Map<String, WsProtocol.QuotePayload> quotes,
                              Map<String, String> failures) {
    }

    /**
     * 并行取多只标的的行情。
     *
     * <p>单只失败不影响其它标的——推送循环会为失败的代码下发一帧
     * {@code type=error}，前端可据此把该行的价格标记为「刷新失败」，
     * 而不是继续显示一个已经不动的旧价格。
     */
    public FetchResult fetchQuotes(Collection<String> codes) {
        List<String> list = List.copyOf(codes);
        Map<String, WsProtocol.QuotePayload> quotes = new ConcurrentHashMap<>(list.size());
        Map<String, String> failures = new ConcurrentHashMap<>();

        for (int start = 0; start < list.size(); start += FETCH_CHUNK_SIZE) {
            List<String> chunk = list.subList(start, Math.min(start + FETCH_CHUNK_SIZE, list.size()));
            List<CompletableFuture<Void>> tasks = new ArrayList<>(chunk.size());
            for (String code : chunk) {
                tasks.add(CompletableFuture.runAsync(() -> {
                    try {
                        quotes.put(code, WsProtocol.QuotePayload.from(marketDataProvider.getQuote(code)));
                    } catch (BizException ex) {
                        // 业务异常（标的不存在等）带的是可读消息，直接给前端
                        failures.put(code, ex.getMessage());
                    } catch (RuntimeException ex) {
                        failures.put(code, "行情数据源暂不可用");
                    }
                }, fetchExecutor));
            }
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        }

        if (!failures.isEmpty()) {
            meterRegistry.counter("app.ws.push.fetch.failure").increment(failures.size());
        }
        return new FetchResult(quotes, failures);
    }

    // ==================================================================
    // 下发
    // ==================================================================

    /**
     * 把同一帧发给一批连接。
     *
     * <p>序列化只做一次：同一只标的对所有订阅者的报文完全相同，
     * 逐个序列化在 100 个订阅者时就是 100 次 JSON 编码。
     *
     * @return 成功送达的连接数
     */
    public int sendToSessions(Collection<String> sessionIds, Object frame) {
        TextMessage message;
        try {
            message = new TextMessage(objectMapper.writeValueAsString(frame));
        } catch (RuntimeException ex) {
            log.warn("WebSocket 报文序列化失败，本轮跳过：{}", ex.toString());
            return 0;
        }

        int delivered = 0;
        for (String sessionId : sessionIds) {
            if (send(sessionId, message)) {
                delivered++;
            }
        }
        return delivered;
    }

    /** 给单个连接发一帧。 */
    public boolean sendFrame(String sessionId, Object frame) {
        WebSocketSession session = registry.session(sessionId);
        if (session == null) {
            return false;
        }
        TextMessage message;
        try {
            message = new TextMessage(objectMapper.writeValueAsString(frame));
        } catch (RuntimeException ex) {
            log.warn("WebSocket 报文序列化失败 sessionId={}：{}", sessionId, ex.toString());
            return false;
        }
        return sendDirect(sessionId, session, message);
    }

    private boolean send(String sessionId, TextMessage message) {
        WebSocketSession session = registry.session(sessionId);
        if (session == null) {
            return false;
        }
        return sendDirect(sessionId, session, message);
    }

    /**
     * 实际发送。
     *
     * <p>两种失败都要处理：
     * <ul>
     *   <li>{@link IOException} —— 连接已断开，浏览器关页面后服务端往往要等到
     *       下一次写才发现</li>
     *   <li>{@code SessionLimitExceededException}（RuntimeException 子类）——
     *       由 {@code ConcurrentWebSocketSessionDecorator} 在发送超时或缓冲
     *       超限时抛出，说明这是一个收不动的慢客户端</li>
     * </ul>
     * 两者都立即关闭连接并注销订阅。不注销的后果是订阅表里长期残留在
     * 一批已经无法送达的 sessionId，推送循环每轮都在做无用功。
     */
    private boolean sendDirect(String sessionId, WebSocketSession session, TextMessage message) {
        try {
            session.sendMessage(message);
            return true;
        } catch (IOException | RuntimeException ex) {
            meterRegistry.counter("app.ws.send.failure").increment();
            log.debug("WebSocket 发送失败，关闭连接 sessionId={}：{}", sessionId, ex.toString());
            closeQuietly(session);
            registry.removeSession(sessionId);
            return false;
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (IOException | RuntimeException ignored) {
            // 关闭失败没有补救手段，忽略
        }
    }
}
