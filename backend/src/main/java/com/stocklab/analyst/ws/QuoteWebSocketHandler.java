package com.stocklab.analyst.ws;

import com.stocklab.analyst.market.StockCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /ws/quote} 的协议处理器。
 *
 * <h2>这一层只做协议，不做业务</h2>
 * 解析上行帧、校验参数、调用订阅表、把结果交给 {@link QuotePusher} 下发。
 * 取价与定时推送都在 Pusher 里，因此这一层没有 IO 阻塞点。
 *
 * <h2>相对原 FastAPI 版本的修正</h2>
 * <ol>
 *   <li><b>慢客户端识别。</b>用 {@link ConcurrentWebSocketSessionDecorator} 包装会话，
 *       限制发送耗时与发送缓冲。浏览器页面卡住或网络极差时，服务端不会
 *       无上限地往该连接缓冲数据，而是抛异常并被关闭。</li>
 *   <li><b>订阅前校验代码格式。</b>原实现把任何字符串都塞进订阅表，
 *       一个错的代码会变成「每 3 秒一次的失败上游请求」，永久刷下去。
 *       这里先用 {@link StockCodes#parse} 拦掉非法代码并回传原因。</li>
 *   <li><b>不回显未知 action。</b>原实现把客户端传来的 action 原样拼进错误消息
 *       （{@code f"unknown action: {action}"}），前端若用 {@code innerHTML}
 *       渲染这条消息就是一个 XSS 面。这里只在服务端日志里记录截断后的值。</li>
 * </ol>
 *
 * <h2>不做鉴权（有意为之）</h2>
 * 推送的只有公开行情，与 REST 侧的行情接口同级，不涉及用户数据。
 * 若将来要推送「我的持仓实时盈亏」，握手必须带凭证——但浏览器 WebSocket
 * 无法自定义 {@code Authorization} 头，<b>不要</b>把长效 access token 放进
 * query 参数（会进访问日志、Referer、浏览器历史）。正确做法是先调一次 REST
 * 换取一次性短期 ticket，再用 ticket 握手。
 */
@Component
public class QuoteWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(QuoteWebSocketHandler.class);

    /** 单连接发送耗时上限；超时视为慢客户端并关闭。 */
    private static final int SEND_TIME_LIMIT_MS = 5_000;

    /** 单连接发送缓冲上限；收不动的客户端会在这里被截断并关闭。 */
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    /** 上行单帧最大长度，防止超大 JSON 占用内存。 */
    private static final int MAX_INBOUND_FRAME_CHARS = 8 * 1024;

    /** 日志中代码 / action 的最大展示长度。 */
    private static final int LOG_FIELD_LIMIT = 32;

    private final QuoteSessionRegistry registry;
    private final QuotePusher pusher;
    private final ObjectMapper objectMapper;

    public QuoteWebSocketHandler(QuoteSessionRegistry registry,
                                 QuotePusher pusher,
                                 ObjectMapper objectMapper) {
        this.registry = registry;
        this.pusher = pusher;
        this.objectMapper = objectMapper;
    }

    // ==================================================================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        registry.addSession(decorated);
        pusher.sendFrame(session.getId(), WsProtocol.Ready.of());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = session.getId();
        String payload = message.getPayload();

        if (payload == null || payload.isBlank()) {
            pusher.sendFrame(sessionId, WsProtocol.Error.of("报文为空"));
            return;
        }
        if (payload.length() > MAX_INBOUND_FRAME_CHARS) {
            log.warn("WebSocket 上行报文过大 sessionId={} 长度={}", sessionId, payload.length());
            pusher.sendFrame(sessionId, WsProtocol.Error.of("上行报文过大"));
            return;
        }

        WsProtocol.Inbound inbound;
        try {
            inbound = objectMapper.readValue(payload, WsProtocol.Inbound.class);
        } catch (RuntimeException ex) {
            pusher.sendFrame(sessionId, WsProtocol.Error.of("invalid JSON"));
            return;
        }

        String action = inbound.action();
        if (action == null || action.isBlank()) {
            pusher.sendFrame(sessionId, WsProtocol.Error.of("缺少 action 字段"));
            return;
        }

        switch (action) {
            case "ping" -> pusher.sendFrame(sessionId, WsProtocol.Pong.now());
            case "subscribe" -> handleSubscribe(sessionId, inbound.codes());
            case "unsubscribe" -> handleUnsubscribe(sessionId, inbound.codes());
            default -> {
                log.debug("WebSocket 收到未知 action sessionId={} action={}",
                        sessionId, sanitize(action));
                pusher.sendFrame(sessionId, WsProtocol.Error.of("unknown action"));
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.removeSession(session.getId());
        log.debug("WebSocket 连接已关闭 sessionId={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket 传输异常 sessionId={}：{}", session.getId(), exception.toString());
        registry.removeSession(session.getId());
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (Exception ignored) {
            // 传输已异常，关闭失败无需处理
        }
    }

    // ==================================================================

    /**
     * 处理订阅。
     *
     * <p>两条路径的代码都先归一化成 6 位数字再入库，避免 {@code sh600519}
     * 与 {@code 600519} 变成两条互不相干的订阅。
     */
    private void handleSubscribe(String sessionId, List<String> rawCodes) {
        if (rawCodes == null || rawCodes.isEmpty()) {
            pusher.sendFrame(sessionId, WsProtocol.Error.of("codes 不能为空"));
            return;
        }

        List<String> valid = new ArrayList<>(rawCodes.size());
        List<WsProtocol.RejectedCode> rejected = new ArrayList<>();
        for (String raw : rawCodes) {
            StockCodes.ParsedCode parsed = StockCodes.parse(raw);
            if (parsed == null) {
                rejected.add(new WsProtocol.RejectedCode(
                        raw == null ? "" : sanitize(raw), "无效的股票代码"));
            } else if (!valid.contains(parsed.code())) {
                valid.add(parsed.code());
            }
        }

        QuoteSessionRegistry.SubscribeResult result = registry.subscribe(sessionId, valid);
        rejected.addAll(result.rejected());

        List<WsProtocol.QuotePayload> snapshots = new ArrayList<>();
        if (!result.accepted().isEmpty()) {
            QuotePusher.FetchResult fetched = pusher.fetchQuotes(result.accepted());
            // 按 accepted 的顺序组装，而不是遍历 ConcurrentHashMap，
            // 保证同一次订阅的返回顺序稳定（前端按序渲染时不会跳动）
            for (String code : result.accepted()) {
                WsProtocol.QuotePayload payload = fetched.quotes().get(code);
                if (payload != null) {
                    snapshots.add(payload);
                }
            }
            pusher.sendFrame(sessionId,
                    WsProtocol.Subscribed.of(result.allCodes(), snapshots, rejected));

            // 取价失败的标的单独下发错误帧，前端可以逐行标记刷新失败
            fetched.failures().forEach((code, reason) ->
                    pusher.sendFrame(sessionId, WsProtocol.Error.of(code, reason)));
        } else {
            pusher.sendFrame(sessionId,
                    WsProtocol.Subscribed.of(result.allCodes(), List.of(), rejected));
        }
    }

    private void handleUnsubscribe(String sessionId, List<String> rawCodes) {
        if (rawCodes == null || rawCodes.isEmpty()) {
            pusher.sendFrame(sessionId, WsProtocol.Error.of("codes 不能为空"));
            return;
        }

        List<String> normalized = new ArrayList<>(rawCodes.size());
        for (String raw : rawCodes) {
            StockCodes.ParsedCode parsed = StockCodes.parse(raw);
            if (parsed != null && !normalized.contains(parsed.code())) {
                normalized.add(parsed.code());
            }
        }

        QuoteSessionRegistry.UnsubscribeResult result = registry.unsubscribe(sessionId, normalized);
        pusher.sendFrame(sessionId,
                WsProtocol.Unsubscribed.of(result.removed(), result.remaining()));
    }

    /** 清理要写进日志或回传给前端的客户端输入：去控制字符、截断。 */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", "");
        return cleaned.length() <= LOG_FIELD_LIMIT
                ? cleaned
                : cleaned.substring(0, LOG_FIELD_LIMIT) + "...";
    }
}
