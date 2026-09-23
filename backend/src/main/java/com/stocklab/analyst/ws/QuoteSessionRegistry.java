package com.stocklab.analyst.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WebSocket 会话与订阅关系登记表。
 *
 * <h2>三张表的强一致性要求</h2>
 * <pre>
 * sessions       : sessionId → WebSocketSession
 * codesBySession : sessionId → 该连接订阅的代码集合
 * sessionsByCode : code      → 订阅该代码的连接集合
 * </pre>
 * 后两张表是同一份关系的正反两个方向，<b>必须同步更新</b>。
 * 一旦出现「codesBySession 里有、sessionsByCode 里没有」的悬挂记录，
 * 表现是「这个连接占着订阅名额却永远收不到推送」——不会报错，只会静默不工作。
 * 因此三张表放在同一把锁下维护，而不是各自用 ConcurrentHashMap 图省事。
 *
 * <p>锁内只做内存 Map 操作（微秒级），<b>绝不能把取行情、发消息这类 IO 放进锁内</b>，
 * 否则一个慢客户端会阻塞所有连接的订阅操作。推送循环通过
 * {@link #snapshotByCode()} 拿到不可变快照后在锁外工作。
 *
 * <h2>两处上限（原 FastAPI 版本没有）</h2>
 * 原实现允许单个连接订阅任意多标的，也没有全局上限。这意味着一个客户端
 * subscribe 全市场 5000 只后，推送循环每 3 秒要对上游发起 5000 次请求——
 * 一个不需要任何鉴权的接口就能把上游限流打满，并把本方带宽与线程池吃干净。
 * 这里加两层限制：单连接 {@value #MAX_CODES_PER_CONNECTION} 只、
 * 全局 {@value #MAX_SUBSCRIBED_CODES} 只。
 */
@Component
public class QuoteSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(QuoteSessionRegistry.class);

    /** 单个连接最多订阅的标的数。 */
    public static final int MAX_CODES_PER_CONNECTION = 50;

    /** 全局最多同时被订阅的标的数。 */
    public static final int MAX_SUBSCRIBED_CODES = 500;

    /** 状态接口最多返回多少个代码的明细。 */
    private static final int STATS_DETAIL_LIMIT = 20;

    private final Map<String, WebSocketSession> sessions = new HashMap<>();
    private final Map<String, Set<String>> codesBySession = new HashMap<>();
    private final Map<String, Set<String>> sessionsByCode = new HashMap<>();

    /** 统一守护三张表的一致性。 */
    private final Object lock = new Object();

    // ==================================================================
    // 生命周期
    // ==================================================================

    public void addSession(WebSocketSession session) {
        synchronized (lock) {
            sessions.put(session.getId(), session);
            codesBySession.putIfAbsent(session.getId(), new LinkedHashSet<>());
        }
        log.debug("WebSocket 连接建立 sessionId={} 当前连接数={}", session.getId(), connectionCount());
    }

    /** 移除连接及其全部订阅。幂等——重复调用安全。 */
    public void removeSession(String sessionId) {
        Set<String> codes;
        synchronized (lock) {
            sessions.remove(sessionId);
            codes = codesBySession.remove(sessionId);
            if (codes != null) {
                for (String code : codes) {
                    Set<String> subscribers = sessionsByCode.get(code);
                    if (subscribers != null) {
                        subscribers.remove(sessionId);
                        if (subscribers.isEmpty()) {
                            sessionsByCode.remove(code);
                        }
                    }
                }
            }
        }
        if (codes != null && !codes.isEmpty()) {
            log.debug("WebSocket 连接关闭 sessionId={} 释放订阅 {} 只", sessionId, codes.size());
        }
    }

    // ==================================================================
    // 订阅
    // ==================================================================

    /**
     * 增量订阅。
     *
     * @param codes 已由调用方校验与归一化的代码（去重、6 位数字）
     */
    public SubscribeResult subscribe(String sessionId, Collection<String> codes) {
        List<String> accepted = new ArrayList<>(codes.size());
        List<WsProtocol.RejectedCode> rejected = new ArrayList<>();
        List<String> current;

        synchronized (lock) {
            Set<String> existing = codesBySession.computeIfAbsent(sessionId, k -> new LinkedHashSet<>());
            for (String code : codes) {
                if (existing.contains(code)) {
                    // 重复订阅不报错也不重复登记，前端重连后重放订阅列表是常见行为
                    continue;
                }
                if (existing.size() >= MAX_CODES_PER_CONNECTION) {
                    rejected.add(new WsProtocol.RejectedCode(code,
                            "单个连接最多订阅 " + MAX_CODES_PER_CONNECTION + " 只标的"));
                    continue;
                }
                boolean known = sessionsByCode.containsKey(code);
                if (!known && sessionsByCode.size() >= MAX_SUBSCRIBED_CODES) {
                    rejected.add(new WsProtocol.RejectedCode(code,
                            "服务端订阅总量已达上限 " + MAX_SUBSCRIBED_CODES + " 只，请稍后重试"));
                    continue;
                }
                existing.add(code);
                sessionsByCode.computeIfAbsent(code, k -> new LinkedHashSet<>()).add(sessionId);
                accepted.add(code);
            }
            current = List.copyOf(existing);
        }
        return new SubscribeResult(accepted, current, rejected);
    }

    /** 取消订阅。返回实际被移除的代码与该连接剩余的订阅。 */
    public UnsubscribeResult unsubscribe(String sessionId, Collection<String> codes) {
        List<String> removed = new ArrayList<>();
        List<String> remaining;

        synchronized (lock) {
            Set<String> existing = codesBySession.get(sessionId);
            if (existing == null) {
                return new UnsubscribeResult(List.of(), List.of());
            }
            for (String code : codes) {
                if (!existing.remove(code)) {
                    continue;
                }
                removed.add(code);
                Set<String> subscribers = sessionsByCode.get(code);
                if (subscribers != null) {
                    subscribers.remove(sessionId);
                    if (subscribers.isEmpty()) {
                        sessionsByCode.remove(code);
                    }
                }
            }
            remaining = List.copyOf(existing);
        }
        return new UnsubscribeResult(removed, remaining);
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public WebSocketSession session(String sessionId) {
        synchronized (lock) {
            return sessions.get(sessionId);
        }
    }

    /**
     * 按代码聚合的订阅快照，供推送循环在锁外使用。
     *
     * @return code → 订阅该代码的 sessionId 列表（不可变）
     */
    public Map<String, List<String>> snapshotByCode() {
        synchronized (lock) {
            if (sessionsByCode.isEmpty()) {
                return Map.of();
            }
            Map<String, List<String>> snapshot = new LinkedHashMap<>(sessionsByCode.size());
            sessionsByCode.forEach((code, ids) -> snapshot.put(code, List.copyOf(ids)));
            return snapshot;
        }
    }

    /** 某只标的的订阅者。 */
    public List<String> sessionsFor(String code) {
        synchronized (lock) {
            Set<String> ids = sessionsByCode.get(code);
            return ids == null ? List.of() : List.copyOf(ids);
        }
    }

    public int connectionCount() {
        synchronized (lock) {
            return sessions.size();
        }
    }

    /** 当前被订阅的标的数（供监控指标直接取值，避免每次都做一遍排序）。 */
    public int subscribedCodeCount() {
        synchronized (lock) {
            return sessionsByCode.size();
        }
    }

    /**
     * 监控快照。
     *
     * <p>只返回订阅数最多的前 {@value #STATS_DETAIL_LIMIT} 个代码。
     * 原 FastAPI 版本的 {@code /ws/stats} 会把全部订阅代码列出来，
     * 一个无需鉴权的接口就能被用来窥探「当前有哪些标的正被关注」，
     * 这类聚合信息在真实产品里属于运营数据，不该完整公开。
     */
    public Stats stats() {
        synchronized (lock) {
            List<Map.Entry<String, Set<String>>> ranked = new ArrayList<>(sessionsByCode.entrySet());
            ranked.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

            Map<String, Integer> top = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : ranked) {
                if (top.size() >= STATS_DETAIL_LIMIT) {
                    break;
                }
                top.put(entry.getKey(), entry.getValue().size());
            }
            return new Stats(sessions.size(), sessionsByCode.size(), top);
        }
    }

    // ==================================================================

    /** 订阅结果。 */
    public record SubscribeResult(
            List<String> accepted,
            List<String> allCodes,
            List<WsProtocol.RejectedCode> rejected
    ) {
    }

    /** 取消订阅结果。 */
    public record UnsubscribeResult(List<String> removed, List<String> remaining) {
    }

    /** 监控快照。 */
    public record Stats(int connections, int subscribedCodes, Map<String, Integer> topCodes) {
    }
}
