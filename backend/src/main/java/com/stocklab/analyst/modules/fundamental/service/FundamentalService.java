package com.stocklab.analyst.modules.fundamental.service;

import com.stocklab.analyst.common.constant.CacheNames;
import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataUnavailableException;
import com.stocklab.analyst.market.fundamental.EastMoneyFundamentalClient;
import com.stocklab.analyst.market.fundamental.RawFinancialPeriod;
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

/**
 * 基本面服务：财务指标概览、杜邦分析、业绩报表、同行对比。
 *
 * <h2>分层：本类只做编排，取数在 {@link FundamentalOverviewProvider}</h2>
 * 概览的取数与缓存已下沉到 {@code FundamentalOverviewProvider}。
 * 这一点不只是整理代码结构，而是<b>修掉了一个静默失效的缓存</b>：
 * 原先 {@code getOverview} 是本类的 {@code @Cacheable} 方法，
 * 而本类的杜邦分析与同行对比在内部直接调用它——
 * <b>同类内部调用不经过 Spring 的 AOP 代理，缓存注解直接无效</b>。
 * 后果是 {@code /fundamental/compare} 一次请求最多放大成 12~18 次上游调用，
 * 既慢又加剧东财的 IP 级风控。
 *
 * <p>现在三个入口（外部接口、杜邦、同行对比）走的是同一个代理，缓存无条件生效。
 * 由此得到一个约定：<b>本类不要再出现「自己调自己」的取数方法</b>——
 * 需要复用取数逻辑，就把它放进 {@code FundamentalOverviewProvider}。
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

    private final FundamentalOverviewProvider overviewProvider;
    private final EastMoneyFundamentalClient fundamentalClient;

    public FundamentalService(FundamentalOverviewProvider overviewProvider,
                              EastMoneyFundamentalClient fundamentalClient) {
        this.overviewProvider = overviewProvider;
        this.fundamentalClient = fundamentalClient;
    }

    // ==================================================================
    // 概览
    // ==================================================================

    /**
     * 财务指标概览。
     *
     * <p><b>刻意不加 {@code @Cacheable}</b>：缓存属于取数层，由
     * {@link FundamentalOverviewProvider#load} 承担。这里再加一层只会多一份
     * key 与 TTL 要维护，而且会掩盖「内部调用绕过缓存」的问题——
     * 外层有缓存时接口看起来正常，只有同行对比这类内部调用方在偷偷放大请求量。
     */
    public FundamentalOverview getOverview(String code) {
        return overviewProvider.load(code);
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
        FundamentalOverview overview = overviewProvider.load(code);

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
        String name = overviewProvider.resolveName(code);
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
                views.isEmpty() ? "unavailable" : FundamentalOverviewProvider.SOURCE_REAL, views, notice);
    }

    // ==================================================================
    // 同行对比
    // ==================================================================

    /**
     * 同行对比。
     *
     * <p>上限 6 只：每个标的都要打上游接口，不做限制时一次请求
     * 传 50 个代码会变成 150 次上游调用，既慢又容易被限流。
     *
     * <p>这里的每一只都走 {@link FundamentalOverviewProvider#load}，
     * 也就是都经过缓存代理——重复对比同一组标的（刷新页面、切回来）不会重复打上游。
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
            result.add(overviewProvider.load(code));
        }
        return result;
    }

    // ==================================================================

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
