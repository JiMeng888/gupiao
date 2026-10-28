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
import com.stocklab.analyst.market.model.Sector;
import com.stocklab.analyst.market.model.StockBrief;
import com.stocklab.analyst.modules.quote.dto.CarouselItem;
import com.stocklab.analyst.modules.quote.dto.MarketSourceStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /**
     * 轮播并行取价的总时间预算（秒）。
     *
     * <p>个券的读超时是 6 秒（{@code app.market-data.timeout}），三级降级链最坏
     * 会串行叠三次，所以理论上单只可能耗到十几秒。首页是首屏，
     * 不该为「一个区块」等这么久——超预算就交付已取到的部分，
     * 前端拿到少于 6 条时按实际条数渲染即可（它本来就允许条数不固定）。
     */
    private static final int CAROUSEL_FETCH_BUDGET_SECONDS = 12;

    private final MarketDataProvider marketDataProvider;
    private final MeterRegistry meterRegistry;

    /** 轮播取价用的线程池。与行情/资讯共用 {@code marketFetchExecutor}，都是 IO 密集型短任务。 */
    private final Executor carouselFetchExecutor;

    /** 轮播并行取价的总时间预算（秒），见 {@link #CAROUSEL_FETCH_BUDGET_SECONDS}。 */
    private final int fetchBudgetSeconds;

    @Autowired
    public QuoteService(MarketDataProvider marketDataProvider,
                        MeterRegistry meterRegistry,
                        @Qualifier("marketFetchExecutor") Executor carouselFetchExecutor) {
        this(marketDataProvider, meterRegistry, carouselFetchExecutor, CAROUSEL_FETCH_BUDGET_SECONDS);
    }

    /**
     * 指定取价预算的构造器，仅供测试。
     *
     * <p>把预算做成可注入而不是常量，是因为「超预算就交付已有结果」这条分支
     * 在生产配置下要等满 12 秒才走得到——那样的测试没人愿意跑，
     * 最后就会变成一条永远不执行的路径。
     */
    QuoteService(MarketDataProvider marketDataProvider,
                 MeterRegistry meterRegistry,
                 Executor carouselFetchExecutor,
                 int fetchBudgetSeconds) {
        this.marketDataProvider = marketDataProvider;
        this.meterRegistry = meterRegistry;
        this.carouselFetchExecutor = carouselFetchExecutor;
        this.fetchBudgetSeconds = Math.max(1, fetchBudgetSeconds);
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
     * 板块榜单（按涨跌幅降序）。
     *
     * <p>不吞异常：真实源不可用时由 {@code MarketDataUnavailableException} 一路抛到
     * Controller 映射为 503，前端据此展示「板块数据暂不可用」。
     * 返回空列表会让前端无法区分「今天没有板块数据」与「上游故障」，
     * 而这两种情况在界面上本就应该给出不同提示。
     */
    public List<Sector> sectors(int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), 20);
        return marketDataProvider.getSectors(effectiveLimit);
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
     * <p><b>取价是并行的（相对首版的修正）</b>：首版用一个 for 循环逐个调
     * {@code getQuote}，冷缓存时就是最多 10 次串行上游往返，首屏耗时等于
     * 所有单只报价之和。现在提交到 {@code marketFetchExecutor} 并行取，
     * 整块耗时降到「最慢的那一只」。并行不改变确定性：
     * 结果按候选顺序回填，高亮文案的随机数也仍在规划阶段按同样顺序生成，
     * 因此「同一天 + 同一轮次 → 完全一致」这条性质保持不变。
     *
     * <p>单只股票取数失败只跳过该条，不影响整批返回——批量数据源里
     * 偶尔有个别标的拉不到是常态，不该让它拖垮整个首页区块。
     * 整批还有一个总预算（{@value #CAROUSEL_FETCH_BUDGET_SECONDS} 秒），
     * 防止个别上游卡住时把首页请求一起拖住。
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

        // 种子同样只由「日期 + 轮次」决定，保证 tag/文案与批次一一对应。
        // 注意 random 的消费顺序必须与 picked 顺序一致——挪到这里统一生成
        // （而不是取价时生成），就是为了让并行取价不影响文案的确定性。
        Random random = new Random(epochDay * 1000L + step);
        List<String> highlights = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            highlights.add(HIGHLIGHT_POOL.get(random.nextInt(HIGHLIGHT_POOL.size())));
        }

        // 先全部投递，再按顺序收集：投递完才 wait，才能真正重叠 IO
        List<CompletableFuture<CarouselItem>> futures = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            futures.add(fetchCarouselItem(picked.get(i), highlights.get(i)));
        }

        return collectCarouselItems(futures, picked);
    }

    /** 投递单只标的的取价任务。任何投递期异常都转成「失败的结果」，不影响整批。 */
    private CompletableFuture<CarouselItem> fetchCarouselItem(String code, String highlight) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                Quote quote = marketDataProvider.getQuote(code);
                return new CarouselItem(
                        quote.code(),
                        quote.name(),
                        quote.price(),
                        quote.change(),
                        quote.changePct(),
                        highlight);
            }, carouselFetchExecutor);
        } catch (RuntimeException ex) {
            // 线程池拒绝（AbortPolicy 会走到这里）等情况：只丢这一条，不炸整批
            log.debug("精选关注投递失败 {}：{}", code, ex.getMessage());
            return CompletableFuture.failedFuture(ex);
        }
    }

    /**
     * 按投递顺序收集结果，跳过失败项，并受总预算约束。
     *
     * <p>逐个 {@code get(remaining)} 而不是 {@code allOf().get()}：
     * 前者能让「已经成功的」尽快进入结果，后者要等最慢的那个；
     * 更重要的是后者一旦有一个任务卡住，整个接口就跟着卡住。
     */
    private List<CarouselItem> collectCarouselItems(List<CompletableFuture<CarouselItem>> futures,
                                                    List<String> picked) {
        List<CarouselItem> items = new ArrayList<>(futures.size());
        long deadline = System.nanoTime() + Duration.ofSeconds(fetchBudgetSeconds).toNanos();
        boolean budgetExhausted = false;

        for (int i = 0; i < futures.size(); i++) {
            if (budgetExhausted) {
                break;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                log.warn("精选关注取价超出 {} 秒总预算，返回已完成的 {} 条",
                        fetchBudgetSeconds, items.size());
                break;
            }
            try {
                items.add(futures.get(i).get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException ex) {
                // 预算用尽：不再等后面的（它们只会更晚），直接交付已有结果
                log.warn("精选关注取价超时，返回已完成的 {} 条", items.size());
                budgetExhausted = true;
            } catch (ExecutionException ex) {
                log.debug("精选关注取数跳过 {}：{}", picked.get(i), ex.getCause());
            } catch (InterruptedException ex) {
                // 恢复中断位：吞掉中断会让上层无法感知停机信号
                Thread.currentThread().interrupt();
                log.warn("精选关注取价被中断，返回已完成的 {} 条", items.size());
                break;
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
