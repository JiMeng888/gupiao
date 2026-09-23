package com.stocklab.analyst.market.eastmoney;

import com.stocklab.analyst.market.model.Mover;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 榜单响应解析（对真实上游样本）。
 *
 * <p>样本取自 2026-09-19 实测 {@code clist/get} 的原始返回，
 * 保留上游的真实形态：数值可能带小数（{@code f6} 成交额）、
 * 代码与名称是字符串、{@code data} 可能整体为 {@code null}。
 * 解析是最贴近「上游改版」的代码——样本常量化在这里，
 * 上游字段一变，先红的是这个类。
 */
@DisplayName("东财榜单响应解析")
class EastMoneyMoversParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 实测涨幅榜样本（截取前两条，字段顺序与上游一致）。 */
    private static final String REAL_SAMPLE = """
            {"rc":0,"rt":6,"svr":177617578,"lt":1,"full":1,"dlmkts":"","dsc":"0","data":{"total":5917,
            "diff":[
              {"f2":57.77,"f3":177.74,"f4":36.97,"f6":4816769833.0,"f12":"601091","f14":"C沈鼓"},
              {"f2":55.6,"f3":34.46,"f4":14.25,"f6":1907383882.0,"f12":"688837","f14":"C信诺维"}
            ]}}
            """;

    /** 上游拒绝服务时的形态：HTTP 200，但 data 为 null。 */
    private static final String REJECTED_SAMPLE =
            "{\"rc\":102,\"rt\":1,\"svr\":177617631,\"lt\":1,\"full\":1,\"dlmkts\":\"\",\"dsc\":\"0\",\"data\":null}";

    @Test
    @DisplayName("真实样本：字段码映射正确，金额保留精度")
    void parsesRealSample() throws Exception {
        List<Mover> movers = EastMoneyMarketDataProvider.parseMoversResponse(
                MAPPER.readTree(REAL_SAMPLE), "f3");

        assertThat(movers).hasSize(2);

        Mover first = movers.get(0);
        assertThat(first.code()).isEqualTo("601091");
        assertThat(first.name()).isEqualTo("C沈鼓");
        assertThat(first.price()).isEqualTo(57.77);
        assertThat(first.change()).isEqualTo(36.97);
        assertThat(first.changePct()).isEqualTo(177.74);
        assertThat(first.amount()).isEqualTo(4_816_769_833.0);
    }

    @Test
    @DisplayName("data 为 null（rc:102 拒绝形态）返回空列表，由调用方转成不可用")
    void returnsEmptyOnRejectedEnvelope() throws Exception {
        List<Mover> movers = EastMoneyMarketDataProvider.parseMoversResponse(
                MAPPER.readTree(REJECTED_SAMPLE), "f3");

        assertThat(movers).isEmpty();
    }

    @Test
    @DisplayName("非 6 位代码的条目被过滤，不进入榜单")
    void filtersNonSixDigitEntries() throws Exception {
        String body = """
                {"data":{"diff":[
                  {"f2":1.0,"f3":1.0,"f4":0.01,"f6":100.0,"f12":"BK0475","f14":"板块指数"},
                  {"f2":2.0,"f3":2.0,"f4":0.02,"f6":200.0,"f12":"600519","f14":"贵州茅台"}
                ]}}
                """;

        List<Mover> movers = EastMoneyMarketDataProvider.parseMoversResponse(
                MAPPER.readTree(body), "f3");

        assertThat(movers).hasSize(1);
        assertThat(movers.get(0).code()).isEqualTo("600519");
    }

    @Test
    @DisplayName("停牌等异常字段（\"-\"）按兜底值 0 处理而不是解析崩掉")
    void toleratesDashFields() throws Exception {
        String body = """
                {"data":{"diff":[
                  {"f2":"-","f3":"-","f4":"-","f6":"-","f12":"600999","f14":"停牌股"}
                ]}}
                """;

        List<Mover> movers = EastMoneyMarketDataProvider.parseMoversResponse(
                MAPPER.readTree(body), "f3");

        assertThat(movers).hasSize(1);
        assertThat(movers.get(0).price()).isZero();
        assertThat(movers.get(0).changePct()).isZero();
    }
}
