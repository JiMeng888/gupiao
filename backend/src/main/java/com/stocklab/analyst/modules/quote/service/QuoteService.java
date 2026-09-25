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
import java.util.ArrayList;
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

    /** 轮播候选池：覆盖白酒 / 新能源 / 金融 / 家电 / 半导体等主要板块。
     *
     *  <p>池子必须显著大于单批条数——「每轮整片换新」这个效果全靠这一点成立。
     *  如果池子只有 10 只而单批要 6 只，任何两批之间至少重合 2 只，
     *  连续两轮更会重合 5 只，用户看到的就是「只换了一只，卡住了」。
     */
    private static final List<String> CAROUSEL_CANDIDATES = List.of(
            "600519", "000858", "300750", "600036", "601318",
            "600030", "002594", "000001", "600887", "688981",
            "601899", "600276", "000651", "002415", "600309",
            "601012", "600585", "000725", "002230", "601668",
            "600900", "000333", "601166", "688111", "600031",
            "002714", "601138", "600570", "000568", "601601");

    /** 候选池规模。 */
    private static final int CAROUSEL_POOL_SIZE = CAROUSEL_CANDIDATES.size();

    /** 上一批与下一批之间额外留出的空档（按池内位置算）。 */
    private static final int CAROUSEL_MIN_GAP = 6;

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
     * 首页精选关注数据。
     *
     * <p><b>为什么用「轮次」而不是时间戳</b>：首页每 10 秒换一批精选股，
     * 最直观的做法是把当前时间戳传进来当随机种子。但那样会有两个问题：
     * 同一时刻打开首页的两个客户端可能拿到不同批次（看起来像数据在抖），
     * 而且「第 3 轮应该出哪几只」无法复现、无法写断言。
     * 所以改用<b>自增轮次</b>作为唯一变量：同一天 + 同一轮次 → 结果完全一致。
     *
     * <p><b>换批算法</b>：把候选池看成一个环，第 n 批从位置
     * {@code (当天偏移 + n × 步长) mod 池大小} 起连续取 {@code size} 只，
     * 其中步长 = {@code size + }{@value #CAROUSEL_MIN_GAP}。
     *
     * <p>这里有个容易踩的坑，值得写下来：<b>「连续取 size 只 + 步长取 1」是错的</b>。
     * 若池 10 只、单批 6 只、步长 1，相邻两批必然重合 5 只——数学上每轮
     * 只换掉 1 只，用户看到的画面是「六格里只有一格跳了一下」，
     * 与「没刷新」在观感上没有区别。（这个坑实际踩过：最初就是池 10 只 + 步长 1。）
     * 因此改为：
     * <ul>
     *   <li>候选池扩到 {@value #CAROUSEL_POOL_SIZE} 只，为单批 6 只的 5 倍；</li>
     *   <li>步长取 {@code size + gap}，使相邻两批在环上的区间天然不相交。</li>
     * </ul>
     *
     * <p>取 {@code size + gap} 作为步长还带来一个好处：只要池大小不被
     * {@code size + gap} 整除，环上各批就会均匀轮转，任何一只股票在
     * 若干轮内出现次数相同，不会出现「某几只股票天天露脸」的偏袒。
     * 当池子小到装不下两批互斥（{@code 2 × size + gap > 池大小}）时，
     * 退化为步长 {@code size}——至少保证集合在移动，不会原样返回。
     *
     * <p>单只股票取数失败只跳过该条，不影响整批返回——批量数据源里
     * 偶尔有个别标的拉不到是常态，不该让它拖垮整个首页区块。
     *
     * @param count 本次返回条数
     * @param round 轮次（自增整数；前端每 10 秒递增一次，不传默认第 0 轮）
     */
    public List<CarouselItem> getCarousel(int count, int round) {
        meterRegistry.counter("app.quote.carousel.requests").increment();

        int poolSize = CAROUSEL_POOL_SIZE;
        int size = Math.min(Math.max(count, 1), poolSize);
        int step = Math.max(round, 0);

        long epochDay = LocalDate.now().toEpochDay();
        int dayOffset = (int) Math.floorMod(epochDay, poolSize);

        // 池子装得下「两批互斥」时用 size + gap，否则退化到 size
        int stride = (2L * size + CAROUSEL_MIN_GAP <= poolSize) ? size + CAROUSEL_MIN_GAP : size;
        int start = Math.floorMod(dayOffset + (long) step * stride, poolSize);

        List<String> picked = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            picked.add(CAROUSEL_CANDIDATES.get(Math.floorMod(start + i, poolSize)));
        }

        // 种子同样只由「日期 + 轮次」决定，保证 tag/文案与批次一一对应
        Random random = new Random(epochDay * 1000L + step);
        List<CarouselItem> items = new ArrayList<>(size);
        for (String code : picked) {
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
                log.debug("精选关注取数跳过 {}：{}", code, ex.getMessage());
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
