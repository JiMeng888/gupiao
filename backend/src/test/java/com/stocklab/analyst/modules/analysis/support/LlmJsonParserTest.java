package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 大模型输出的 JSON 解析。
 *
 * <p>这一段此前是私有方法，测试需要启动整个 Spring 上下文才能碰到，
 * 实际结果是完全没测。而它恰恰是最容易在生产上炸的地方——
 * 不同服务商的模型对 {@code response_format} 的遵守程度不一致，
 * 包围栏、加前言、把数组写成字符串都会真实发生。
 * 因此这里逐个覆盖三级降级路径与数组清洗规则。
 */
@DisplayName("大模型输出 JSON 解析")
class LlmJsonParserTest {

    private final LlmJsonParser parser = new LlmJsonParser(JsonMapper.builder().build());

    @Nested
    @DisplayName("三级降级解析")
    class TolerantParsing {

        @Test
        @DisplayName("标准 JSON 直接解析")
        void parsesPlainJson() {
            JsonNode node = parser.parse("{\"thesis\":\"估值合理\"}");

            assertThat(node.path("thesis").asString()).isEqualTo("估值合理");
        }

        @Test
        @DisplayName("剥掉 markdown 围栏后解析")
        void parsesFencedJson() {
            JsonNode node = parser.parse("```json\n{\"thesis\":\"估值合理\"}\n```");

            assertThat(node.path("thesis").asString()).isEqualTo("估值合理");
        }

        @Test
        @DisplayName("围栏不带语言标记时同样能剥")
        void parsesFenceWithoutLanguageTag() {
            JsonNode node = parser.parse("```\n{\"thesis\":\"估值合理\"}\n```");

            assertThat(node.path("thesis").asString()).isEqualTo("估值合理");
        }

        @Test
        @DisplayName("前后夹带前言时截取首尾花括号之间解析")
        void parsesJsonWrappedInProse() {
            JsonNode node = parser.parse("好的，以下是分析：\n{\"thesis\":\"估值合理\"}\n希望对您有帮助。");

            assertThat(node.path("thesis").asString()).isEqualTo("估值合理");
        }

        @Test
        @DisplayName("完全无法解析时抛 LLM_UPSTREAM_ERROR，由上层降级")
        void throwsOnUnparseable() {
            assertThatThrownBy(() -> parser.parse("抱歉，我无法完成该请求。"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("无法解析");
        }

        @Test
        @DisplayName("解析出数组而非对象时同样拒绝")
        void rejectsNonObject() {
            assertThatThrownBy(() -> parser.parse("[1, 2, 3]"))
                    .isInstanceOf(BizException.class);
        }
    }

    @Nested
    @DisplayName("围栏剥离")
    class FenceStripping {

        @Test
        @DisplayName("按前缀成对剥离，不误伤正文中的反引号")
        void doesNotEatBackticksInsideBody() {
            // 原实现用 strip("`") 按字符剥离，会把这句里的反引号一起吃掉
            String fenced = "```json\n{\"thesis\":\"参考 `PE` 指标\"}\n```";

            String stripped = LlmJsonParser.stripCodeFence(fenced);

            assertThat(stripped).isEqualTo("{\"thesis\":\"参考 `PE` 指标\"}");
        }

        @Test
        @DisplayName("不是围栏时原样返回")
        void leavesPlainTextAlone() {
            assertThat(LlmJsonParser.stripCodeFence("  {\"a\":1}  ")).isEqualTo("{\"a\":1}");
        }
    }

    @Nested
    @DisplayName("字段读取")
    class FieldReading {

        @Test
        @DisplayName("字段缺失或空白时返回兜底文案")
        void readTextFallsBack() {
            JsonNode root = parser.parse("{\"thesis\":\"   \"}");

            assertThat(LlmJsonParser.readText(root, "thesis", "模型未给出核心论点"))
                    .isEqualTo("模型未给出核心论点");
            assertThat(LlmJsonParser.readText(root, "missing", "兜底"))
                    .isEqualTo("兜底");
        }

        @Test
        @DisplayName("数组读取过滤非字符串项并去重")
        void readListFiltersAndDeduplicates() {
            JsonNode root = parser.parse(
                    "{\"risks\":[\"流动性风险\", 123, \"流动性风险\", null, \"行业风险\"]}");

            assertThat(LlmJsonParser.readList(root, "risks"))
                    .containsExactly("流动性风险", "行业风险");
        }

        @Test
        @DisplayName("模型输出十几条时截断到上限，避免前端布局被撑坏")
        void readListTruncatesToLimit() {
            JsonNode root = parser.parse(
                    "{\"risks\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\"]}");

            assertThat(LlmJsonParser.readList(root, "risks")).hasSize(AnalysisLimits.MAX_LIST_ITEMS);
        }

        @Test
        @DisplayName("字段不是数组时返回空列表而不是抛错")
        void readListHandlesNonArray() {
            JsonNode root = parser.parse("{\"risks\":\"只有一条字符串\"}");

            assertThat(LlmJsonParser.readList(root, "risks")).isEqualTo(List.of());
        }
    }
}
