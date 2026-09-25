package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.MarketPeriod;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.fundamental.dto.FundamentalOverview;
import com.stocklab.analyst.modules.fundamental.service.FundamentalService;
import com.stocklab.analyst.modules.indicator.dto.IndicatorName;
import com.stocklab.analyst.modules.indicator.dto.IndicatorResponse;
import com.stocklab.analyst.modules.indicator.service.IndicatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.stocklab.analyst.modules.analysis.support.AnalysisLimits.CONTEXT_BAR_DAYS;
import static com.stocklab.analyst.modules.analysis.support.AnalysisLimits.INDICATOR_BAR_LIMIT;

/**
 * 分析所需数据的取数器。
 *
 * <h2>并行取三块数据</h2>
 * 串行取的话，模型还没开始工作就要先等 K 线、指标、财务三次往返；
 * 三者之间没有依赖，并行可以把这段等待压到最慢的那一项。
 *
 * <h2>每一路都单独捕获异常</h2>
 * 财务接口挂掉不应该让整份分析无法生成，只是 prompt 里会写明
 * 「财务数据未获取到」。这也是 {@link AnalysisInput} 里
 * {@code bars} 用空列表、{@code indicators} / {@code fundamental} 用 null
 * 而不是让整个对象缺失的原因——缺项是常态，不是异常。
 *
 * <h2>为什么指标要单独再取一次 K 线</h2>
 * 指标计算内部会取 120 根（{@link AnalysisLimits#INDICATOR_BAR_LIMIT}），
 * 与本类的 60 根（{@link AnalysisLimits#CONTEXT_BAR_DAYS}）不是同一个缓存键。
 * 这是刻意的：指标需要更长的窗口做 EMA 与 MA60 的预热，
 * 而走势摘要只需要 60 根，多算 60 根对计算本身没有意义。
 */
@Component
public class AnalysisInputLoader {

    private static final Logger log = LoggerFactory.getLogger(AnalysisInputLoader.class);

    /** 分析链路固定使用的指标集合。 */
    private static final Set<IndicatorName> ANALYSIS_INDICATORS =
            Set.of(IndicatorName.MACD, IndicatorName.RSI, IndicatorName.BOLL,
                    IndicatorName.KDJ, IndicatorName.MA);

    private final MarketDataProvider marketDataProvider;
    private final IndicatorService indicatorService;
    private final FundamentalService fundamentalService;
    private final Executor fetchExecutor;

    public AnalysisInputLoader(MarketDataProvider marketDataProvider,
                               IndicatorService indicatorService,
                               FundamentalService fundamentalService,
                               @Qualifier("marketFetchExecutor") Executor fetchExecutor) {
        this.marketDataProvider = marketDataProvider;
        this.indicatorService = indicatorService;
        this.fundamentalService = fundamentalService;
        this.fetchExecutor = fetchExecutor;
    }

    /**
     * 取齐一次分析所需的全部数据。
     *
     * @param code  已归一化的 6 位股票代码
     * @param quote 实时报价（由调用方先取好，因为它同时承担「代码是否存在」的校验）
     */
    public AnalysisInput load(String code, Quote quote) {
        CompletableFuture<List<Bar>> barsFuture =
                CompletableFuture.supplyAsync(() -> safeBars(code), fetchExecutor);
        CompletableFuture<IndicatorResponse> indicatorsFuture =
                CompletableFuture.supplyAsync(() -> safeIndicators(code), fetchExecutor);
        CompletableFuture<FundamentalOverview> fundamentalFuture =
                CompletableFuture.supplyAsync(() -> safeFundamental(code), fetchExecutor);

        List<Bar> bars = barsFuture.join();
        IndicatorResponse indicators = indicatorsFuture.join();
        FundamentalOverview fundamental = fundamentalFuture.join();

        return new AnalysisInput(quote, bars, indicators, fundamental,
                buildBasis(bars, indicators, fundamental));
    }

    /** 本次真正取到的数据项清单。缺项在此不出现，用户据此就知道结论建立在什么之上。 */
    private static List<String> buildBasis(List<Bar> bars, IndicatorResponse indicators,
                                           FundamentalOverview fundamental) {
        List<String> basis = new ArrayList<>(4);
        basis.add("实时报价");
        if (!bars.isEmpty()) {
            basis.add("近 " + bars.size() + " 个交易日 K 线");
        }
        if (indicators != null) {
            basis.add("技术指标 MACD / RSI / BOLL / KDJ / MA");
        }
        if (fundamental != null) {
            basis.add("财务指标 PE / PB / ROE / 毛利率 / 资产负债率"
                    + ("estimated".equals(fundamental.dataSource()) ? "（估算值）" : ""));
        }
        return List.copyOf(basis);
    }

    private List<Bar> safeBars(String code) {
        try {
            List<Bar> bars = marketDataProvider.getBars(code, MarketPeriod.DAILY, CONTEXT_BAR_DAYS);
            return bars == null ? List.of() : bars;
        } catch (RuntimeException ex) {
            log.debug("分析取 K 线失败 code={}：{}", code, ex.getMessage());
            return List.of();
        }
    }

    private IndicatorResponse safeIndicators(String code) {
        try {
            return indicatorService.compute(code, ANALYSIS_INDICATORS, INDICATOR_BAR_LIMIT);
        } catch (RuntimeException ex) {
            log.debug("分析取技术指标失败 code={}：{}", code, ex.getMessage());
            return null;
        }
    }

    private FundamentalOverview safeFundamental(String code) {
        try {
            return fundamentalService.getOverview(code);
        } catch (RuntimeException ex) {
            log.debug("分析取财务数据失败 code={}：{}", code, ex.getMessage());
            return null;
        }
    }
}
