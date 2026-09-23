package com.stocklab.analyst.modules.analysis.service;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.config.properties.LlmProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的对话补全客户端。
 *
 * <p>DeepSeek / 阿里云百炼（千问）/ OpenAI 官方都实现了
 * {@code POST {baseUrl}/v1/chat/completions}，请求与响应结构一致，
 * 因此一个客户端即可覆盖三家，只靠配置切换 {@code baseUrl} 与 {@code model}。
 * 这与原 FastAPI 版本的思路一致，但修正了其中的几处缺陷：
 *
 * <h2>修正 1：重试失败后异常类型不一致</h2>
 * 原实现只在 {@code response_format} 被拒绝时重试一次，若重试<b>仍然失败</b>，
 * 抛出的还是原始的 HTTP 异常，最终变成 500，而不是设计中的 502
 * {@code LLM_UPSTREAM_ERROR}。这里两条路径都归一化为 {@link BizException}。
 *
 * <h2>修正 2：超时与其它异常混为一谈</h2>
 * 原实现把所有异常都映射为 {@code LLM_UPSTREAM_ERROR}。
 * 超时是可重试的（提示用户稍后再试），上游 4xx 是配置问题（重试无用），
 * 两者的用户提示与运维动作完全不同，因此这里分开为
 * {@code LLM_TIMEOUT}（504）与 {@code LLM_UPSTREAM_ERROR}（502）。
 *
 * <h2>修正 3：异常信息可能过长</h2>
 * 上游 4xx 的响应体会被拼进错误消息，某些服务商会回显整个请求。
 * 这里统一截断到 {@value #MAX_ERROR_DETAIL} 字符，避免错误响应体膨胀。
 *
 * <h2>已知阻塞点</h2>
 * 这是<b>同步</b>调用，一次请求会占用一个 Tomcat 工作线程 10~40 秒。
 * 单机默认 200 线程下，约 5 个并发分析请求就能把线程池的可用余量吃掉大半。
 * 生产环境应改为「提交任务 → 返回 taskId → 前端轮询/WebSocket 推送结果」，
 * 但那是接口契约的变更，不在当前脚手架范围内。此处如实标注，不假装已解决。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    /** 错误详情截断长度。 */
    private static final int MAX_ERROR_DETAIL = 200;

    private final RestClient llmRestClient;
    private final ObjectMapper objectMapper;
    private final LlmProperties properties;
    private final MeterRegistry meterRegistry;

    public LlmClient(@Qualifier("llmRestClient") RestClient llmRestClient,
                     ObjectMapper objectMapper,
                     LlmProperties properties,
                     MeterRegistry meterRegistry) {
        this.llmRestClient = llmRestClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;

        log.info("大模型配置：provider={} model={} endpoint={} apiKey={} timeout={}s",
                properties.provider(),
                properties.effectiveModel(),
                properties.endpoint(),
                properties.maskedApiKey(),
                properties.timeout().toSeconds());
    }

    /** 是否具备调用条件。不具备时调用方应直接走规则化降级。 */
    public boolean enabled() {
        return properties.llmEnabled();
    }

    public String modelName() {
        return properties.effectiveModel();
    }

    /**
     * 发起一次对话补全。
     *
     * @param systemPrompt 系统提示词（角色与输出格式约束）
     * @param userPrompt   用户提示词（本次分析的数据与任务）
     * @return 模型返回的文本内容
     * @throws BizException 超时抛 {@code LLM_TIMEOUT}；其余失败抛 {@code LLM_UPSTREAM_ERROR}
     */
    public String complete(String systemPrompt, String userPrompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.effectiveModel());
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        // 低温度：分析类任务要的是稳定复现，不是创造力。同一份数据两次调用
        // 得到截然相反的结论会让用户对系统失去信任。
        payload.put("temperature", 0.3);
        if (properties.preferJsonMode()) {
            payload.put("response_format", Map.of("type", "json_object"));
        }

        Duration timeout = properties.timeout();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String content = doComplete(payload);
            recordOutcome("success", sample);
            return content;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            // 上游明确拒绝 response_format（部分千问模型如此）时去掉该字段重试一次。
            // 只在 4xx 且确实带了该字段时重试，避免对 5xx 做无意义重放。
            if (payload.containsKey("response_format") && (status == 400 || status == 422)) {
                log.info("上游拒绝 response_format（HTTP {}），去掉该字段重试一次", status);
                payload.remove("response_format");
                try {
                    String content = doComplete(payload);
                    recordOutcome("success_retry", sample);
                    return content;
                } catch (RestClientException retryEx) {
                    recordOutcome("error", sample);
                    throw upstreamError(retryEx, timeout);
                }
            }
            recordOutcome("error", sample);
            throw upstreamError(ex, timeout);
        } catch (ResourceAccessException ex) {
            // 连接超时 / 读超时 / 连接被重置
            recordOutcome("timeout", sample);
            throw new BizException(ErrorCode.LLM_TIMEOUT,
                    "大模型响应超时（已等待 " + timeout.toSeconds() + " 秒），请稍后重试");
        } catch (RestClientException ex) {
            recordOutcome("error", sample);
            throw upstreamError(ex, timeout);
        }
    }

    // ==================================================================

    private String doComplete(Map<String, Object> payload) {
        String body = llmRestClient.post()
                .uri(URI.create(properties.endpoint()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(String.class);

        if (body == null || body.isBlank()) {
            throw new BizException(ErrorCode.LLM_UPSTREAM_ERROR, "大模型返回空响应");
        }

        String content = extractContent(body);
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.LLM_UPSTREAM_ERROR, "大模型返回内容为空");
        }
        return content;
    }

    /**
     * 从响应报文中取出 {@code choices[0].message.content}。
     *
     * <p>逐层 {@code path()} 取值而不是强转对象：上游在限流、鉴权失败等场景下
     * 会返回结构完全不同的 JSON（如 {@code {"error": {...}}}），
     * 直接 {@code get("choices").get(0)} 会抛 NPE 或 IndexOutOfBounds，
     * 把一个可解释的上游错误变成 500。
     */
    private String extractContent(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (RuntimeException ex) {
            throw new BizException(ErrorCode.LLM_UPSTREAM_ERROR, "大模型响应不是合法 JSON");
        }

        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String message = error.path("message").isString() ? error.path("message").asString() : error.toString();
            throw new BizException(ErrorCode.LLM_UPSTREAM_ERROR,
                    "大模型返回错误：" + truncate(message));
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new BizException(ErrorCode.LLM_UPSTREAM_ERROR, "大模型响应缺少 choices 字段");
        }
        JsonNode content = choices.get(0).path("message").path("content");
        return content.isString() ? content.asString() : null;
    }

    private BizException upstreamError(Exception ex, Duration timeout) {
        if (ex instanceof RestClientResponseException responseEx) {
            int status = responseEx.getStatusCode().value();
            // 上游 429 是限流，与 4xx 参数错误的重试策略不同，单独给一句提示
            String hint = status == 429 ? "（上游限流，请降低调用频率）" : "";
            log.warn("大模型调用失败 HTTP {}{}：{}", status, hint,
                    truncate(responseEx.getResponseBodyAsString()));
            return new BizException(ErrorCode.LLM_UPSTREAM_ERROR,
                    "大模型调用失败（HTTP " + status + "）" + hint);
        }
        log.warn("大模型调用异常（超时设置 {}s）：{}", timeout.toSeconds(), ex.toString());
        return new BizException(ErrorCode.LLM_UPSTREAM_ERROR,
                "大模型调用异常：" + truncate(ex.getMessage()));
    }

    private void recordOutcome(String result, Timer.Sample sample) {
        meterRegistry.counter("app.llm.calls", "provider", properties.provider(), "result", result).increment();
        sample.stop(meterRegistry.timer("app.llm.call.duration", "provider", properties.provider()));
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_ERROR_DETAIL
                ? normalized
                : normalized.substring(0, MAX_ERROR_DETAIL) + "...";
    }
}
