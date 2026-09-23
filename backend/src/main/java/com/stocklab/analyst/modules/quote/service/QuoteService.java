package com.stocklab.analyst.modules.quote.service;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.IndexSnapshot;
import com.stocklab.analyst.market.model.MarketPeriod;
import com.stocklab.analyst.market.model.Mover;
import com.stocklab.analyst.market.model.MoverMetric;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.market.model.StockBrief;
import com.stocklab.analyst.modules.quote.dto.CarouselItem;
import com.stocklab.analyst.modules.quote.dto.MarketSourceStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 行情服务：把数据源能力编排成前端需要的视图数据。
 *
 * <p>本层不直接访问 HTTP，只依赖 {@link MarketDataProvider} 抽象，
 * 因此单元测试里可以注入假实现，不触网。
 */
@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    /** 轮播候选：覆盖白酒 / 新能源 / 金融 / 汽车 / 半导体等主要板块。 */
    private static final List<String> CAROUSEL_CANDIDATES = List.of(
            "600519", "000858", "300750", "600036", "601318",
            "600030", "002594", "000001", "600887", "688981");

    private static final List<String> HIGHLIGHT_POOL = List.of(
            "主力资金净流入",
            "行业景气持续上行",
            "成交量明显放大",
            "机构关注度升温",
            "技术面出现买点信号",
            "市场情绪回暖");

    private final MarketDataProvider marketDataProvider;
    private final MeterRegistry meterRegistry;

    public QuoteService(MarketDataProvider marketDataProvider, MeterRegistry meterRegistry) {
        this.marketDataProvider = marketDataProvider;
        this.meterRegistry = meterRegistry;
    }

    public Quote getQuote(String code) {
        return marketDataProvider.getQuote(code);
    }

    /**
     * 查询 K 线。
     *
     * <p>{@code start} / {@code end} 在原 FastAPI 版本中虽然出现在接口签名上，
     * 但没有被任何数据源实现使用（等于静默忽略参数）。这里补全为真实生效：
     * 拿到数据后按日期区间过滤。公开的接口参数不应该「收下但不用」。
     */
    public List<Bar> getBars(String code, MarketPeriod period, int limit, String start, String end) {
        List<Bar> bars = marketDataProvider.getBars(code, period, limit);
        if (bars.isEmpty()) {
            throw new BizException(ErrorCode.QUOTE_NOT_FOUND, "未获取到 " + code + " 的 K 线数据");
        }
        String from = start == null ? "" : start.trim();
        String to = end == null ? "" : end.trim();
        if (from.isEmpty() && to.isEmpty()) {
            return bars;
        }
        return bars.stream()
                .filter(bar -> from.isEmpty() || bar.date().compareTo(from) >= 0)
                .filter(bar -> to.isEmpty() || bar.date().compareTo(to) <= 0)
                .toList();
    }

    public List<StockBrief> search(String keyword) {
        return marketDataProvider.search(keyword);
    }

    public List<IndexSnapshot> getIndices() {
        return marketDataProvider.getIndices();
    }

    /**
     * 市场榜单（涨幅榜 / 跌幅榜 / 成交额榜）。
     *
     * <p>limit 在这里收敛而不是只在 Controller 收敛：本服务的调用方
     * 不一定都经过 HTTP 入参校验（未来的定时任务、内部接口），
     * 「期望条数」的合理性保证放在离数据源最近的一层。
     */
    public List<Mover> topMovers(MoverMetric metric, boolean ascending, int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), 20);
        return marketDataProvider.getTopMovers(metric, ascending, effectiveLimit);
    }

    /**
     * 首页轮播数据。
     *
     * <p>用「当天日期」作为随机种子：同一天内多次请求返回同一批股票与文案，
     * 刷新页面不会出现卡片顺序乱跳；跨天则自然轮换。
     * 这是个容易忽略的体验细节——用纯随机会让首页每次刷新都变样。
     *
     * <p>单只股票取数失败只跳过该条，不影响整批返回：
     * 轮播里少一张卡片远好于整个首页报错。
     */
    public List<CarouselItem> getCarousel(int count) {
        meterRegistry.counter("app.quote.carousel.requests").increment();

        long seed = Long.parseLong(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        Random random = new Random(seed);

        List<String> shuffled = new ArrayList<>(CAROUSEL_CANDIDATES);
        Collections.shuffle(shuffled, random);

        List<CarouselItem> items = new ArrayList<>(count);
        for (String code : shuffled) {
            if (items.size() >= count) {
                break;
            }
            try {
                Quote quote = marketDataProvider.getQuote(code);
                items.add(new CarouselItem(
                        quote.code(),
                        quote.name(),
                        quote.price(),
                        quote.change(),
                        quote.changePct(),
                        HIGHLIGHT_POOL.get(random.nextInt(HIGHLIGHT_POOL.size()))));
            } catch (RuntimeException ex) {
                log.debug("轮播取数跳过 {}：{}", code, ex.getMessage());
            }
        }
        return items;
    }

    /**
     * 数据源状态。
     *
     * <p>如实同时给出「配置的主数据源」与「实际在用哪个源」，
     * 不在降级时把主数据源的名字当成本次数据的来源对外播报。
     */
    public MarketSourceStatus sourceStatus() {
        return MarketSourceStatus.of(
                marketDataProvider.name(),
                marketDataProvider.available(),
                marketDataProvider.degraded(),
                marketDataProvider.servingByAction());
    }
}
