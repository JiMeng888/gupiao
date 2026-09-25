package com.stocklab.analyst.modules.fundamental.service;

import com.stocklab.analyst.common.constant.CacheNames;
import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.MarketDataUnavailableException;
import com.stocklab.analyst.market.fundamental.EastMoneyFundamentalClient;
import com.stocklab.analyst.market.fundamental.RawFinancialPeriod;
import com.stocklab.analyst.market.fundamental.RawFundamental;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.fundamental.dto.DupontAnalysis;
import com.stocklab.analyst.modules.fundamental.dto.FinancialPeriodView;
import com.stocklab.analyst.modules.fundamental.dto.FundamentalOverview;
import com.stocklab.analyst.modules.fundamental.dto.StatementsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基本面服务：财务指标概览、杜邦分析、业绩报表、同行对比。
 *
 * <h2>数据真实性约定</h2>
 * 财务数据与行情不同——行情错了用户一眼能看出，财务数据错了用户会当真。
 * 因此本服务遵循两条硬规则：
 * <ol>
 *   <li><b>能取真实数据就绝不用估算值。</b>真实数据来自东方财富<b>内部数据接口</b>
 *       （未获数据方授权，详见 {@code docs/数据来源与授权状态说明-20260924.md}）。</li>
 *   <li><b>使用估算值时必须在响应里标注。</b>{@code dataSource=estimated} 且
 *       {@code notice} 给出明确提示，前端必须显式展示该标识。</li>
 * </ol>
 *
 * <h2>与业绩报表的差异处理</h2>
 * 概览与杜邦分析保留了估算值兜底（因为它们是页面首屏，离线演示需要内容），
 * 但<b>业绩报表不兜底</b>——返回空列表而不是编造多期历史。
 * 编造一个季度的 ROE 与编造「连续 12 个季度的营收增长曲线」，
 * 后者的误导性高一个量级，因此不做。
 */
@Service
public class FundamentalService {

    private static final Logger log = LoggerFactory.getLogger(FundamentalService.class);

    private static final String SOURCE_REAL = "eastmoney";
    private static final String SOURCE_ESTIMATED = "estimated";

    /**
     * 真实数据时的来源标注。措辞刻意区分两件事：
     * <ul>
     *   <li><b>内容层面</b>：财报是上市公司强制公开披露的信息，本身是公开的；</li>
     *   <li><b>渠道层面</b>：我们是调东方财富给自家网页用的内部接口拿到的，未获授权。</li>
     * </ul>
     * 以前这里写的是「东方财富公开披露接口」，把「内容公开」和「接口获准」混成了一件事。
     */
    private static final String REAL_NOTICE =
            "数据来源：东方财富内部数据接口（未获数据方授权）；财报内容为上市公司公开披露信息，"
                    + "仅作研究参考，不构成投资建议";
    private static final String ESTIMATED_NOTICE =
            "当前为估算数据（未连接真实财务数据源），与实际财务状况无关，请勿据此决策";

    private final EastMoneyFundamentalClient fundamentalClient;
    private final MarketDataProvider marketDataProvider;

    public FundamentalService(EastMoneyFundamentalClient fundamentalClient,
                              MarketDataProvider marketDataProvider) {
        this.fundamentalClient = fundamentalClient;
        this.marketDataProvider = marketDataProvider;
    }

    // ==================================================================
    // 概览
    // ==================================================================

    /**
     * 财务指标概览。
     *
     * <p>缓存 6 小时：财务数据按季度披露，日间不变；
     * 而一次概览要打 2~3 个上游接口，不缓存会让每次页面访问都产生数倍延迟。
     */
    @Cacheable(cacheNames = CacheNames.FUNDAMENTAL, key = "'overview:' + #code", sync = true)
    public FundamentalOverview getOverview(String code) {
        String name = resolveName(code);

        try {
            Optional<RawFundamental> raw = fundamentalClient.fetchFundamental(code);
            if (raw.isPresent()) {
                return buildFromReal(code, name, raw.get());
            }
            log.debug("上游无 {} 的财务数据，使用估算值", code);
        } catch (MarketDataUnavailableException ex) {
            log.warn("财务数据源不可用，{} 使用估算值：{}", code, ex.getMessage());
        }
        return buildEstimated(code, name);
    }

    private FundamentalOverview buildFromReal(String code, String fallbackName, RawFundamental raw) {
        String name = raw.name() != null && !raw.name().isBlank() ? raw.name() : fallbackName;

        // 行业名称与板块代码需要额外一次查询；取不到不影响主体指标
        String industry = null;
        Double industryPe = null;
        try {
            List<RawFinancialPeriod> periods = fundamentalClient.fetchFinancialPeriods(code, 1);
            if (!periods.isEmpty()) {
                RawFinancialPeriod latest = periods.get(0);
                industry = latest.boardName();
                Optional<EastMoneyFundamentalClient.BoardValuation> valuation =
                        fundamentalClient.fetchBoardValuation(latest.boardCode());
                if (valuation.isPresent()) {
                    industryPe = valuation.get().pe();
                    if (industry == null || industry.isBlank()) {
                        industry = valuation.get().name();
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.debug("行业估值补充信息获取失败 code={}：{}", code, ex.getMessage());
        }

        Double pe = firstNonNull(raw.peTtm(), raw.peDynamic(), raw.peStatic());
        Double premium = (pe != null && industryPe != null && industryPe > 0)
                ? round2((pe - industryPe) / industryPe * 100)
                : null;

        return new FundamentalOverview(
                code,
                name,
                industry,
                raw.peDynamic(),
                raw.peStatic(),
                raw.peTtm(),
                raw.pb(),
                raw.roe(),
                raw.grossMargin(),
                raw.grossMarginApplicable(),
                raw.netMargin(),
                raw.debtRatio(),
                raw.revenue(),
                raw.netProfit(),
                raw.revenueYoy(),
                raw.netProfitYoy(),
                raw.bps(),
                raw.eps(),
                industryPe,
                premium,
                SOURCE_REAL,
                REAL_NOTICE);
    }

    /**
     * 估算值兜底。
     *
     * <p>使用代码的稳定哈希生成「看起来合理」的数值，仅用于离线演示。
     * 相比原 FastAPI 版本有一处修正：Python 的 {@code hash()} 对字符串
     * 默认启用随机化（PYTHONHASHSEED），导致同一只股票的「财务数据」
     * 每次重启都不同；这里用 Java 的 {@code String.hashCode()}
     * （由语言规范保证稳定）作为种子，结果可复现。
     *
     * <p>散列取模用 {@link Math#floorMod} 而不是 {@code %}：
     * Java 的 {@code %} 对负数返回负值，会让「ROE 出现负数」这种
     * 与预期完全不符的结果。
     */
    private FundamentalOverview buildEstimated(String code, String name) {
        int h = code.hashCode();
        double pe = round2(20 + Math.floorMod(h, 150) / 10.0);
        double pb = round2(2 + Math.floorMod(h, 70) / 10.0);
        double roe = round2(15 + Math.floorMod(h, 200) / 10.0);
        double grossMargin = round2(25 + Math.floorMod(h, 450) / 10.0);
        double netMargin = round2(8 + Math.floorMod(h, 250) / 10.0);
        double debtRatio = round2(20 + Math.floorMod(h, 400) / 10.0);

        return new FundamentalOverview(
                code, name, null,
                pe, pe, pe, pb, roe,
                grossMargin, true,
                netMargin, debtRatio,
                null, null, null, null,
                null, null, null, null,
                SOURCE_ESTIMATED, ESTIMATED_NOTICE);
    }

    // ==================================================================
    // 杜邦分析
    // ==================================================================

    /**
     * 杜邦分析。
     *
     * <p>计算说明见 {@link DupontAnalysis}：权益乘数由资产负债率推导，
     * 总资产周转率反推，保证 {@code ROE = 净利率 × 周转率 × 权益乘数} 精确闭合。
     */
    @Cacheable(cacheNames = CacheNames.FUNDAMENTAL, key = "'dupont:' + #code", sync = true)
    public DupontAnalysis getDupont(String code) {
        FundamentalOverview overview = getOverview(code);

        Double roe = overview.roe();
        Double netMargin = overview.netMargin();
        Double debtRatio = overview.debtRatio();

        if (roe == null || netMargin == null || netMargin == 0 || debtRatio == null) {
            return new DupontAnalysis(code, overview.name(), roe, netMargin, null, null,
                    "财务数据不完整，无法完成杜邦拆解", overview.dataSource());
        }
        if (debtRatio >= 100 || debtRatio < 0) {
            // 资不抵债或数据异常：权益乘数在数学上无意义（除零或负数）
            return new DupontAnalysis(code, overview.name(), roe, netMargin, null, null,
                    "资产负债率数据异常，无法计算权益乘数", overview.dataSource());
        }

        double equityMultiplier = 1.0 / (1.0 - debtRatio / 100.0);
        // 反推周转率，使恒等式精确成立（详见 DupontAnalysis 的说明）
        double assetTurnover = roe / (netMargin * equityMultiplier);

        return new DupontAnalysis(
                code,
                overview.name(),
                round2(roe),
                round2(netMargin),
                round4(assetTurnover),
                round4(equityMultiplier),
                interpret(roe, netMargin, assetTurnover, equityMultiplier),
                overview.dataSource());
    }

    /** 判断 ROE 主要由哪个因子驱动。 */
    private String interpret(double roe, double netMargin, double turnover, double multiplier) {
        if (multiplier >= 3.0) {
            return String.format("ROE %.2f%% 主要由高杠杆驱动（权益乘数 %.2f），盈利质量受负债水平影响较大",
                    roe, multiplier);
        }
        if (netMargin >= 25.0) {
            return String.format("ROE %.2f%% 主要由高净利率驱动（净利率 %.2f%%），属于高毛利商业模式",
                    roe, netMargin);
        }
        if (turnover >= 1.0) {
            return String.format("ROE %.2f%% 主要由高资产周转驱动（周转率 %.2f），属于薄利多销模式",
                    roe, turnover);
        }
        return String.format("ROE %.2f%% 由净利率 %.2f%%、周转率 %.2f、权益乘数 %.2f 共同驱动，各项相对均衡",
                roe, netMargin, turnover, multiplier);
    }

    // ==================================================================
    // 业绩报表
    // ==================================================================

    /**
     * 逐期业绩报表（真实披露数据）。
     *
     * <p>取不到数据时返回空列表，<b>不编造</b>——理由见类注释。
     */
    public StatementsResponse getStatements(String code, int periods) {
        String name = resolveName(code);
        String industry = null;
        List<FinancialPeriodView> views = new ArrayList<>();

        try {
            List<RawFinancialPeriod> raw = fundamentalClient.fetchFinancialPeriods(code, periods);
            for (RawFinancialPeriod item : raw) {
                if (industry == null && item.boardName() != null) {
                    industry = item.boardName();
                }
                views.add(new FinancialPeriodView(
                        item.reportDate(),
                        item.periodLabel(),
                        item.quarter(),
                        item.revenue(),
                        item.netProfit(),
                        item.roe(),
                        item.revenueYoy(),
                        item.netProfitYoy(),
                        item.grossMargin(),
                        item.bps(),
                        item.eps(),
                        item.cashflowPerShare(),
                        item.dividendPlan()));
            }
        } catch (MarketDataUnavailableException ex) {
            log.warn("业绩报表数据源不可用 code={}：{}", code, ex.getMessage());
        }

        String notice = views.isEmpty()
                ? "暂未获取到该标的的业绩披露数据（可能为新上市、停牌或数据源不可用）"
                : "数据来源：东方财富内部数据接口；财报为上市公司公开披露信息。"
                        + "A 股财报为年内累计值，趋势比较请使用同一报告期口径";

        return new StatementsResponse(code, name, industry,
                views.isEmpty() ? "unavailable" : SOURCE_REAL, views, notice);
    }

    // ==================================================================
    // 同行对比
    // ==================================================================

    /**
     * 同行对比。
     *
     * <p>上限 6 只：每个标的都要打上游接口，不做限制时一次请求
     * 传 50 个代码会变成 150 次上游调用，既慢又容易被限流。
     */
    public List<FundamentalOverview> compare(String codes) {
        if (codes == null || codes.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAMS, "codes 不能为空");
        }
        List<String> codeList = new ArrayList<>();
        for (String token : codes.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                codeList.add(trimmed);
            }
        }
        if (codeList.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAMS, "codes 不能为空");
        }
        if (codeList.size() > 6) {
            throw new BizException(ErrorCode.INVALID_PARAMS, "同行对比最多支持 6 只股票");
        }

        List<FundamentalOverview> result = new ArrayList<>(codeList.size());
        for (String code : codeList) {
            result.add(getOverview(code));
        }
        return result;
    }

    // ==================================================================

    /** 名称优先从行情源取；失败则退回代码本身，保证接口不因取名失败而报错。 */
    private String resolveName(String code) {
        try {
            Quote quote = marketDataProvider.getQuote(code);
            return quote.name();
        } catch (RuntimeException ex) {
            return code;
        }
    }

    private static Double firstNonNull(Double... values) {
        for (Double value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
