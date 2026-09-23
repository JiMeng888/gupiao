package com.stocklab.analyst.config;

import com.stocklab.analyst.config.properties.CorsProperties;
import com.stocklab.analyst.ws.QuoteSessionRegistry;
import com.stocklab.analyst.ws.QuoteWebSocketHandler;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 装配。
 *
 * <p>用原生 WebSocket 而不是 STOMP：本项目的推送是单向的
 * 「订阅一组代码 → 服务端定时下发行情」，不需要 STOMP 提供的
 * 目的地路由、消息代理、心跳协商等能力。引入 STOMP 只会让前端多一个
 * 依赖（{@code sockjs-client} + {@code stompjs}），而协议本身
 * 仍然要自定义（订阅的是股票代码，不是频道）。
 *
 * <p>{@code setAllowedOriginPatterns} 是必需的：WebSocket 握手不受
 * {@code CorsRegistry} 约束（那套只作用于 MVC 的 HTTP 请求），
 * 不在这里单独配置时，跨域的浏览器连接会被直接拒绝，
 * 而 curl / 测试工具仍然能连上，表现为「本地测试通过、前端连不上」。
 *
 * <h2>为什么也要设会话空闲超时</h2>
 * 客户端异常退出（断网、进程被杀）时 TCP 连接可能长时间不关闭。
 * 没有空闲超时的情况下这些连接会一直留在会话表里，每 3 秒被推送一次、
 * 每次写失败才被发现，白耗资源。10 分钟空闲即回收，
 * 前端只要按 30 秒一次的心跳就永远不会被误杀。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** 上行单帧缓冲上限；真正的业务级长度校验在 Handler 里。 */
    private static final int MAX_TEXT_MESSAGE_BUFFER_SIZE = 16 * 1024;

    /** 会话空闲超时（毫秒）。前端心跳间隔应显著小于该值。 */
    private static final long MAX_SESSION_IDLE_TIMEOUT_MS = 600_000L;

    private final QuoteWebSocketHandler quoteWebSocketHandler;
    private final CorsProperties corsProperties;

    public WebSocketConfig(QuoteWebSocketHandler quoteWebSocketHandler,
                           CorsProperties corsProperties) {
        this.quoteWebSocketHandler = quoteWebSocketHandler;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(quoteWebSocketHandler, "/ws/quote")
                .setAllowedOriginPatterns(corsProperties.allowedOriginPatterns().toArray(String[]::new));
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BUFFER_SIZE);
        container.setMaxBinaryMessageBufferSize(MAX_TEXT_MESSAGE_BUFFER_SIZE);
        container.setMaxSessionIdleTimeout(MAX_SESSION_IDLE_TIMEOUT_MS);
        return container;
    }

    /**
     * 连接数与订阅数指标。
     *
     * <p>这两个值是判断「推送是否成为瓶颈」的第一手依据：
     * 订阅数突然涨到上限附近说明有人在滥用；连接数持续只增不减
     * 说明连接回收有问题。
     */
    @Bean
    public MeterBinder quoteWebSocketMetrics(QuoteSessionRegistry registry) {
        return meterRegistry -> {
            Gauge.builder("app.ws.connections", registry, QuoteSessionRegistry::connectionCount)
                    .description("当前 WebSocket 连接数")
                    .register(meterRegistry);
            Gauge.builder("app.ws.subscribed.codes", registry, QuoteSessionRegistry::subscribedCodeCount)
                    .description("当前被订阅的标的数")
                    .register(meterRegistry);
        };
    }
}
