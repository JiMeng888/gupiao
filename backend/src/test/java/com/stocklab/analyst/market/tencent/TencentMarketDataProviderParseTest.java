package com.stocklab.analyst.market.tencent;

import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.Quote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TencentMarketDataProvider} 解析逻辑单测。
 *
 * <p>样本全部取自 2026-09-21 对真实上游的实测响应（宝钢股份 600019 /
 * 上证指数等），不是编造数据——解析代码的错误几乎都出在「对字段位置、
 * 编码、异常形态的想当然」上，只有真实样本能暴露它们。
 */
@DisplayName("腾讯行情数据源解析")
class TencentMarketDataProviderParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================================================================
    @Nested
    @DisplayName("实时报价")
    class QuoteParsing {

        /** 2026-09-21 实测：GET qt.gtimg.cn/q=sh600019（GBK 解码后）。 */
        private static final String SAMPLE = """
                v_sh600019="1~宝钢股份~600019~5.78~5.76~5.76~811118~356509~454609~5.78~873~5.77~4113~5.76~3650~5.75~3567~5.74~1536~5.79~18050~5.80~13095~5.81~12566~5.82~13870~5.83~5479~~20260921161459~0.02~0.35~5.79~5.69~5.78/811118/465772997~811118~46577~0.38~12.54~~5.79~5.69~1.74~1237.73~1259.00~0.62~6.34~5.18~1.04~-49321~5.74~13.77~12.17~~~0.49~46577.2997~7.1672~124~   A~GP-A~-19.05~1.23~5.35~4.87~2.98~7.52~5.17~2.85~0.87~6.84~21414095749~21782084749~-64.22~-15.99~21414095749~~~-10.83~0.00~~CNY~0~___D__F__N~5.69~18730~";
                """;

        @Test
        @DisplayName("正确解析名称价格与涨跌")
        void parsesBasicFields() {
            Quote quote = TencentMarketDataProvider.parseQuote(SAMPLE, "sh600019");

            assertThat(quote).isNotNull();
            assertThat(quote.code()).isEqualTo("600019");
            assertThat(quote.name()).isEqualTo("宝钢股份");
            assertThat(quote.exchange()).isEqualTo("SH");
            assertThat(quote.price()).isEqualTo(5.78);
            assertThat(quote.prevClose()).isEqualTo(5.76);
            assertThat(quote.open()).isEqualTo(5.76);
            assertThat(quote.high()).isEqualTo(5.79);
            assertThat(quote.low()).isEqualTo(5.69);
            assertThat(quote.change()).isEqualTo(0.02);
            assertThat(quote.changePct()).isEqualTo(0.35);
        }

        @Test
        @DisplayName("成交量单位为手，成交额由万元换算为元")
        void parsesVolumeAndAmount() {
            Quote quote = TencentMarketDataProvider.parseQuote(SAMPLE, "sh600019");

            assertThat(quote.volume()).isEqualTo(811_118L);
            // 上游 46577 万元 = 465,770,000 元
            assertThat(quote.amount()).isEqualTo(465_770_000.0);
            assertThat(quote.turnover()).isEqualTo(0.38);
        }

        @Test
        @DisplayName("时间字段按上海时区解析为 epoch 秒")
        void parsesTimestamp() {
            Quote quote = TencentMarketDataProvider.parseQuote(SAMPLE, "sh600019");

            // 2026-09-21 16:14:59 Asia/Shanghai = 1789978499 epoch 秒
            assertThat(quote.ts()).isEqualTo(1_789_978_499L);
        }

        @Test
        @DisplayName("上游返回空数据时视为标的不存在，返回 null")
        void emptyPayloadMeansNotFound() {
            assertThat(TencentMarketDataProvider.parseQuote(
                    "v_sh999999=\"\";\n", "sh999999")).isNull();
            assertThat(TencentMarketDataProvider.parseQuote("pv_none=1;\n", "sh999999")).isNull();
        }

        @Test
        @DisplayName("从多行响应中提取目标变量，不受其它行干扰")
        void extractsTargetVariableFromMultiLineBody() {
            String body = """
                    pv_none=1;
                    v_sh600019="1~宝钢股份~600019~5.78~5.76~5.76~811118~356509~454609~5.78~873~5.77~4113~5.76~3650~5.75~3567~5.74~1536~5.79~18050~5.80~13095~5.81~12566~5.82~13870~5.83~5479~~20260921161459~0.02~0.35~5.79~5.69~5.78/811118/465772997~811118~46577~0.38~12.54~~5.79~5.69~1.74~1237.73~1259.00~0.62~6.34~5.18~1.04~-49321~5.74~13.77~12.17~~~0.49~46577.2997~7.1672~124~   A~GP-A~-19.05~1.23~5.35~4.87~2.98~7.52~5.17~2.85~0.87~6.84~21414095749~21782084749~-64.22~-15.99~21414095749~~~-10.83~0.00~~CNY~0~___D__F__N~5.69~18730~";
                    v_sz000001="1~平安银行~000001~11.50~11.48~11.49~970000~0~0~~20260921161459~0.02~0.17~11.60~11.40~11.50/970000/111550000~970000~11155~0.50~4.50~~11.60~11.40~1.74~2200.00~2230.00~0.60~12.63~10.33~1.04~-49321~11.44~13.77~12.17";
                    """;

            Quote quote = TencentMarketDataProvider.parseQuote(body, "sh600019");
            assertThat(quote.name()).isEqualTo("宝钢股份");
            assertThat(quote.code()).isEqualTo("600019");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("日 / 周 / 月 K 线")
    class KlineParsing {

        /**
         * 2026-09-21 实测：fqkline/get day qfq。
         * 行结构 [日期, 开, 收, 高, 低, 量(手)]——第 2 项是收盘、第 3 项才是最高，
         * 且除权日（09-16）第 7 项是分红信息对象而非数值。
         */
        private static final String SAMPLE = """
                {"code":0,"msg":"","data":{"sh600019":{"qfqday":[
                ["2026-09-14","5.690","5.710","5.750","5.660","715103.000"],
                ["2026-09-15","5.720","5.750","5.780","5.690","914604.000"],
                ["2026-09-16","5.780","5.770","5.790","5.670","939332.000",{"nd":"2026","fh_sh":"1.3","djr":"2026-09-15","cqr":"2026-09-16","FHcontent":"10派1.3元"}],
                ["2026-09-17","5.750","5.720","5.760","5.680","632515.000"],
                ["2026-09-18","5.720","5.760","5.790","5.700","702189.000"],
                ["2026-09-21","5.76","5.78","5.79","5.69","811118"]],
                "qt":{"sh600019":["1"]},"prec":"5.710","version":"18"}}}
                """;

        private List<Bar> parse() throws Exception {
            return TencentMarketDataProvider.parseKline(
                    MAPPER.readTree(SAMPLE), "sh600019", "qfqday");
        }

        @Test
        @DisplayName("开高低收按语义下标解析，不被「顺序恰好对齐」的巧合误导")
        void parsesOhlcCorrectly() throws Exception {
            List<Bar> bars = parse();

            assertThat(bars).hasSize(6);
            Bar first = bars.get(0);
            assertThat(first.date()).isEqualTo("2026-09-14");
            assertThat(first.open()).isEqualTo(5.69);
            assertThat(first.close()).isEqualTo(5.71);
            assertThat(first.high()).isEqualTo(5.75);
            assertThat(first.low()).isEqualTo(5.66);
            assertThat(first.volume()).isEqualTo(715_103L);
        }

        @Test
        @DisplayName("昨收由前一根收盘价回填，首根为 null")
        void fillsPrevCloseFromPreviousBar() throws Exception {
            List<Bar> bars = parse();

            assertThat(bars.get(0).prevClose()).isNull();
            assertThat(bars.get(1).prevClose()).isEqualTo(5.71);
            assertThat(bars.get(2).prevClose()).isEqualTo(5.75);
        }

        @Test
        @DisplayName("除权日的分红信息对象被跳过，不影响数值解析")
        void skipsDividendInfoObject() throws Exception {
            List<Bar> bars = parse();

            Bar exDividendDay = bars.get(2);
            assertThat(exDividendDay.date()).isEqualTo("2026-09-16");
            assertThat(exDividendDay.close()).isEqualTo(5.77);
            assertThat(exDividendDay.high()).isEqualTo(5.79);
        }

        @Test
        @DisplayName("空数据返回空列表而不是异常")
        void emptyDataReturnsEmptyList() throws Exception {
            assertThat(TencentMarketDataProvider.parseKline(
                    MAPPER.readTree("{\"code\":0,\"data\":{\"sh600019\":{}}}"),
                    "sh600019", "qfqday")).isEmpty();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("分时")
    class TrendParsing {

        /**
         * 2026-09-21 实测：minute/query。
         * 每项 "HHMM 价格 累计量(手) 累计额(元)"，量额需要差分。
         */
        private static final String SAMPLE = """
                {"code":0,"msg":"","data":{"sh600019":{"data":{"date":"20260921","data":[
                "0930 5.76 2177 1253952.00",
                "0931 5.75 16678 9600551.00",
                "0932 5.75 25190 14486447.00"]}}}}
                """;

        @Test
        @DisplayName("日期与分钟拼成 yyyy-MM-dd HH:mm 格式")
        void buildsDateTimeFromTradeDateAndMinute() {
            List<Bar> bars = TencentMarketDataProvider.parseTrend(MAPPER.readTree(SAMPLE), "sh600019");

            assertThat(bars).hasSize(3);
            assertThat(bars.get(0).date()).isEqualTo("2026-09-21 09:30");
            assertThat(bars.get(1).date()).isEqualTo("2026-09-21 09:31");
        }

        @Test
        @DisplayName("累计量额差分为单根量额，价格填充四价")
        void diffsCumulativeVolumeAndAmount() {
            List<Bar> bars = TencentMarketDataProvider.parseTrend(MAPPER.readTree(SAMPLE), "sh600019");

            Bar first = bars.get(0);
            assertThat(first.volume()).isEqualTo(2_177L);
            assertThat(first.amount()).isEqualTo(1_253_952.0);
            assertThat(first.close()).isEqualTo(5.76);

            Bar second = bars.get(1);
            // 16678 - 2177
            assertThat(second.volume()).isEqualTo(14_501L);
            // 9600551.00 - 1253952.00
            assertThat(second.amount()).isEqualTo(8_346_599.0);
            assertThat(second.close()).isEqualTo(5.75);
        }

        @Test
        @DisplayName("昨收由前一分时价格回填，首根为 null")
        void fillsPrevPriceFromPreviousTick() {
            List<Bar> bars = TencentMarketDataProvider.parseTrend(MAPPER.readTree(SAMPLE), "sh600019");

            assertThat(bars.get(0).prevClose()).isNull();
            assertThat(bars.get(1).prevClose()).isEqualTo(5.76);
            assertThat(bars.get(2).prevClose()).isEqualTo(5.75);
        }

        @Test
        @DisplayName("交易日期缺失时返回空列表，不产出垃圾数据")
        void missingTradeDateReturnsEmpty() {
            String broken = "{\"code\":0,\"data\":{\"sh600019\":{\"data\":{\"data\":[\"0930 5.76 2177 1253952.00\"]}}}}";

            assertThat(TencentMarketDataProvider.parseTrend(MAPPER.readTree(broken), "sh600019"))
                    .isEmpty();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("指数")
    class IndexParsing {

        /** 2026-09-21 实测：qt.gtimg.cn/q=s_sh000001,... 简版格式。 */
        private static final String SAMPLE = """
                v_s_sh000001="1~上证指数~000001~3949.91~38.04~0.97~502354877~94681912~~703790.12~ZS~";
                v_s_sz399001="51~深证成指~399001~13730.02~89.15~0.65~631708071~108469436~~445909.24~ZS~";
                v_s_sz399006="51~创业板指~399006~3399.59~26.91~0.80~176424755~52222240~~187274.66~ZS~";
                v_s_sh000688="1~科创50~000688~1657.48~4.85~0.29~7600285~8925053~~59638.85~ZS~";
                v_s_sh000300="1~沪深300~000300~4539.57~32.18~0.71~186361542~49543593~~544636.55~ZS~";
                """;

        @Test
        @DisplayName("解析五大指数，代码带交易所后缀与东财保持一致")
        void parsesAllIndices() {
            List<com.stocklab.analyst.market.model.IndexSnapshot> indices =
                    TencentMarketDataProvider.parseIndices(SAMPLE);

            assertThat(indices).hasSize(5);

            com.stocklab.analyst.market.model.IndexSnapshot sh = indices.get(0);
            assertThat(sh.code()).isEqualTo("000001.SH");
            assertThat(sh.name()).isEqualTo("上证指数");
            assertThat(sh.value()).isEqualTo(3949.91);
            assertThat(sh.change()).isEqualTo(38.04);
            assertThat(sh.changePct()).isEqualTo(0.97);

            assertThat(indices.get(1).code()).isEqualTo("399001.SZ");
            assertThat(indices.get(4).name()).isEqualTo("沪深300");
        }

        @Test
        @DisplayName("缺失的指数行被跳过，不影响其余指数")
        void skipsMissingIndexLines() {
            String partial = """
                    v_s_sh000001="1~上证指数~000001~3949.91~38.04~0.97~502354877~94681912~~703790.12~ZS~";
                    """;

            List<com.stocklab.analyst.market.model.IndexSnapshot> indices =
                    TencentMarketDataProvider.parseIndices(partial);

            assertThat(indices).hasSize(1);
            assertThat(indices.get(0).code()).isEqualTo("000001.SH");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("板块榜单")
    class SectorRanking {

        /**
         * 2026-10-21 实测 {@code proxy.finance.qq.com/cgi/cgi-bin/rank/pt/getRank}
         * 的真实响应（只保留解析用到的字段，值未改动）。
         *
         * <p>顺序刻意保持上游原样——上游<b>不按涨跌幅排序</b>
         * （这里 1.95 / 0.79 / 0.6 / -0.11 恰好是降序，但真实响应里
         * 首条可能是 -1.65，见 {@code TencentMarketDataProvider#SECTOR_FETCH_COUNT}），
         * 所以「排序」这条断言检验的是本类的本地排序，而不是上游的顺序。
         */
        private static final String SAMPLE = """
                {
                  "code": 0,
                  "msg": "ok",
                  "data": {
                    "offset": 0,
                    "total": 31,
                    "rank_list": [
                      {"code":"pt01801010","name":"农林牧渔","zdf":"1.95","zgb":"73/103",
                       "lzg":{"code":"sh002086","name":"东方海洋","zdf":"10.14"}},
                      {"code":"pt01801150","name":"医药生物","zdf":"-0.11","zgb":"184/480",
                       "lzg":{"code":"sz300006","name":"莱美药业","zdf":"20.00"}},
                      {"code":"pt01801080","name":"电子","zdf":"0.79","zgb":"237/497",
                       "lzg":{"code":"sz300296","name":"利亚德","zdf":"20.04"}},
                      {"code":"pt01801770","name":"通信","zdf":"0.60"}
                    ]
                  }
                }
                """;

        @Test
        @DisplayName("按涨跌幅降序排列：上游顺序不可信，排序必须自己做")
        void sortsByChangePctDescending() throws Exception {
            List<com.stocklab.analyst.market.model.Sector> sectors =
                    TencentMarketDataProvider.parseSectorRank(MAPPER.readTree(SAMPLE));

            assertThat(sectors).extracting(com.stocklab.analyst.market.model.Sector::name)
                    .containsExactly("农林牧渔", "电子", "通信", "医药生物");
        }

        @Test
        @DisplayName("涨跌家数从 \"73/103\" 解析为两个整数")
        void parsesBreadth() throws Exception {
            List<com.stocklab.analyst.market.model.Sector> sectors =
                    TencentMarketDataProvider.parseSectorRank(MAPPER.readTree(SAMPLE));

            assertThat(sectors.get(0).upCount()).isEqualTo(73);
            assertThat(sectors.get(0).downCount()).isEqualTo(103);
        }

        @Test
        @DisplayName("领涨股代码去掉市场前缀，只留 6 位")
        void stripsLeaderMarketPrefix() throws Exception {
            List<com.stocklab.analyst.market.model.Sector> sectors =
                    TencentMarketDataProvider.parseSectorRank(MAPPER.readTree(SAMPLE));

            assertThat(sectors.get(0).leaderName()).isEqualTo("东方海洋");
            assertThat(sectors.get(0).leaderCode()).isEqualTo("002086");
            assertThat(sectors.get(0).leaderPct()).isEqualTo(10.14);
        }

        @Test
        @DisplayName("家数与领涨股缺失时给 null，不用 0 兜底")
        void missingBreadthAndLeaderBecomeNull() throws Exception {
            List<com.stocklab.analyst.market.model.Sector> sectors =
                    TencentMarketDataProvider.parseSectorRank(MAPPER.readTree(SAMPLE));

            com.stocklab.analyst.market.model.Sector telecom = sectors.get(2);
            assertThat(telecom.upCount()).isNull();
            assertThat(telecom.downCount()).isNull();
            assertThat(telecom.leaderName()).isNull();
            assertThat(telecom.leaderCode()).isNull();
            assertThat(telecom.leaderPct()).isNull();
        }

        @Test
        @DisplayName("缺涨跌幅的条目直接丢弃：放进涨幅榜会被读成「平盘」")
        void dropsEntriesWithoutChangePct() throws Exception {
            String withDash = """
                    {"code":0,"data":{"rank_list":[
                      {"code":"pt1","name":"板块甲","zdf":"-"},
                      {"code":"pt2","name":"板块乙","zdf":"1.20","zgb":"-/-"}
                    ]}}
                    """;

            List<com.stocklab.analyst.market.model.Sector> sectors =
                    TencentMarketDataProvider.parseSectorRank(MAPPER.readTree(withDash));

            assertThat(sectors).hasSize(1);
            assertThat(sectors.get(0).name()).isEqualTo("板块乙");
            // "−/−" 这类占位符同样不能变成 0：0/0 会被渲染成一个正常但无意义的比例
            assertThat(sectors.get(0).upCount()).isNull();
            assertThat(sectors.get(0).downCount()).isNull();
        }

        @Test
        @DisplayName("上游拒绝服务（data 为 null）时返回空列表，由调用方决定含义")
        void returnsEmptyOnRejectedEnvelope() throws Exception {
            String rejected = """
                    {"code":1619377101,"msg":"param error","data":null}
                    """;

            assertThat(TencentMarketDataProvider.parseSectorRank(MAPPER.readTree(rejected)))
                    .isEmpty();
        }
    }
}
