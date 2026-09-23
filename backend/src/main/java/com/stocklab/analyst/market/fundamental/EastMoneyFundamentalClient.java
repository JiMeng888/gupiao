package com.stocklab.analyst.market.fundamental;

import com.stocklab.analyst.market.MarketDataUnavailableException;
import com.stocklab.analyst.market.StockCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 财务数据客户端。
 *
 * <p>两处数据来源：
 * <ol>
 *   <li><b>实时财务指标</b>：复用行情接口的扩展字段，一次请求拿到
 *       市盈率 / 市净率 / ROE / 毛利率 / 净利率 / 资产负债率 / 营收与净利同比</li>
 *   <li><b>历史业绩报表</b>：东方财富数据中心接口，返回逐期真实披露数据</li>
 * </ol>
 *
 * <p><b>相对原 FastAPI 版本的重要修正</b>：原实现的财务数据是用股票代码
 * 哈希出来的伪随机值（{@code h = sum(ord(c) for c in code)} 再套公式），
 * 「贵州茅台的 ROE 是 15%~35% 之间的某个数」——看着像真的，实际与公司无关。
 * 本实现改为拉取真实披露数据，并在无法获取时才回退到标注清楚的
 * 估算值（见 {@code FundamentalService}）。
 * 财务数据被当成真实信息用于投资判断，编造它的风险远高于展示一个错误提示。
 */
@Component
public class EastMoneyFundamentalClient {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyFundamentalClient.class);

    private static final String HOST_PUSH2 = "https://push2.eastmoney.com";
    private static final String HOST_DATACENTER = "https://datacenter-web.eastmoney.com";

    /** 扩展字段：105 净利润 / 162 市盈率(动) / 163 市盈率(静) / 164 市盈率(TTM) /
     *  167 市净率 / 173 ROE / 183 营业收入 / 184 营收同比 / 185 净利同比 /
     *  186 毛利率 / 187 净利率 / 188 资产负债率 / 190 每股净资产 /
     *  191 每股收益 / 292 所属行业 */
    private static final String FUNDAMENTAL_FIELDS =
            "f57,f58,f105,f162,f163,f164,f167,f173,f183,f184,f185,f186,f187,f188,f190,f191";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public EastMoneyFundamentalClient(@Qualifier("marketRestClient") RestClient restClient,
                                      ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询实时财务指标。
     *
     * @return 指标存在时返回，标的无财务数据时返回 {@link Optional#empty()}
     * @throws MarketDataUnavailableException 上游不可用
     */
    public Optional<RawFundamental> fetchFundamental(String code) {
        StockCodes.ParsedCode parsed = StockCodes.parse(code);
        if (parsed == null) {
            return Optional.empty();
        }

        String url = HOST_PUSH2 + "/api/qt/stock/get"
                + "?secid=" + parsed.secId()
                + "&fields=" + FUNDAMENTAL_FIELDS
                + "&invt=2&fltt=2";

        JsonNode root = fetchJson(url, "财务指标 " + code);
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return Optional.empty();
        }

        RawFundamental fundamental = new RawFundamental(
                text(data, "f57", parsed.code()),
                text(data, "f58", parsed.code()),
                null,
                nullableDouble(data, "f162"),
                nullableDouble(data, "f163"),
                nullableDouble(data, "f164"),
                nullableDouble(data, "f167"),
                nullableDouble(data, "f173"),
                nullableDouble(data, "f186"),
                nullableDouble(data, "f187"),
                nullableDouble(data, "f188"),
                nullableDouble(data, "f183"),
                nullableDouble(data, "f105"),
                nullableDouble(data, "f184"),
                nullableDouble(data, "f185"),
                nullableDouble(data, "f190"),
                nullableDouble(data, "f191"));

        // 所有指标都缺失，说明上游没有这只标的的财务数据
        if (fundamental.peTtm() == null && fundamental.roe() == null && fundamental.revenue() == null) {
            return Optional.empty();
        }
        return Optional.of(fundamental);
    }

    /**
     * 查询历史业绩报表。
     *
     * @param periods 返回的期数（按报告期倒序取最近 N 期）
     */
    public List<RawFinancialPeriod> fetchFinancialPeriods(String code, int periods) {
        StockCodes.ParsedCode parsed = StockCodes.parse(code);
        if (parsed == null) {
            return List.of();
        }
        String filter = URLEncoder.encode("(SECURITY_CODE=\"" + parsed.code() + "\")", StandardCharsets.UTF_8);
        String url = HOST_DATACENTER + "/api/data/v1/get"
                + "?reportName=RPT_LICO_FN_CPD"
                + "&columns=SECURITY_CODE,SECURITY_NAME_ABBR,REPORTDATE,QDATE,DATATYPE,"
                + "TOTAL_OPERATE_INCOME,PARENT_NETPROFIT,WEIGHTAVG_ROE,YSTZ,SJLTZ,"
                + "XSMLL,BPS,BASIC_EPS,MGJYXJJE,ASSIGNDSCRPT,BOARD_CODE,BOARD_NAME"
                + "&filter=" + filter
                + "&pageSize=" + Math.min(Math.max(periods, 1), 40)
                + "&pageNumber=1"
                + "&sortColumns=REPORTDATE&sortTypes=-1";

        JsonNode root = fetchJson(url, "历史业绩 " + code);
        JsonNode data = root.path("result").path("data");
        if (!data.isArray() || data.isEmpty()) {
            // 数据中心接口对不存在 / 未披露的标的返回 result=null，
            // 属于「查不到」而非「服务故障」，返回空列表即可
            return List.of();
        }

        List<RawFinancialPeriod> result = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            result.add(new RawFinancialPeriod(
                    truncateDate(text(item, "REPORTDATE", "")),
                    text(item, "DATATYPE", ""),
                    text(item, "QDATE", ""),
                    nullableDouble(item, "TOTAL_OPERATE_INCOME"),
                    nullableDouble(item, "PARENT_NETPROFIT"),
                    nullableDouble(item, "WEIGHTAVG_ROE"),
                    nullableDouble(item, "YSTZ"),
                    nullableDouble(item, "SJLTZ"),
                    nullableDouble(item, "XSMLL"),
                    nullableDouble(item, "BPS"),
                    nullableDouble(item, "BASIC_EPS"),
                    nullableDouble(item, "MGJYXJJE"),
                    text(item, "ASSIGNDSCRPT", null),
                    text(item, "BOARD_CODE", null),
                    text(item, "BOARD_NAME", null)));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 查询行业板块估值（市盈率）。
     *
     * <p>板块用 {@code 90.BKxxxx} 作为 secid。注意板块接口的市净率字段
     * 常返回字符串 {@code "-"}（不适用），因此这里只取市盈率，
     * 且读取时按「非数字即缺失」处理。
     *
     * @param boardCode 板块代码，如 {@code BK1277}
     * @return 行业名称与市盈率；无数据时返回空
     */
    public Optional<BoardValuation> fetchBoardValuation(String boardCode) {
        if (boardCode == null || boardCode.isBlank()) {
            return Optional.empty();
        }
        String url = HOST_PUSH2 + "/api/qt/stock/get"
                + "?secid=90." + boardCode
                + "&fields=f57,f58,f162,f167&invt=2&fltt=2";

        try {
            JsonNode data = fetchJson(url, "行业板块 " + boardCode).path("data");
            if (data.isMissingNode() || data.isNull()) {
                return Optional.empty();
            }
            return Optional.of(new BoardValuation(
                    text(data, "f58", boardCode),
                    nullableDouble(data, "f162")));
        } catch (MarketDataUnavailableException ex) {
            // 行业估值是「锦上添花」的数据，取不到不应让整个基本面接口失败
            log.debug("行业板块估值获取失败 boardCode={}：{}", boardCode, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 行业板块估值。
     *
     * @param name 行业名称
     * @param pe   行业市盈率
     */
    public record BoardValuation(String name, Double pe) {
    }

    // ------------------------------------------------------------------

    private JsonNode fetchJson(String url, String action) {
        String body;
        try {
            body = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
        } catch (RuntimeException ex) {
            throw new MarketDataUnavailableException("财务数据请求失败（" + action + "）", ex);
        }
        if (body == null || body.isBlank()) {
            throw new MarketDataUnavailableException("财务数据返回空响应（" + action + "）");
        }
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException ex) {
            throw new MarketDataUnavailableException("财务数据响应不是合法 JSON（" + action + "）", ex);
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isString()) {
            String raw = value.asString();
            return raw.isBlank() ? fallback : raw;
        }
        return fallback;
    }

    private static Double nullableDouble(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isString()) {
            try {
                return Double.parseDouble(value.asString().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /** {@code 2026-06-30 00:00:00} → {@code 2026-06-30}。 */
    private static String truncateDate(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() > 10 ? raw.substring(0, 10) : raw;
    }
}
