package com.stocklab.analyst.market;

import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.IndexSnapshot;
import com.stocklab.analyst.market.model.MarketPeriod;
import com.stocklab.analyst.market.model.Mover;
import com.stocklab.analyst.market.model.MoverMetric;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.market.model.StockBrief;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 带降级的行情路由器（装饰器模式）。
 *
 * <h2>多级降级链</h2>
 * 数据源按优先级组成一条链（如 {@code eastmoney → tencent → mock}），
 * 每次调用从链头开始尝试，谁先成功谁服务：
 * <ul>
 *   <li>{@link MarketDataUnavailableException}（上游超时/拒连/风控断连）
 *       → 记录降级痕迹，尝试下一级；</li>
 *   <li>{@code QUOTE_NOT_FOUND}（上游明确表示没有这个标的）→ <b>直接上抛</b>；
 *       把「查无此股」降级成「给你编一个」会让用户拿到错误的数据，比报错更糟；</li>
 *   <li>其它 RuntimeException（自身 bug）→ 直接上抛，不把故障掩盖成「上游抖动」。</li>
 * </ul>
 *
 * <p>兜底源（链尾，通常是 mock）失败时没有下一级可去，此时如实上抛。
 * {@code fallbackEnabled=false} 时链被截断为只允许链头第一级——
 * 「关闭降级」的语义是<b>只信任配置的主数据源</b>，任何一级都不许替它服务。
 *
 * <h2>故障冷却（简易熔断）</h2>
 * 某一级真实源的<b>某个操作</b>请求失败后进入冷却期（默认 30 秒，由配置开启）：
 * 冷却期内的同类请求直接跳过它从下一级取数，到期自动放行一次探测，成功即解除冷却。
 *
 * <p>三个设计约束：
 * <ul>
 *   <li><b>按操作隔离</b>：「报价接口被风控、K 线接口正常」是真实常态，
 *       冷却 key 是「操作:数据源」，报价的一次故障不会连累本可正常服务的 K 线；</li>
 *   <li><b>跳过也计入告警</b>：冷却跳过对调用方而言仍是一次「由降级源服务」，
 *       fallback 指标照常累加——否则「主源持续故障 + 冷却生效」会让告警静默；</li>
 *   <li><b>链尾兜底永不冷却</b>：mock 不走网络，跳过它等于放弃最后一层保障。</li>
 * </ul>
 *
 * <p>为什么需要冷却：东财风控表现为<b>服务端直接断连</b>，失败虽然快（几百毫秒），
 * 但若是超时型故障（6 秒读超时），WebSocket 每 3 秒的行情推送会让每个周期
 * 都在死源上白等一次超时，行情页肉眼可见地变慢。冷却把「每个请求都试一次死源」
 * 变成「每 30 秒探测一次」，代价是恢复检测最多延迟一个冷却周期。
 * 默认关闭（冷却时长 0），由 {@code app.market-data.upstream-cooldown} 显式开启。
 *
 * <p>链尾兜底（mock）不走网络，永不冷却。
 *
 * <h2>降级是「有痕迹」的</h2>
 * <ul>
 *   <li>{@code app.market_data.fallback}：每级失败各累加一次，
 *       tag 含 action / primary（链头）/ from（失败的那一级），可据此配置告警；</li>
 *   <li>{@code app.market_data.circuit_skip}：冷却期内被跳过的次数——
 *       这个数字持续增长说明有源长期处于不健康状态；</li>
 *   <li>{@code app.market_data.fabrication_refused}：降级源<b>策略性拒绝</b>
 *       提供某个操作的数据的次数（见下节）。这个数字持续增长意味着
 *       「真实源长期不可用 + 某个操作永久不可用」，是需要人介入的状态，
 *       与「降级兜住了」完全不是一回事；</li>
 *   <li>{@link #servingByAction()}：各操作最近一次的实际服务源，
 *       主源恢复后下一次成功调用自动归位，不搞「曾经降级就永久置位」。</li>
 * </ul>
 *
 * <h2>降级不等于「什么都用演示数据顶上」（重要）</h2>
 * 兜底源可以在降级路径上主动<b>拒绝</b>某个操作（{@link MarketDataProvider#acceptableAsFallback}）。
 * 被拒绝的操作会直接返回 503「暂不可用」，而不是给出一份编造的数据。
 *
 * <p>目前只有演示源对<b>榜单</b>这么声明，理由是榜单与其它数据有本质差别：
 * 报价是「某只股票多少钱」，用户至少能靠红色横幅意识到它可能不真；
 * 而榜单是<b>排序结果</b>，名次本身携带「第 1 名值得关注」的判断，
 * 这个判断完全由演示池那 10 只固定股票的随机涨跌幅决定。更糟的是
 * 它<b>看起来完全合理</b>（「招商银行 +2.5%」不像乱码），用户拿它选股
 * 会真的亏钱。<b>「暂不可用」比「看起来合理的假榜单」安全。</b>
 *
 * <p>注意拒绝只作用于<b>降级路径</b>：演示源自己作为主数据源（
 * {@code provider=mock} 的离线模式）时不受影响——那是用户显式选择了
 * 「只看演示数据」，离线演示仍需要完整的产品形态。
 *
 * <p>另外，策略拒绝<b>不累加</b> {@code fallback} 指标。若继续累加，
 * 「榜单永久不可用」和「榜单由演示数据顶着」在告警上会长得一模一样。
 *
 * <h2>关于 {@link #degraded()} 的语义（重要）</h2>
 * {@code degraded=true} 专指「有操作正在由<b>链尾演示源</b>服务」——
 * 前端据此挂「演示数据，请勿据此投资」的红色警示，所以它必须等价于
 * 「用户正在看到编造的数据」。链中间的备用真实源（如腾讯）接管时
 * {@code degraded} 保持 false：那是真实行情，挂演示警示是在撒谎。
 * 「数据实际来自谁」始终以 {@link #servingByAction()} 为准。
 */
public class ResilientMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(ResilientMarketDataProvider.class);

    /** 兼容旧两级构造的默认值：禁用冷却。冷却由部署配置显式开启（见 MarketDataProperties）。 */
    private static final long DEFAULT_COOLDOWN_MS = 0L;

    /** 数据源降级链，下标 0 是主数据源，末位是兜底源。 */
    private final List<MarketDataProvider> chain;

    private final boolean fallbackEnabled;
    private final long cooldownMillis;
    private final MeterRegistry meterRegistry;

    /**
     * 各操作最近一次实际服务源。只记录「最近一次」：主数据源恢复后，
     * 下一次调用就会把该操作改回主源，{@link #degraded()} 随之自动归位。
     */
    private final Map<String, String> servingByAction = new ConcurrentHashMap<>();

    /** 各数据源的冷却截止时间（epoch ms）。key 为「操作:数据源名」——
     *  冷却必须按操作隔离：「报价接口被风控、K 线接口正常」是真实常态，
     *  源级冷却会让报价的一次故障连累本可正常服务的 K 线。 */
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    /**
     * 兼容旧的两级构造（{@code primary + fallback}）。
     * 等价于 {@code chain = [primary, fallback]}、默认冷却时长。
     */
    public ResilientMarketDataProvider(MarketDataProvider primary,
                                       MarketDataProvider fallback,
                                       boolean fallbackEnabled,
                                       MeterRegistry meterRegistry) {
        this(List.of(primary, fallback), fallbackEnabled, DEFAULT_COOLDOWN_MS, meterRegistry);
    }

    /**
     * 多级链构造。
     *
     * @param chain          数据源降级链，按优先级排列；末位为兜底源（如 mock）
     * @param fallbackEnabled false 时链被截断为只用第一级（关闭一切降级）
     * @param cooldownMillis 真实源失败后的冷却时长；0 表示禁用冷却
     */
    public ResilientMarketDataProvider(List<MarketDataProvider> chain,
                                       boolean fallbackEnabled,
                                       long cooldownMillis,
                                       MeterRegistry meterRegistry) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("行情数据源链不能为空");
        }
        this.chain = List.copyOf(chain);
        this.fallbackEnabled = fallbackEnabled;
        this.cooldownMillis = Math.max(0, cooldownMillis);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String name() {
        return chain.get(0).name();
    }

    @Override
    public boolean available() {
        return chain.get(0).available();
    }

    @Override
    public Map<String, String> servingByAction() {
        return Map.copyOf(servingByAction);
    }

    @Override
    public boolean degraded() {
        // 「降级」专指正在使用链尾演示源；备用真实源接管不算。
        // 链头本身就是兜底源（provider=mock 的离线配置）时不存在降级概念。
        String demoSource = chain.get(chain.size() - 1).name();
        if (demoSource.equals(chain.get(0).name())) {
            return false;
        }
        return servingByAction.values().stream().anyMatch(demoSource::equals);
    }

    @Override
    public Quote getQuote(String code) {
        return route("getQuote", code, provider -> provider.getQuote(code));
    }

    @Override
    public List<Bar> getBars(String code, MarketPeriod period, int limit) {
        return route("getBars", code, provider -> provider.getBars(code, period, limit));
    }

    @Override
    public List<IndexSnapshot> getIndices() {
        return route("getIndices", "-", provider -> provider.getIndices());
    }

    @Override
    public List<StockBrief> search(String keyword) {
        return route("search", keyword, provider -> provider.search(keyword));
    }

    @Override
    public List<Mover> getTopMovers(MoverMetric metric, boolean ascending, int limit) {
        return route("getTopMovers", metric.name(),
                provider -> provider.getTopMovers(metric, ascending, limit));
    }

    @Override
    public void warmup() {
        // 各实现自行保证幂等（接口契约），这里只负责遍历。
        // 主源就绪时跳过，仍不就绪的源（含被冷却的源）照常预热。
        for (MarketDataProvider provider : chain) {
            if (provider.available()) {
                continue;
            }
            try {
                provider.warmup();
            } catch (RuntimeException ex) {
                log.warn("行情源 {} 预热失败：{}", provider.name(), ex.getMessage());
            }
        }
    }

    // ==================================================================
    // 路由核心
    // ==================================================================

    /**
     * 沿降级链依次尝试，返回第一个成功源的结果。
     *
     * <p>只有 {@link MarketDataUnavailableException} 会推进到下一级；
     * {@code QUOTE_NOT_FOUND} 与其它 RuntimeException 均直接穿透上抛
     * （前者是用户的输入问题，后者是我们自己的 bug，两者都不该被降级掩盖）。
     */
    private <T> T route(String action, String subject, Function<MarketDataProvider, T> call) {
        String primaryName = chain.get(0).name();
        // 关闭降级 = 只允许链头第一级服务；链上只有一级时同样没有「下一级」
        int usableCount = fallbackEnabled && chain.size() > 1 ? chain.size() : 1;
        MarketDataUnavailableException lastUnavailable = null;
        // 是否有降级源「策略性拒绝」为该操作提供演示数据（见 acceptableAsFallback）
        boolean refusedFabrication = false;

        for (int i = 0; i < usableCount; i++) {
            MarketDataProvider provider = chain.get(i);
            boolean hasFallbackAfter = i < usableCount - 1;
            String cooldownKey = action + ":" + provider.name();

            // 策略拒绝优先于冷却判断：这是数据源对「本操作能不能编」的静态声明，
            // 与它当前健不健康无关，也没必要为它维护冷却计时。
            //
            // 不调用、也不累加 fallback 指标——继续累加会让「榜单不可用」
            // 和「榜单由演示数据顶着」在告警上长得一样，而这两件事的处置
            // 完全不同：前者要人介入，后者只是数据质量下降。
            if (i > 0 && !provider.acceptableAsFallback(action)) {
                refusedFabrication = true;
                meterRegistry.counter("app.market_data.fabrication_refused",
                        "action", action, "provider", provider.name()).increment();
                log.warn("{} 拒绝为「{}」提供演示数据：该操作的数据必须真实，宁可不可用",
                        provider.name(), actionLabel(action));
                continue;
            }

            // 冷却中的真实源直接跳过（兜底源永不冷却：它不走网络，
            // 跳过它等于放弃了最后一层保障）。
            // 跳过对调用方而言仍然是一次「由降级源服务」，因此同时累加
            // fallback 告警指标——否则「主源持续故障 + 冷却生效」的组合
            // 会让告警完全静默，故障被冷却机制藏起来。
            if (hasFallbackAfter && isCoolingDown(cooldownKey)) {
                meterRegistry.counter("app.market_data.circuit_skip",
                        "provider", provider.name(), "action", action).increment();
                meterRegistry.counter("app.market_data.fallback",
                        "action", action,
                        "primary", primaryName,
                        "from", provider.name()).increment();
                continue;
            }

            try {
                T result = call.apply(provider);
                markServed(action, provider);
                // 成功即解除冷却：下一次故障前不再跳过该源
                cooldownUntil.remove(cooldownKey);
                return result;
            } catch (MarketDataUnavailableException ex) {
                lastUnavailable = ex;
                if (!hasFallbackAfter) {
                    // 走到链尾仍不可用：要么显式关闭了降级，要么整条链全挂。
                    // 两者都没有「兜底成功」发生，必须如实记 unavailable 而不是 fallback，
                    // 否则「降级兜住了」和「一个请求都没兜住」在指标上无法区分。
                    meterRegistry.counter("app.market_data.unavailable",
                            "action", action, "primary", primaryName).increment();
                    String reason = fallbackEnabled && chain.size() > 1
                            ? "整条降级链均不可用"
                            : "已关闭演示数据降级";
                    log.warn("行情主数据源不可用，且{}（action={} subject={}）：{}",
                            reason, action, subject, ex.getMessage(), ex);
                    throw new MarketDataUnavailableException(
                            actionLabel(action) + "暂不可用（" + reason + "）", ex);
                }
                meterRegistry.counter("app.market_data.fallback",
                        "action", action,
                        "primary", primaryName,
                        "from", provider.name()).increment();
                log.warn("行情源 {} 不可用，尝试下一级（action={} subject={}）：{}",
                        provider.name(), action, subject, ex.getMessage(), ex);
                openCooldown(cooldownKey, provider);
            }
        }

        // 走到这里只有一种情形：该操作的降级源被策略拒绝，且真实源全都没服务成功。
        // 这是「设计上的不可用」，必须与「上游抖动」区分开说，否则运维会去
        // 排查一个根本不存在的数据源故障。
        if (refusedFabrication) {
            throw new MarketDataUnavailableException(
                    actionLabel(action) + "暂不可用：上游数据源故障，且该类数据不使用内置演示数据替代");
        }
        // 防御性兜底：绝不静默返回 null。
        throw lastUnavailable != null
                ? lastUnavailable
                : new MarketDataUnavailableException("无可用行情数据源（action=" + action + "）");
    }

    /**
     * 操作名的中文标签，用于日志与<b>面向用户的错误消息</b>。
     *
     * <p>单独抽出来的原因是：{@link MarketDataUnavailableException} 的 message
     * 会随 503 返回给前端并可能直接显示给用户，
     * 让用户看到「getTopMovers 暂不可用」是没有意义的。
     */
    private static String actionLabel(String action) {
        return switch (action) {
            case "getQuote" -> "个股报价";
            case "getBars" -> "K 线";
            case "getIndices" -> "大盘指数";
            case "search" -> "股票搜索";
            case "getTopMovers" -> "市场榜单";
            default -> action;
        };
    }

    private void markServed(String action, MarketDataProvider provider) {
        servingByAction.put(action, provider.name());
    }

    private boolean isCoolingDown(String cooldownKey) {
        if (cooldownMillis <= 0) {
            return false;
        }
        Long until = cooldownUntil.get(cooldownKey);
        return until != null && until > System.currentTimeMillis();
    }

    private void openCooldown(String cooldownKey, MarketDataProvider provider) {
        if (cooldownMillis <= 0) {
            return;
        }
        cooldownUntil.put(cooldownKey, System.currentTimeMillis() + cooldownMillis);
        log.info("行情源 {} 的 {} 操作进入 {} 秒冷却期，期间请求直接使用下一级数据源",
                provider.name(), cooldownKey.substring(0, cooldownKey.indexOf(':')),
                cooldownMillis / 1000);
    }
}
