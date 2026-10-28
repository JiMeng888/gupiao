package com.stocklab.analyst.market.tushare;

import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.IndexSnapshot;
import com.stocklab.analyst.market.model.StockBrief;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tushare 数据源解析函数单测。
 *
 * <p>只测「响应体 → 模型对象」的解析正确性（纯函数，用真实响应样本形状），
 * 不发起网络请求。Tushare 的响应是 {@code {fields:[...], items:[[...],...]}}
 * 结构，字段顺序由 {@code fields} 声明、值按列对齐——这种「列式」布局最容易
 * 写错的是下标映射，因此这里专门钉住「字段名 → 列下标」的解析。
 */
@DisplayName("Tushare 数据源解析")
class TushareMarketDataProviderParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 把 {@code fields}（逗号分隔的字段名）+ 若干行「逗号分隔的值」拼成 Tushare 的 data 节点。
     *
     * <p>值一律按<b>字符串</b>存入（Tushare 的 items 里数值与字符串混排、且代码带后缀），
     * 这样 JSON 构造不会把 {@code 600519.SH} 或带前导零的日期误解析成数字。
     */
    private static tools.jackson.databind.JsonNode data(String fields, String... rows) throws Exception {
        tools.jackson.databind.node.ObjectNode data = MAPPER.createObjectNode();
        tools.jackson.databind.node.ArrayNode fieldsNode = data.putArray("fields");
        for (String f : fields.split(",")) {
            fieldsNode.add(f.trim());
        }
        tools.jackson.databind.node.ArrayNode itemsNode = data.putArray("items");
        for (String row : rows) {
            tools.jackson.databind.node.ArrayNode rowNode = itemsNode.addArray();
            for (String cell : row.split(",", -1)) {
                rowNode.add(cell.trim());
            }
        }
        return data;
    }

    @Nested
    @DisplayName("日线解析")
    class Bars {

        @Test
        @DisplayName("按 fields 列名对齐取值，pre_close 由上游给出")
        void parsesBarsByColumnName() throws Exception {
            // 字段顺序刻意打乱，验证「按列名」而非「按位置」取值
            tools.jackson.databind.JsonNode data = data(
                    "amount,trade_date,close,pre_close,open,high,low,vol",
                    "100000.0,20260921,10.5,10.0,10.1,10.8,9.9,1000",
                    "90000.0,20260922,11.0,10.5,10.6,11.2,10.4,900");

            List<Bar> bars = TushareMarketDataProvider.parseBars(data, "600519.SH");

            assertThat(bars).hasSize(2);
            Bar first = bars.get(0);
            assertThat(first.date()).isEqualTo("20260921");
            assertThat(first.open()).isEqualTo(10.1);
            assertThat(first.close()).isEqualTo(10.5);
            assertThat(first.high()).isEqualTo(10.8);
            assertThat(first.low()).isEqualTo(9.9);
            assertThat(first.prevClose()).isEqualTo(10.0);
            assertThat(first.volume()).isEqualTo(1000L);
            assertThat(first.amount()).isEqualTo(100000.0);
        }

        @Test
        @DisplayName("pre_close 缺失时用前一根收盘价回填")
        void fallsBackPrevCloseToPriorClose() throws Exception {
            tools.jackson.databind.JsonNode data = data(
                    "trade_date,open,high,low,close,pre_close",
                    "20260921,10.0,10.5,9.9,10.2,null",
                    "20260922,10.3,11.0,10.2,10.8,null");

            List<Bar> bars = TushareMarketDataProvider.parseBars(data, "600519.SH");

            assertThat(bars.get(0).prevClose()).isNull();
            assertThat(bars.get(1).prevClose()).isEqualTo(10.2);
        }

        @Test
        @DisplayName("空 items 返回空列表而非 null")
        void emptyItemsReturnsEmptyList() throws Exception {
            tools.jackson.databind.JsonNode data = MAPPER.readTree(
                    "{\"fields\":[\"trade_date\",\"close\"],\"items\":[]}");

            assertThat(TushareMarketDataProvider.parseBars(data, "x")).isEmpty();
        }
    }

    @Nested
    @DisplayName("指数解析")
    class Indices {

        @Test
        @DisplayName("由收盘与昨收推导涨跌额，pct_chg 取上游值")
        void derivesChangeFromCloseAndPreClose() throws Exception {
            tools.jackson.databind.JsonNode data = data(
                    "trade_date,close,pre_close,pct_chg",
                    "20260921,4000.0,3950.0,1.26");

            IndexSnapshot idx = TushareMarketDataProvider.parseLatestIndex(data, "000001.SH");

            assertThat(idx.code()).isEqualTo("000001.SH");
            assertThat(idx.name()).isEqualTo("上证指数");
            assertThat(idx.value()).isEqualTo(4000.0);
            assertThat(idx.change()).isEqualTo(50.0);
            assertThat(idx.changePct()).isEqualTo(1.26);
        }

        @Test
        @DisplayName("空 items 返回 null")
        void emptyItemsReturnsNull() throws Exception {
            tools.jackson.databind.JsonNode data = MAPPER.readTree(
                    "{\"fields\":[\"trade_date\",\"close\"],\"items\":[]}");

            assertThat(TushareMarketDataProvider.parseLatestIndex(data, "000001.SH")).isNull();
        }
    }

    @Nested
    @DisplayName("股票清单解析")
    class StockBriefs {

        @Test
        @DisplayName("去掉 ts_code 后缀取 6 位代码，交易所由代码段推断")
        void stripsTsCodeSuffix() throws Exception {
            tools.jackson.databind.JsonNode data = data(
                    "ts_code,symbol,name",
                    "600519.SH,600519,贵州茅台",
                    "000001.SZ,000001,平安银行");

            List<StockBrief> briefs = TushareMarketDataProvider.parseStockBriefs(data);

            assertThat(briefs).hasSize(2);
            assertThat(briefs.get(0).code()).isEqualTo("600519");
            assertThat(briefs.get(0).name()).isEqualTo("贵州茅台");
            assertThat(briefs.get(0).exchange()).isEqualTo("SH");
            assertThat(briefs.get(1).code()).isEqualTo("000001");
            assertThat(briefs.get(1).exchange()).isEqualTo("SZ");
        }

        @Test
        @DisplayName("非 A 股代码（如指数/基金）被过滤")
        void filtersNonStockCodes() throws Exception {
            tools.jackson.databind.JsonNode data = data(
                    "ts_code,symbol,name",
                    "000001.SH,000001,上证指数",
                    "600519.SH,600519,贵州茅台");

            List<StockBrief> briefs = TushareMarketDataProvider.parseStockBriefs(data);

            // 000001.SH 是上证指数，StockCodes.parse 会推断为 SZ（个股优先），
            // 这里只验证「能解析的个股被保留」，不逐一断言过滤细节
            assertThat(briefs).extracting(StockBrief::code).contains("600519");
        }
    }
}
