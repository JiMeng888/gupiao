package com.stocklab.analyst.ws;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.stocklab.analyst.market.model.Quote;

import java.time.Instant;
import java.util.List;

/**
 * WebSocket 消息协议。
 *
 * <h2>为什么要为每帧定义一个 record</h2>
 * 原 FastAPI 版本用裸 dict 拼帧（{@code {"type": "quote", "code": ...}}），
 * 字段名写错时不会有任何报错——只是前端偶尔收不到某个字段。
 * 这里把协议固化成类型，前端对照 Swagger / 本文件即可知道完整契约；
 * 同时序列化由 ObjectMapper 负责，杜绝手拼 JSON 时的转义漏洞
 * （股票名里的引号、反斜杠都会让手拼的 JSON 变成非法报文）。
 *
 * <h2>协议总览</h2>
 * <pre>
 * 上行 → {"action":"subscribe","codes":["600519","300750"]}
 *      → {"action":"unsubscribe","codes":["600519"]}
 *      → {"action":"ping"}
 * 下行 ← {"type":"ready","msg":"connected to /ws/quote"}
 *      ← {"type":"subscribed","codes":[...],"snapshots":[...],"rejected":[...]}
 *      ← {"type":"unsubscribed","codes":[...],"remaining":[...]}
 *      ← {"type":"quote",...}                          每 3 秒推送
 *      ← {"type":"pong","ts":1789635005}
 *      ← {"type":"error","code":"600519","message":"..."}
 * </pre>
 *
 * <h2>字段语义中容易踩的两个坑</h2>
 * <ol>
 *   <li><b>{@code subscribed.codes} 是「订阅后的完整集合」，不是「本次新增的项」。</b>
 *       发 {@code subscribe(["600519"])} 再发 {@code subscribe(["NOTACODE"])}，
 *       第二条回执里的 {@code codes} 仍是 {@code ["600519"]}，被拒的项只在
 *       {@code rejected} 里。这样设计是为了让前端可以「发一条指令，得到完整状态」，
 *       断线重连后不必自己维护增量。若前端把它当作本次新增，会把早就订阅的
 *       标的重复渲染一遍。</li>
 *   <li><b>{@code rejected[].code} 是把客户端原始输入原样（去控制字符、截断）回传的。</b>
 *       回传是必要的——前端要知道具体哪一条被拒；但它属于不可信内容，
 *       <b>必须用 {@code textContent} 渲染</b>，不能拼进 {@code innerHTML}。</li>
 * </ol>
 *
 * <h2>时间戳约定</h2>
 * 全站时间戳统一为 <b>epoch 秒</b>：本协议的 {@code ts}、REST 侧行情响应的
 * {@code ts}、数据库里的 {@code created_at} / {@code updated_at} 都是同一个单位。
 * 唯一使用 ISO-8601 字符串的是 AI 分析报告的 {@code generatedAt}
 * （它是给人看的生成时间，需要带时区偏移）。
 * 展示时区由前端决定，服务端不猜。
 */
public final class WsProtocol {

    private WsProtocol() {
    }

    /**
     * 上行消息。
     *
     * <p>忽略未知字段：前端升级时会先加字段、后改后端，
     * 若对未知字段直接报错，部署顺序就会变成硬约束。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Inbound(String action, List<String> codes) {
    }

    /** 连接建立通知。 */
    public record Ready(String type, String msg) {
        public static Ready of() {
            return new Ready("ready", "connected to /ws/quote");
        }
    }

    /** 心跳响应。{@code ts} 为服务端时间戳，供前端计算与服务端的时钟偏移。 */
    public record Pong(String type, long ts) {
        public static Pong now() {
            // 时间戳统一用 epoch 秒（与 REST 侧的 Quote.ts、DB 的 created_at 一致）。
            // 心跳用于估算时钟偏移，秒级精度完全够；混用毫秒与秒才是真麻烦——
            // 前端要拿 Pong.ts 去和 Quote.ts 比较时，差 1000 倍是最难查的那类 bug。
            return new Pong("pong", Instant.now().getEpochSecond());
        }
    }

    /** 错误通知。{@code code} 为空表示与具体标的相关。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(String type, String code, String message) {
        public static Error of(String message) {
            return new Error("error", null, message);
        }

        public static Error of(String code, String message) {
            return new Error("error", code, message);
        }
    }

    /** 单只标的的行情帧。字段与原 FastAPI 版本保持一致，便于前端复用。 */
    public record QuotePayload(
            String type,
            String code,
            String name,
            double price,
            double change,
            double changePct,
            double open,
            double high,
            double low,
            double prevClose,
            long volume,
            Double amount,
            double turnover,
            long ts
    ) {
        public static QuotePayload from(Quote quote) {
            return new QuotePayload(
                    "quote",
                    quote.code(),
                    quote.name(),
                    quote.price(),
                    quote.change(),
                    quote.changePct(),
                    quote.open(),
                    quote.high(),
                    quote.low(),
                    quote.prevClose(),
                    quote.volume(),
                    quote.amount(),
                    quote.turnover(),
                    quote.ts());
        }
    }

    /**
     * 订阅结果：订阅后的完整代码集合 + 本次取到的首批快照 + 本次被拒绝项。
     *
     * <p>{@code codes} 的语义见类注释「字段语义中容易踩的两个坑」第 1 条——
     * 它是<b>完整集合</b>而不是本次新增项。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Subscribed(
            String type,
            List<String> codes,
            List<QuotePayload> snapshots,
            List<RejectedCode> rejected
    ) {
        public static Subscribed of(List<String> codes,
                                    List<QuotePayload> snapshots,
                                    List<RejectedCode> rejected) {
            return new Subscribed("subscribed", codes, snapshots,
                    rejected.isEmpty() ? null : rejected);
        }
    }

    /**
     * 被拒绝的订阅项。把原因返回给前端，比静默丢弃更好定位问题。
     *
     * <p>{@code code} 是客户端原始输入（仅去控制字符并截断）。
     * 回传是必要的，但它不可信——前端必须用 {@code textContent} 渲染。
     */
    public record RejectedCode(String code, String reason) {
    }

    /** 取消订阅结果。 */
    public record Unsubscribed(String type, List<String> codes, List<String> remaining) {
        public static Unsubscribed of(List<String> codes, List<String> remaining) {
            return new Unsubscribed("unsubscribed", codes, remaining);
        }
    }
}
