package com.stocklab.analyst.market.eastmoney;

import com.stocklab.analyst.market.model.Sector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EastMoneyMarketDataProvider#parseSectorsResponse} 单测。
 *
 * <p>样本取自 2026-10-21 对 {@code clist/get?fs=m:90+t:2+f:!50} 的真实响应。
 * 东财在板块字段上有两个反复出现的坑，正是这里要固化的：
 * <ul>
 *   <li><b>数值字段会以字符串返回</b>（家数、涨跌幅都有），直接 {@code asInt()}
 *       会得到 0——「0 家上涨」和「上游没给」在界面上是两件事；</li>
 *   <li><b>缺失时给的是 {@code "-"}</b>，不是 null 也不是省略字段。
 *       领涨股缺失时若兜底成空串，前端会渲染出一个空名字而不是「—」。</li>
 * </ul>
 */
@DisplayName("东财板块榜单解析")
class EastMoneySectorParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    @DisplayName("字段解析")
    class FieldParsing {

        private static final String SAMPLE = """
                {
                  "rc": 0,
                  "data": {
                    "total": 496,
                    "diff": [
                      {"f3":6.85,"f12":"BK1292","f14":"广告媒体","f104":2,"f105":1,
                       "f128":"兆讯传媒","f136":19.98,"f140":"301102"},
                      {"f3":"3.79","f12":"BK1512","f14":"生猪养殖","f104":"10","f105":"0",
                       "f128":"-","f136":"-","f140":"-"}
                    ]
                  }
                }
                """;

        @Test
        @DisplayName("领涨股代码 f140 为 6 位代码时保留")
        void parsesLeaderCode() throws Exception {
            List<Sector> sectors =
                    EastMoneyMarketDataProvider.parseSectorsResponse(MAPPER.readTree(SAMPLE));

            assertThat(sectors.get(0).leaderCode()).isEqualTo("301102");
            assertThat(sectors.get(0).leaderName()).isEqualTo("兆讯传媒");
            assertThat(sectors.get(0).leaderPct()).isEqualTo(19.98);
        }

        @Test
        @DisplayName("以字符串返回的涨跌幅与家数同样解析")
        void parsesStringNumbers() throws Exception {
            List<Sector> sectors =
                    EastMoneyMarketDataProvider.parseSectorsResponse(MAPPER.readTree(SAMPLE));

            assertThat(sectors.get(1).changePct()).isEqualTo(3.79);
            assertThat(sectors.get(1).upCount()).isEqualTo(10);
            assertThat(sectors.get(1).downCount()).isZero();
        }

        @Test
        @DisplayName("领涨股字段为 \"-\" 时给 null，不渲染成空名字")
        void dashBecomesNull() throws Exception {
            List<Sector> sectors =
                    EastMoneyMarketDataProvider.parseSectorsResponse(MAPPER.readTree(SAMPLE));

            assertThat(sectors.get(1).leaderName()).isNull();
            assertThat(sectors.get(1).leaderCode()).isNull();
            assertThat(sectors.get(1).leaderPct()).isNull();
        }
    }

    @Nested
    @DisplayName("异常形态")
    class RejectedEnvelope {

        @Test
        @DisplayName("上游拒绝（rc:102 / data:null）时返回空列表")
        void returnsEmptyOnNullData() throws Exception {
            String rejected = """
                    {"rc":102,"data":null}
                    """;

            assertThat(EastMoneyMarketDataProvider.parseSectorsResponse(MAPPER.readTree(rejected)))
                    .isEmpty();
        }

        @Test
        @DisplayName("代码或名称为空的行被跳过，不影响其余板块")
        void skipsRowsWithoutCodeOrName() throws Exception {
            String partial = """
                    {"rc":0,"data":{"diff":[
                      {"f3":1.0,"f12":"","f14":"无代码"},
                      {"f3":2.0,"f12":"BK0001","f14":"有效板块"}
                    ]}}
                    """;

            List<Sector> sectors =
                    EastMoneyMarketDataProvider.parseSectorsResponse(MAPPER.readTree(partial));

            assertThat(sectors).extracting(Sector::name).containsExactly("有效板块");
        }
    }
}
