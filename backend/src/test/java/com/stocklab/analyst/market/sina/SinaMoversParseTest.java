package com.stocklab.analyst.market.sina;

import com.stocklab.analyst.market.model.Mover;
import com.stocklab.analyst.market.model.MoverMetric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新浪榜单解析的单测。
 *
 * <p>样本取自 <b>2026-10-21 真实上游响应</b>（{@code node=hs_a&sort=changepercent&asc=0}），
 * 只截取前三行、只保留本项目用得到的字段。用真实样本而不是自己编的 JSON，
 * 是因为自己编的 JSON 永远符合自己的假设——真实响应的价值恰恰在于
 * 「{@code trade} 是字符串、{@code changepercent} 是数字、中文以 {@code \\uXXXX}
 * 转义返回」这些<b>混用形态</b>，它们才是解析器出错的地方。
 */
class SinaMoversParseTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 真实响应样本（涨幅榜前三）。 */
    private static final String REAL_GAINERS = """
            [{"symbol":"bj920296","code":"920296","name":"N文峰","trade":"36.300",
              "pricechange":24.85,"changepercent":217.031,"settlement":"11.450",
              "volume":19328940,"amount":759246930,"ticktime":"15:30:01"},
             {"symbol":"sz300296","code":"300296","name":"利亚德","trade":"5.630",
              "pricechange":0.94,"changepercent":20.043,"settlement":"4.690",
              "volume":305657967,"amount":1620859495,"ticktime":"16:29:45"},
             {"symbol":"sh688500","code":"688500","name":"慧辰股份","trade":"37.560",
              "pricechange":6.26,"changepercent":20.000,"settlement":"31.300",
              "volume":8372733,"amount":293968997,"ticktime":"15:34:59"}]
            """;

    private static JsonNode json(String raw) {
        return MAPPER.readTree(raw);
    }

    @Nested
    @DisplayName("解析与排序")
    class Parsing {

        @Test
        @DisplayName("字符串价格与数字涨跌幅混用也能解析：字段形态不一致是真实响应的常态")
        void parsesMixedFieldTypes() {
            List<Mover> movers = SinaMarketDataProvider.parseMovers(
                    json(REAL_GAINERS), MoverMetric.CHANGE_PCT, false);

            assertThat(movers).hasSize(3);
            Mover first = movers.get(0);
            assertThat(first.code()).isEqualTo("920296");
            assertThat(first.name()).isEqualTo("N文峰");
            // trade 是字符串 "36.300"，必须能读成 36.3
            assertThat(first.price()).isEqualTo(36.3);
            assertThat(first.changePct()).isEqualTo(217.031);
            assertThat(first.change()).isEqualTo(24.85);
            assertThat(first.amount()).isEqualTo(759_246_930);
        }

        @Test
        @DisplayName("降序取涨幅榜：首位是涨得最多的那只")
        void sortsDescendingForGainers() {
            List<Mover> movers = SinaMarketDataProvider.parseMovers(
                    json(REAL_GAINERS), MoverMetric.CHANGE_PCT, false);

            assertThat(movers).extracting(Mover::changePct)
                    .containsExactly(217.031, 20.043, 20.0);
        }

        @Test
        @DisplayName("升序取跌幅榜：即便上游给的是降序，也要在本地重排")
        void sortsAscendingLocallyForLosers() {
            // 上游对 asc 参数的行为没有文档保证，契约不能寄托在对方实现细节上
            List<Mover> movers = SinaMarketDataProvider.parseMovers(
                    json(REAL_GAINERS), MoverMetric.CHANGE_PCT, true);

            assertThat(movers).extracting(Mover::code)
                    .as("升序 = 跌幅榜，最跌的排第一")
                    .containsExactly("688500", "300296", "920296");
        }

        @Test
        @DisplayName("按成交额排序时用 amount 而不是涨跌幅")
        void sortsByAmountWhenRequested() {
            List<Mover> movers = SinaMarketDataProvider.parseMovers(
                    json(REAL_GAINERS), MoverMetric.AMOUNT, false);

            assertThat(movers).extracting(Mover::code)
                    .containsExactly("300296", "920296", "688500");
        }
    }

    @Nested
    @DisplayName("丢掉无法展示的行")
    class Filtering {

        @Test
        @DisplayName("停牌价 0 的行被丢弃：上榜会被读成「股价归零」")
        void dropsZeroPriceRows() {
            String raw = """
                    [{"code":"600519","name":"贵州茅台","trade":"0.000","changepercent":0.0,"amount":0},
                     {"code":"000001","name":"平安银行","trade":"11.400","changepercent":1.2,"amount":1e8}]
                    """;

            List<Mover> movers = SinaMarketDataProvider.parseMovers(
                    json(raw), MoverMetric.CHANGE_PCT, false);

            assertThat(movers).extracting(Mover::code).containsExactly("000001");
        }

        @Test
        @DisplayName("非 6 位代码的行被丢弃：前端按 6 位代码跳转个股页")
        void dropsNonStockRows() {
            String raw = """
                    [{"code":"sh000001","name":"上证指数","trade":"3100.0","changepercent":0.5,"amount":1},
                     {"code":"600519","name":"贵州茅台","trade":"1700.0","changepercent":0.5,"amount":1}]
                    """;

            List<Mover> movers = SinaMarketDataProvider.parseMovers(
                    json(raw), MoverMetric.CHANGE_PCT, false);

            assertThat(movers).extracting(Mover::code).containsExactly("600519");
        }

        @Test
        @DisplayName("上游返回字面量 null 或空数组时给空列表，由调用方决定意味着什么")
        void emptyEnvelopeYieldsEmptyList() {
            assertThat(SinaMarketDataProvider.parseMovers(
                    json("null"), MoverMetric.CHANGE_PCT, false)).isEmpty();
            assertThat(SinaMarketDataProvider.parseMovers(
                    json("[]"), MoverMetric.CHANGE_PCT, false)).isEmpty();
            assertThat(SinaMarketDataProvider.parseMovers(
                    null, MoverMetric.CHANGE_PCT, false)).isEmpty();
        }
    }

    @Nested
    @DisplayName("响应解码")
    class Decoding {

        @Test
        @DisplayName("UTF-8 原文与 GBK 原文都能解出正确的中文")
        void decodesBothCharsets() {
            Charset gbk = Charset.forName("GBK");

            assertThat(SinaMarketDataProvider.decode("文峰".getBytes(StandardCharsets.UTF_8)))
                    .as("响应头声明 gbk 但正文其实是 \\uXXXX 转义/UTF-8 的情况")
                    .isEqualTo("文峰");
            assertThat(SinaMarketDataProvider.decode("文峰".getBytes(gbk)))
                    .as("上游真的按 gbk 给原文时，UTF-8 严格解码会失败并回退")
                    .isEqualTo("文峰");
        }
    }
}
