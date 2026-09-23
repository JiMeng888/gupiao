package com.stocklab.analyst.market.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 榜单维度参数解析。
 *
 * <p>这个类存在的原因写在 {@link MoverMetric#parse} 的 javadoc 里：
 * {@code changePct} 曾经因为「小驼峰参数 vs 带下划线枚举名」被整体判成非法，
 * 而 {@code amount} 恰好能通过——一半能用比全部不能用更难被发现。
 * 这里的每一条都对应一种真实调用方会写出的形态。
 */
@DisplayName("榜单维度参数解析")
class MoverMetricParseTest {

    @Test
    @DisplayName("小驼峰 changePct 是前端约定形态，必须能解析")
    void parsesCamelCase() {
        assertThat(MoverMetric.parse("changePct")).isEqualTo(MoverMetric.CHANGE_PCT);
    }

    @Test
    @DisplayName("带下划线与全大写形态同样接受")
    void acceptsEnumNameForms() {
        assertThat(MoverMetric.parse("CHANGE_PCT")).isEqualTo(MoverMetric.CHANGE_PCT);
        assertThat(MoverMetric.parse("AMOUNT")).isEqualTo(MoverMetric.AMOUNT);
        assertThat(MoverMetric.parse(" amount ")).isEqualTo(MoverMetric.AMOUNT);
    }

    @Test
    @DisplayName("非法值与空值返回 null，交给 Controller 生成 400")
    void returnsNullForUnknown() {
        assertThat(MoverMetric.parse("turnover")).isNull();
        assertThat(MoverMetric.parse("")).isNull();
        assertThat(MoverMetric.parse(null)).isNull();
    }
}
