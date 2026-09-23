package com.stocklab.analyst.market.mock;

import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.StockCodes;
import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.Exchange;
import com.stocklab.analyst.market.model.IndexSnapshot;
import com.stocklab.analyst.market.model.MarketPeriod;
import com.stocklab.analyst.market.model.Mover;
import com.stocklab.analyst.market.model.MoverMetric;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.market.model.StockBrief;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 离线演示数据源。
 *
 * <p>作用：在没有外网、或上游行情接口临时不可用时，保证系统仍可启动、
 * 页面不白屏、接口结构完整。前端拿到的是与真实数据源<b>完全同构</b>的数据，
 * 因此可以放心用于本地开发与离线演示。
 *
 * <p><b>数据是确定性的</b>：同一只股票、同一时间片内多次调用返回相同结果；
 * 不同进程重启后结果也一致。这一点比原 FastAPI 版本更可靠——
 * Python 的 {@code hash()} 对字符串默认启用随机化种子（PYTHONHASHSEED），
 * 导致 mock 数据每次启动都变。Java 的 {@code String.hashCode()}
 * 由语言规范保证稳定，因此可以直接作为种子。
 *
 * <p><b>明确不是真实行情</b>，仅覆盖演示所需的价格量级与波动特征。
 */
public class MockMarketDataProvider implements MarketDataProvider {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 榜单条数上限，与真实源实现保持同一收敛区间。 */
    private static final int MAX_MOVERS_LIMIT = 20;

    /** 演示股票池：code -> {名称, 交易所, 基准价}。 */
    private static final Map<String, StockSeed> STOCK_POOL = new LinkedHashMap<>();

    static {
        STOCK_POOL.put("600519", new StockSeed("贵州茅台", Exchange.SH, 1685.00));
        STOCK_POOL.put("000858", new StockSeed("五粮液", Exchange.SZ, 138.20));
        STOCK_POOL.put("300750", new StockSeed("宁德时代", Exchange.SZ, 198.50));
        STOCK_POOL.put("600036", new StockSeed("招商银行", Exchange.SH, 38.20));
        STOCK_POOL.put("601318", new StockSeed("中国平安", Exchange.SH, 52.85));
        STOCK_POOL.put("600030", new StockSeed("中信证券", Exchange.SH, 24.66));
        STOCK_POOL.put("002594", new StockSeed("比亚迪", Exchange.SZ, 246.30));
        STOCK_POOL.put("000001", new StockSeed("平安银行", Exchange.SZ, 11.40));
        STOCK_POOL.put("600887", new StockSeed("伊利股份", Exchange.SH, 28.90));
        STOCK_POOL.put("688981", new StockSeed("中芯国际", Exchange.SH, 52.10));
    }

    /** 演示指数：code -> {名称, 基准点位}。 */
    private static final Map<String, IndexSeed> INDICES = new LinkedHashMap<>();

    static {
        INDICES.put("000001.SH", new IndexSeed("上证指数", 3112.45));
        INDICES.put("399001.SZ", new IndexSeed("深证成指", 10425.78));
        INDICES.put("399006.SZ", new IndexSeed("创业板指", 2012.33));
        INDICES.put("000688.SH", new IndexSeed("科创50", 1058.60));
        INDICES.put("000300.SH", new IndexSeed("沪深300", 3680.12));
    }

    /** 演示池内的代码，预热后支持搜索。 */
    private static final List<StockBrief> BRIEFS = STOCK_POOL.entrySet().stream()
            .map(e -> new StockBrief(e.getKey(), e.getValue().name(), e.getValue().exchange().display()))
            .toList();

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public Quote getQuote(String code) {
        StockCodes.ParsedCode parsed = requireParsed(code);
        StockSeed seed = seedOf(parsed.code());

        // 用 5 秒时间片做种子：同一时间片内报价稳定，跨片才变化，
        // 模拟「行情在跳动但不闪烁」的效果
        long bucket = System.currentTimeMillis() / 5_000L;
        Random random = new Random(codeSeed(parsed.code()) * 31L + bucket);

        double base = seed.basePrice();
        double changePct = random.nextDouble(-0.03, 0.03);
        double price = base * (1 + changePct);
        double prevClose = base;
        double open = prevClose * (1 + random.nextDouble(-0.01, 0.01));
        double high = Math.max(open, price) * (1 + random.nextDouble(0, 0.005));
        double low = Math.min(open, price) * (1 - random.nextDouble(0, 0.005));
        long volume = random.nextLong(10_000, 800_000);

        return new Quote(
                parsed.code(),
                seed.name(),
                parsed.exchange().display(),
                round2(price),
                round2(price - prevClose),
                round2(changePct * 100),
                round2(open),
                round2(high),
                round2(low),
                round2(prevClose),
                volume,
                round2(random.nextDouble(0.1, 5.0)),
                round2(price * volume * 100),
                System.currentTimeMillis() / 1000);
    }

    @Override
    public List<Bar> getBars(String code, MarketPeriod period, int limit) {
        StockCodes.ParsedCode parsed = requireParsed(code);
        StockSeed seed = seedOf(parsed.code());
        int effectiveLimit = Math.min(Math.max(limit, 1), 500);

        return switch (period) {
            case DAILY, WEEKLY, MONTHLY -> generateDaily(parsed.code(), seed, effectiveLimit, period);
            case TREND, MIN1, MIN5, MIN15, MIN30, MIN60 -> generateIntraday(parsed.code(), seed, effectiveLimit, period);
        };
    }

    @Override
    public List<IndexSnapshot> getIndices() {
        List<IndexSnapshot> result = new ArrayList<>(INDICES.size());
        long bucket = System.currentTimeMillis() / 60_000L;
        for (Map.Entry<String, IndexSeed> entry : INDICES.entrySet()) {
            Random random = new Random(entry.getKey().hashCode() * 31L + bucket);
            double pct = random.nextDouble(-0.01, 0.01);
            double value = entry.getValue().baseValue() * (1 + pct);
            result.add(new IndexSnapshot(
                    entry.getKey(),
                    entry.getValue().name(),
                    round2(value),
                    round2(value - entry.getValue().baseValue()),
                    round2(pct * 100)));
        }
        return result;
    }

    @Override
    public List<StockBrief> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        return BRIEFS.stream()
                .filter(brief -> brief.code().contains(needle)
                        || brief.name().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    /**
     * 演示榜单：从 10 只演示股票池里按维度排序。
     *
     * <p>复用 {@link #getQuote(String)} 而不是另造一套价格逻辑——
     * 同一时间片内两次调用返回相同值，保证「榜单上的价格」和
     * 「点进去看到的报价」一致，不会出现榜单 +5.2%、详情页 -1.3% 的演示事故。
     * 代价是每次调用触发 10 次 getQuote（纯内存计算，无网络），可忽略。
     */
    @Override
    public List<Mover> getTopMovers(MoverMetric metric, boolean ascending, int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_MOVERS_LIMIT);
        Comparator<Mover> comparator = switch (metric) {
            case CHANGE_PCT -> Comparator.comparingDouble(Mover::changePct);
            case AMOUNT -> Comparator.comparingDouble(Mover::amount);
        };
        if (!ascending) {
            comparator = comparator.reversed();
        }
        return STOCK_POOL.keySet().stream()
                .map(this::getQuote)
                .map(quote -> new Mover(
                        quote.code(),
                        quote.name(),
                        quote.price(),
                        quote.change(),
                        quote.changePct(),
                        quote.amount() == null ? 0 : quote.amount()))
                .sorted(comparator)
                .limit(effectiveLimit)
                .toList();
    }

    /**
     * 生成日线序列。
     *
     * <p>与原实现一致：用正弦项叠加随机扰动构造有趋势感的走势，
     * 跳过周末。周线 / 月线通过抽取日线生成，保证三者形态一致
     * （不会出现「同一只股票日线和周线趋势相反」这种演示事故）。
     */
    private List<Bar> generateDaily(String code, StockSeed seed, int limit, MarketPeriod period) {
        int step = switch (period) {
            case WEEKLY -> 5;
            case MONTHLY -> 21;
            default -> 1;
        };
        // 先按日线生成足够根数，再按周期抽样
        int dailyCount = limit * step;
        Random random = new Random(codeSeed(code));
        double base = seed.basePrice();
        double price = base * (1 - random.nextDouble(0.1, 0.25));
        double prevClose = base;

        List<Bar> daily = new ArrayList<>(dailyCount);
        LocalDate cursor = LocalDate.now();
        int produced = 0;
        int index = 0;
        while (produced < dailyCount) {
            cursor = cursor.minusDays(1);
            if (cursor.getDayOfWeek() == DayOfWeek.SATURDAY || cursor.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            double drift = Math.sin(index / 6.0) * 0.01 + random.nextDouble(-0.015, 0.015);
            double open = price;
            double close = price * (1 + drift);
            double high = Math.max(open, close) * (1 + random.nextDouble(0, 0.01));
            double low = Math.min(open, close) * (1 - random.nextDouble(0, 0.01));
            long volume = random.nextLong(20_000, 600_000);

            daily.add(new Bar(
                    cursor.format(DATE),
                    round2(open),
                    round2(high),
                    round2(low),
                    round2(close),
                    volume,
                    round2(close * volume * 100),
                    round2(prevClose)));

            prevClose = open;
            price = close;
            index++;
            produced++;
        }

        // daily 是从今天往前生成的，反转成时间升序
        daily.sort(Comparator.comparing(Bar::date));

        if (step == 1) {
            // 只保留最后 limit 根
            return daily.subList(Math.max(0, daily.size() - limit), daily.size());
        }
        List<Bar> sampled = new ArrayList<>();
        for (int i = daily.size() - 1; i >= 0 && sampled.size() < limit; i -= step) {
            sampled.add(daily.get(i));
        }
        sampled.sort(Comparator.comparing(Bar::date));
        return sampled;
    }

    /**
     * 生成分钟级序列（含分时）。
     *
     * <p>按 A 股交易时段（09:30–11:30、13:00–15:00）倒推时间戳，
     * 保证时间刻度落在真实交易时段内，前端坐标轴形态正确。
     */
    private List<Bar> generateIntraday(String code, StockSeed seed, int limit, MarketPeriod period) {
        int intervalMinutes = switch (period) {
            case TREND, MIN1 -> 1;
            case MIN5 -> 5;
            case MIN15 -> 15;
            case MIN30 -> 30;
            case MIN60 -> 60;
            default -> 1;
        };
        Random random = new Random(codeSeed(code) * 31L + intervalMinutes);
        double base = seed.basePrice();
        double price = base * (1 - random.nextDouble(0.005, 0.02));
        double prevClose = base;

        List<LocalDateTime> timestamps = buildTradingTimestamps(limit, intervalMinutes);
        List<Bar> bars = new ArrayList<>(timestamps.size());
        for (int i = 0; i < timestamps.size(); i++) {
            double drift = Math.sin(i / 8.0) * 0.002 + random.nextDouble(-0.004, 0.004);
            double open = price;
            double close = price * (1 + drift);
            double high = Math.max(open, close) * (1 + random.nextDouble(0, 0.002));
            double low = Math.min(open, close) * (1 - random.nextDouble(0, 0.002));
            long volume = random.nextLong(500, 40_000);

            bars.add(new Bar(
                    timestamps.get(i).format(DATE_TIME),
                    round2(open),
                    round2(high),
                    round2(low),
                    round2(close),
                    volume,
                    round2(close * volume * 100),
                    round2(prevClose)));

            prevClose = open;
            price = close;
        }
        return bars;
    }

    /** 倒推生成最近 limit 个落在交易时段内的时间点，返回升序列表。 */
    private List<LocalDateTime> buildTradingTimestamps(int limit, int intervalMinutes) {
        LocalTime morningStart = LocalTime.of(9, 30);
        LocalTime morningEnd = LocalTime.of(11, 30);
        LocalTime afternoonStart = LocalTime.of(13, 0);
        LocalTime afternoonEnd = LocalTime.of(15, 0);

        List<LocalDateTime> slots = new ArrayList<>();
        LocalDate day = LocalDate.now();
        // 收集最近若干个交易日的时段槽位，直到够 limit 个
        while (slots.size() < limit) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                for (LocalTime t = morningEnd; !t.isBefore(morningStart); t = t.minusMinutes(intervalMinutes)) {
                    slots.add(day.atTime(t));
                }
                for (LocalTime t = afternoonEnd; !t.isBefore(afternoonStart); t = t.minusMinutes(intervalMinutes)) {
                    slots.add(day.atTime(t));
                }
            }
            day = day.minusDays(1);
        }
        slots.sort(Comparator.naturalOrder());
        return slots.subList(Math.max(0, slots.size() - limit), slots.size());
    }

    /** 稳定且跨进程一致的代码种子。 */
    private int codeSeed(String code) {
        return code.hashCode();
    }

    private StockCodes.ParsedCode requireParsed(String code) {
        StockCodes.ParsedCode parsed = StockCodes.parse(code);
        if (parsed == null) {
            throw new com.stocklab.analyst.common.exception.BizException(
                    com.stocklab.analyst.common.exception.ErrorCode.QUOTE_NOT_FOUND,
                    "未找到股票代码 " + code);
        }
        return parsed;
    }

    private StockSeed seedOf(String code) {
        StockSeed seed = STOCK_POOL.get(code);
        if (seed == null) {
            throw new com.stocklab.analyst.common.exception.BizException(
                    com.stocklab.analyst.common.exception.ErrorCode.QUOTE_NOT_FOUND,
                    "演示数据源未收录该股票代码 " + code);
        }
        return seed;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record StockSeed(String name, Exchange exchange, double basePrice) {
    }

    private record IndexSeed(String name, double baseValue) {
    }
}
