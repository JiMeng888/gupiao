package com.stocklab.analyst.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;

/**
 * 大模型（AI 分析）配置。
 *
 * <p>三家服务商均遵循 OpenAI Chat Completions 协议，因此统一用同一套调用逻辑，
 * 只切换 baseUrl / model。未配置 {@code apiKey} 时自动降级为规则化报告，
 * 保证无网络、无 Key 的环境也能跑通完整链路。
 *
 * @param provider  服务商：{@code deepseek} / {@code qwen} / {@code openai} / {@code mock}
 * @param apiKey    API Key，留空则走规则化降级报告
 * @param baseUrl   覆盖默认 baseUrl（留空用各服务商预设）
 * @param model     覆盖默认模型名（留空用各服务商预设）
 * @param timeout   调用超时
 * @param maxTokens 期望的最大输出 token（部分服务商忽略）
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
        String provider,
        String apiKey,
        String baseUrl,
        String model,
        Duration timeout,
        Integer maxTokens
) {

    /** DeepSeek 开放平台。 */
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private static final String DEEPSEEK_MODEL = "deepseek-chat";

    /** 阿里云百炼（DashScope）OpenAI 兼容模式。 */
    private static final String QWEN_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode";
    private static final String QWEN_MODEL = "qwen-plus";

    /** OpenAI 官方。 */
    private static final String OPENAI_BASE_URL = "https://api.openai.com";
    private static final String OPENAI_MODEL = "gpt-4o-mini";

    /**
     * 兜底默认值。
     *
     * <p>{@code timeout} 必须有默认值：大模型生成长文本要 10~40 秒，
     * 若配置里漏填则 {@code RestClient} 的读超时为 {@code null}，
     * 表现为「永远不超时」（连接线程被无限占用），比超时更难排查。
     *
     * <p>默认 provider 取 {@code deepseek} 而不是 {@code mock}：
     * 「没有 Key」与「故意不调模型」是两回事，前者由 {@link #llmEnabled()} 判定，
     * 不应靠 provider 名字来表达。
     */
    public LlmProperties {
        if (provider == null || provider.isBlank()) {
            provider = "deepseek";
        }
        provider = provider.trim().toLowerCase(Locale.ROOT);
        if (timeout == null) {
            timeout = Duration.ofSeconds(45);
        }
        if (maxTokens == null) {
            maxTokens = 1500;
        }
    }

    /**
     * 是否可以真正调用大模型。
     *
     * <p>两个条件缺一不可：配了 Key，且没有显式指定 {@code provider=mock}。
     */
    public boolean llmEnabled() {
        return !"mock".equals(provider) && apiKey != null && !apiKey.isBlank();
    }

    /**
     * 解析最终的 chat completions 端点。
     *
     * <p><b>{@code baseUrl} 不要带 {@code /v1}</b>——路径由这里统一拼接，
     * 否则会拼出 {@code .../v1/v1/chat/completions}。
     */
    public String endpoint() {
        String base = baseUrl != null && !baseUrl.isBlank() ? baseUrl : defaultBaseUrl();
        return stripTrailingSlash(base) + "/v1/chat/completions";
    }

    /** 解析最终使用的模型名。 */
    public String effectiveModel() {
        if (model != null && !model.isBlank()) {
            return model.trim();
        }
        return switch (provider) {
            case "qwen" -> QWEN_MODEL;
            case "openai" -> OPENAI_MODEL;
            default -> DEEPSEEK_MODEL;
        };
    }

    /**
     * 是否使用 {@code response_format=json_object} 强制 JSON 输出。
     *
     * <p>DeepSeek 与 OpenAI 官方稳定支持。阿里云百炼的兼容模式对部分模型
     * 不支持该参数并会返回 400，因此默认关闭——由调用方在 400 时去掉该字段重试，
     * 而不是在这里猜哪家支持。
     */
    public boolean preferJsonMode() {
        return "deepseek".equals(provider) || "openai".equals(provider);
    }

    private String defaultBaseUrl() {
        return switch (provider) {
            case "qwen" -> QWEN_BASE_URL;
            case "openai" -> OPENAI_BASE_URL;
            default -> DEEPSEEK_BASE_URL;
        };
    }

    private static String stripTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 仅暴露给日志的脱敏后 Key 摘要，避免密钥泄漏到日志。 */
    public String maskedApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            return "(未配置)";
        }
        return apiKey.length() <= 8 ? "****" : apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
