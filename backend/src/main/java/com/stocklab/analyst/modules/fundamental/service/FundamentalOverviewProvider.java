package com.stocklab.analyst.modules.fundamental.service;

import com.stocklab.analyst.common.constant.CacheNames;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.MarketDataUnavailableException;
import com.stocklab.analyst.market.fundamental.EastMoneyFundamentalClient;
import com.stocklab.analyst.market.fundamental.RawFinancialPeriod;
import com.stocklab.analyst.market.fundamental.RawFundamental;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.fundamental.dto.FundamentalOverview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 财务概览的<b>取数层</b>——真正打上游、真正被缓存的那一层。
 *
 * <h2>为什么把 {@code getOverview} 从 FundamentalService 里抽出来（重要）</h2>
 * 曾经这个方法是 {@code FundamentalService} 的一个 {@code @Cacheable} 方法，
 * 但同类的另外两个方法在内部直接 {@code getOverview(code)} 调用它：
 * 杜邦分析（1 次）与同行对比（最多 6 次）。Spring 的缓存注解建立在 AOP 代理上，
 * <b>同类内部调用不经过代理</b>，于是注解静默失效——
 * {@code GET /fundamental/compare} 一次请求放大成 <b>12~18 次上游调用</b>
 * （每只标的要打「概览 + 行业 + 板块估值」三个接口），
 * 而那个接口的注释恰恰写着「上限是为了避免单次请求放大成数十次上游调用」。
 * 换句话说：注释声称的防护被自调用抵消了，而链路上没有任何报错、日志或指标会提示这件事。
 *
 * <p>抽成独立 Bean 后，所有调用方（外部接口、杜邦、同行对比）走的都是<b>同一个代理</b>，
 * 缓存无条件生效。这不只是修一次 bug——把「取数」与「业务编排」分开之后，
 * 「在编排层里再写一个同类内部调用」这个写法本身就没有了存在的理由。
 *
 * <h2>数据真实性约定</h2>
 * 财务数据与行情不同——行情错了用户一眼能看出，财务数据错了用户会当真。
 * 因此这里遵循两条硬规则：
 * <ol>
 *   <li><b>能取真实数据就绝不用估算值。</b>真实数据来自东方财富<b>内部数据接口</b>
 *       （未获数据方授权，详见 {@code docs/数据来源与授权状态说明-20260924.md}）。</li>
 *   <li><b>使用估算值时必须在响应里标注。</b>{@code dataSource=estimated} 且
 *       {@code notice} 给出明确提示，前端必须显式展示该标识。</li>
 * </ol>
 *
 * <p>注意概览保留估算值兜底（它是页面首屏，离线演示需要内容），
 * 但业绩报表不兜底——参见 {@link FundamentalService#getStatements}。
 */
@Component
public class FundamentalOverviewProvider {

    private static final Logger log = LoggerFactory.getLogger(FundamentalOverviewProvider.class);

    static final String SOURCE_REAL = "eastmoney";
    static final String SOURCE_ESTIMATED = "estimated";

    /**
     * 真实数据时的来源标注。措辞刻意区分两件事：
     * <ul>
     *   <li><b>内容层面</b>：财报是上市公司强制公开披露的信息，本身是公开的；</li>
     *   <li><b>渠道层面</b>：我们是调东方财富给自家网页用的内部接口拿到的，未获授权。</li>
     * </ul>
     * 以前这里写的是「东方财富公开披露接口」，把「内容公开」和「接口获准」混成了一件事。
     */
    static final String REAL_NOTICE =
            "数据来源：东方财富内部数据接口（未获数据方授权）；财报内容为上市公司公开披露信息，"
                    + "仅作研究参考，不构成投资建议";

    static final String ESTIMATED_NOTICE =
            "当前为估算数据（未连接真实财务数据源），与实际财务状况无关，请勿据此决策";

    private final EastMoneyFundamentalClient fundamentalClient;
    private final MarketDataProvider marketDataProvider;

    public FundamentalOverviewProvider(EastMoneyFundamentalClient fundamentalClient,
                                       MarketDataProvider marketDataProvider) {
        this.fundamentalClient = fundamentalClient;
        this.marketDataProvider = marketDataProvider;
    }

    /**
     * 财务指标概览（带缓存）。
     *
     * <p>缓存 6 小时：财务数据按季度披露，日间不变；
     * 而一次概览要打 2~3 个上游接口，不缓存会让每次页面访问都产生数倍延迟。
     * 缓存 key 前缀 {@code overview:} 与杜邦的 {@code dupont:} 共用同一个
     * cache name（{@code fundamental}），靠前缀区分——它们 TTL 相同、容量同池即可。
     */
    @Cacheable(cacheNames = CacheNames.FUNDAMENTAL, key = "'overview:' + #code", sync = true)
    public FundamentalOverview load(String code) {
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

    /**
     * 名称优先从行情源取；失败则退回代码本身，保证接口不因取名失败而报错。
     *
     * <p>由本类统一提供，避免 {@code FundamentalService} 再实现一份——
     * 两处实现必然在某次改动后出现「概览页显示股票名、业绩页显示代码」这种不一致。
     */
    public String resolveName(String code) {
        try {
            Quote quote = marketDataProvider.getQuote(code);
            return quote.name();
        } catch (RuntimeException ex) {
            return code;
        }
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
}
