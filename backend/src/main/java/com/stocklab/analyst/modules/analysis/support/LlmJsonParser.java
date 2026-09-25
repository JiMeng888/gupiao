package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static com.stocklab.analyst.modules.analysis.support.AnalysisLimits.MAX_LIST_ITEMS;

/**
 * 大模型输出的 JSON 解析。
 *
 * <h2>为什么不能只写一句 {@code readValue}</h2>
 * 即使开了 {@code response_format=json_object}，也不是所有服务商都会严格遵守
 * （尤其千问的部分模型），常见偏差是包一层 markdown 围栏，或在 JSON 前后
 * 加一段「好的，以下是分析：」。因此 {@link #parse} 做三级尝试：
 * <ol>
 *   <li>直接解析</li>
 *   <li>剥掉围栏后解析</li>
 *   <li>截取第一个 <code>{</code> 到最后一个 <code>{@code }</code> 之间解析</li>
 * </ol>
 *
 * <h2>相对原实现的一处修正</h2>
 * 原实现的围栏剥离用 {@code text.strip("`")}——这是按<b>字符集</b>剥离而非按前缀，
 * 若 JSON 内容里含反引号会被误伤（例如某条风险描述里写了「`」）。
 * 这里改为显式判断前缀并成对剥离，见 {@link #stripCodeFence}。
 *
 * <p>三级都失败时抛 {@code LLM_UPSTREAM_ERROR}，由上层统一降级为规则化报告。
 */
@Component
public class LlmJsonParser {

    private final ObjectMapper objectMapper;

    public LlmJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析模型输出为 JSON 对象。
     *
     * @throws BizException 三级尝试都失败，或结果不是 JSON 对象
     */
    public JsonNode parse(String content) {
        String text = content.trim();

        JsonNode node = tryParse(text);
        if (node == null && text.startsWith("```")) {
            node = tryParse(stripCodeFence(text));
        }
        if (node == null) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                node = tryParse(text.substring(start, end + 1));
            }
        }
        if (node == null || !node.isObject()) {
            throw new BizException(ErrorCode.LLM_UPSTREAM_ERROR,
                    "模型输出无法解析为约定的 JSON 结构");
        }
        return node;
    }

    private JsonNode tryParse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 成对剥离 markdown 代码围栏；不是围栏就原样返回（仅 trim）。 */
    static String stripCodeFence(String text) {
        String result = text;
        if (result.startsWith("```")) {
            result = result.substring(3);
            if (result.startsWith("json") || result.startsWith("JSON")) {
                result = result.substring(4);
            }
            if (result.endsWith("```")) {
                result = result.substring(0, result.length() - 3);
            }
        }
        return result.trim();
    }

    /** 读取字符串字段；缺失或空白时返回 {@code fallback}。 */
    public static String readText(JsonNode root, String field, String fallback) {
        JsonNode value = root.path(field);
        if (value.isString()) {
            String text = value.asString().trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return fallback;
    }

    /**
     * 读取字符串数组。
     *
     * <p>做三件事：过滤非字符串项、去重、截断到 {@value AnalysisLimits#MAX_LIST_ITEMS} 条。
     * 模型偶尔会把数组写成一个字符串或塞进十几条，直接透传会把前端布局撑坏。
     */
    public static List<String> readList(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isArray()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isString()) {
                String text = item.asString().trim();
                if (!text.isEmpty() && !items.contains(text)) {
                    items.add(text);
                }
            }
            if (items.size() >= MAX_LIST_ITEMS) {
                break;
            }
        }
        return List.copyOf(items);
    }
}
