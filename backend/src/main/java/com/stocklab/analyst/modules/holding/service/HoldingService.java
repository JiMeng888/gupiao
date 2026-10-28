package com.stocklab.analyst.modules.holding.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.holding.dto.HoldingBuyRequest;
import com.stocklab.analyst.modules.holding.dto.HoldingSellRequest;
import com.stocklab.analyst.modules.holding.dto.HoldingView;
import com.stocklab.analyst.modules.holding.dto.PortfolioSummary;
import com.stocklab.analyst.modules.holding.entity.Holding;
import com.stocklab.analyst.modules.holding.mapper.HoldingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 持仓服务：买入 / 卖出 / 盈亏计算。
 *
 * <p>成本核算规则（加权平均）：
 * <pre>
 *   已有持仓：new_avg = (old_shares × old_cost + new_shares × new_price) / (old_shares + new_shares)
 *   新建持仓：avg = new_price
 *   卖出：    shares 扣减，avg_cost 保持不变
 * </pre>
 * 卖出不改变成本价，是「加权平均法」的固有特征——已实现盈亏体现在
 * 「卖出价与当时平均成本的差」上，而持仓成本只随买入变动。
 *
 * <p><b>并发安全</b>：买入与卖出都通过 {@code FOR UPDATE} 行锁读取后修改，
 * 见 {@link HoldingMapper#selectByUserAndCodeForUpdate}。
 * 原 FastAPI 版本是「先 SELECT 再写回」，存在丢失更新问题，这里做了修正。
 *
 * <p>行锁只能锁住已存在的行，因此「首次买入同一只股票」的竞态还需
 * {@code uk_holding_user_code (user_id, code)} 唯一索引兜底，
 * 应用层捕获冲突后退化为加仓。详见
 * {@link #insertNewHolding(String, String, String, int, double, String)}。
 */
@Service
public class HoldingService {

    private static final Logger log = LoggerFactory.getLogger(HoldingService.class);

    /** 单用户持仓只数上限。 */
    private static final int MAX_HOLDINGS_PER_USER = 200;

    private final HoldingMapper holdingMapper;
    private final MarketDataProvider marketDataProvider;

    public HoldingService(HoldingMapper holdingMapper, MarketDataProvider marketDataProvider) {
        this.holdingMapper = holdingMapper;
        this.marketDataProvider = marketDataProvider;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 列出全部持仓并附带实时盈亏。
     *
     * <p>用只读事务：保证这一批查询看到同一份数据快照，
     * 避免查询过程中其它写入导致「总览数字与明细对不上」。
     */
    @Transactional(readOnly = true)
    public List<HoldingView> listHoldings(String userId) {
        List<Holding> holdings = holdingMapper.selectList(Wrappers.<Holding>lambdaQuery()
                .eq(Holding::getUserId, userId)
                .orderByDesc(Holding::getUpdatedAt));
        if (holdings.isEmpty()) {
            return List.of();
        }

        Map<String, Quote> quotes = fetchQuotes(holdings);
        List<HoldingView> views = new ArrayList<>(holdings.size());
        for (Holding holding : holdings) {
            views.add(toView(holding, quotes.get(holding.getCode())));
        }
        return views;
    }

    /**
     * 组合总览。
     *
     * <p>基于 {@link #listHoldings} 的结果聚合，而不是再发一轮行情请求——
     * 后者会让盈亏数字与实际明细之间出现时间差（两次取价不同），
     * 用户会看到「总结与明细差几块钱」这种说不清的问题。
     */
    @Transactional(readOnly = true)
    public PortfolioSummary portfolioSummary(String userId) {
        List<HoldingView> holdings = listHoldings(userId);
        if (holdings.isEmpty()) {
            return PortfolioSummary.empty();
        }

        double marketValue = 0;
        double costTotal = 0;
        double dayPnl = 0;
        int counted = 0;
        int totalCount = 0;
        for (HoldingView holding : holdings) {
            // 持仓只数只取决于股数，与行情是否取到无关：
            // 行情整体不可用时它仍然是真实只数，调用方据此区分「空仓」与「行情暂不可用」
            if (holding.shares() > 0) {
                totalCount++;
            }
            if (holding.marketValue() == null) {
                // 行情缺失的持仓不计入市值，否则会被当成 0 市值，
                // 让总收益率凭空变负
                continue;
            }
            marketValue += holding.marketValue();
            costTotal += holding.costTotal() == null ? 0 : holding.costTotal();
            dayPnl += holding.dayPnl() == null ? 0 : holding.dayPnl();
            counted++;
        }
        double pnl = marketValue - costTotal;
        double pnlPct = costTotal == 0 ? 0 : pnl / costTotal * 100;

        return new PortfolioSummary(counted, totalCount, round2(marketValue), round2(costTotal),
                round2(pnl), round2(pnlPct), round2(dayPnl));
    }

    // ==================================================================
    // 买入 / 卖出
    // ==================================================================

    /**
     * 买入或补仓。
     *
     * <p>事务隔离级别用默认的 READ_COMMITTED（MySQL 默认）即可：
     * 正确性由 {@code FOR UPDATE} 行锁保证，不需要把整个事务提升到
     * REPEATABLE_READ 去承担额外的间隙锁开销。
     */
    @Transactional
    public HoldingView buy(String userId, HoldingBuyRequest request) {
        String code = normalizeCode(request.code());
        int shares = request.shares();
        double price = request.price();
        String buyDate = resolveBuyDate(request.buyDate());

        Holding holding = holdingMapper.selectByUserAndCodeForUpdate(userId, code);
        if (holding == null) {
            holding = insertNewHolding(userId, code, request.name(), shares, price, buyDate);
        } else {
            applyBuy(holding, shares, price, request.name(), code);
            holdingMapper.updateById(holding);
        }

        log.info("买入 userId={} code={} shares={} price={} buyDate={}",
                userId, code, shares, price, buyDate);
        return toView(holding, safeQuote(code));
    }

    /**
     * 新建持仓记录，并在并发冲突时退化为加仓。
     *
     * <h2>为什么需要处理 {@link DuplicateKeyException}</h2>
     * {@code SELECT ... FOR UPDATE} 只能锁住<b>已存在</b>的行。两个请求同时
     * 首次买入同一只股票时，双方都查不到行、也都没有可锁的对象，于是都会走到
     * INSERT。没有唯一约束时结果是同一只股票出现两行记录——之后
     * {@code selectOne} 式的读取会直接抛 {@code TooManyResultsException}，
     * 持仓数据从此不可用。
     *
     * <p>因此 {@code holdings} 表上必须有 {@code uk_holding_user_code (user_id, code)}
     * 唯一索引兜底，应用层再捕获冲突、改为「读取已存在行 → 按加权平均加仓」，
     * 其最终效果与两个请求串行执行完全一致。
     *
     * <p><b>注意这里对数据库的隐含要求</b>：MySQL 与 H2 在唯一键冲突后
     * 事务仍可继续使用，所以「捕获异常后继续执行」是可行的；
     * PostgreSQL 会把整个事务置为 aborted，任何后续语句都会报
     * {@code current transaction is aborted}，那种环境下必须改用
     * {@code INSERT ... ON CONFLICT DO UPDATE} 之类的单语句方案。
     * 本项目只支持 MySQL / H2，故沿用当前写法。
     */
    private Holding insertNewHolding(String userId, String code, String name,
                                    int shares, double price, String buyDate) {
        // 仅新增标的时校验持仓只数上限：补仓不应被上限拦住
        long held = holdingMapper.selectCount(Wrappers.<Holding>lambdaQuery()
                .eq(Holding::getUserId, userId));
        if (held >= MAX_HOLDINGS_PER_USER) {
            throw new BizException(ErrorCode.INVALID_PARAMS,
                    "持仓数量已达上限（" + MAX_HOLDINGS_PER_USER + " 只）");
        }

        Holding holding = new Holding();
        holding.setUserId(userId);
        holding.setCode(code);
        holding.setName(resolveName(name, code));
        holding.setShares(shares);
        holding.setAvgCost(price);
        holding.setBuyDate(buyDate);
        try {
            holdingMapper.insert(holding);
            return holding;
        } catch (DuplicateKeyException ex) {
            Holding existing = holdingMapper.selectByUserAndCodeForUpdate(userId, code);
            if (existing == null) {
                // 冲突了却又读不到行，说明不是唯一索引冲突，原样抛出
                throw ex;
            }
            log.info("并发买入同一标的，转为加仓 userId={} code={}", userId, code);
            applyBuy(existing, shares, price, name, code);
            holdingMapper.updateById(existing);
            return existing;
        }
    }

    /** 加权平均成本递增。抽出来是为了让「新建冲突后的加仓」与正常加仓走同一段逻辑。 */
    private void applyBuy(Holding holding, int shares, double price, String name, String code) {
        int oldShares = holding.getShares() == null ? 0 : holding.getShares();
        double oldCost = holding.getAvgCost() == null ? 0 : holding.getAvgCost();
        int totalShares = oldShares + shares;
        if (totalShares <= 0) {
            throw new BizException(ErrorCode.INVALID_PARAMS, "持仓数量无效");
        }
        double newAvgCost = (oldShares * oldCost + shares * price) / totalShares;
        holding.setShares(totalShares);
        holding.setAvgCost(round4(newAvgCost));
        if (holding.getName() == null || holding.getName().isBlank()) {
            holding.setName(resolveName(name, code));
        }
    }

    /**
     * 卖出：扣减股数，成本价不变。
     *
     * <p>允许卖到 0 股（清仓），此时记录保留而不是删除——
     * 前端可以展示「已清仓」状态并保留历史成本，
     * 用户想彻底移除需要显式调用删除接口。
     *
     * <p>返回体中的盈亏按<b>成交价</b>而不是当前市价计算：用户在这一刻关心的是
     * 「这笔卖出赚了多少」，市价与他刚提交的成交价无关。
     * 因此 {@code dayPnl} 置为 {@code null}（用成交价推不出当日盈亏），
     * 改为返回只在这里有意义的 {@code realizedPnl}（本次卖出的已实现盈亏）。
     * 详见 {@link HoldingView} 的字段说明。
     */
    @Transactional
    public HoldingView sell(String userId, HoldingSellRequest request) {
        String code = normalizeCode(request.code());
        Holding holding = holdingMapper.selectByUserAndCodeForUpdate(userId, code);
        if (holding == null) {
            throw new BizException(ErrorCode.HOLDING_NOT_FOUND);
        }

        int held = holding.getShares() == null ? 0 : holding.getShares();
        if (request.shares() > held) {
            throw new BizException(ErrorCode.INSUFFICIENT_SHARES,
                    "卖出 " + request.shares() + " 股超过持有 " + held + " 股");
        }

        double avgCost = holding.getAvgCost() == null ? 0 : holding.getAvgCost();
        double realizedPnl = request.shares() * (request.price() - avgCost);

        holding.setShares(held - request.shares());
        holdingMapper.updateById(holding);

        log.info("卖出 userId={} code={} shares={} price={} 剩余={} 已实现盈亏={}",
                userId, code, request.shares(), request.price(), holding.getShares(),
                round2(realizedPnl));

        // 按成交价构造一个仅供估值展示的行情代理：
        // currentPrice 用成交价，使前端看到的是「按本次成交价估算的剩余持仓盈亏」
        Quote quote = new Quote(holding.getCode(), holding.getName(), "", request.price(),
                request.price() - avgCost, 0, request.price(), request.price(),
                request.price(), request.price(), 0, 0, null,
                System.currentTimeMillis() / 1000);

        // 最后一个参数传 false：卖出场景不计算当日盈亏。
        // 成交价推不出昨收，任何数值都是编造的（详见 HoldingView 的字段说明）。
        return toView(holding, quote, round2(realizedPnl), request.shares(), false);
    }

    /**
     * 彻底删除持仓记录。
     *
     * <p>删除条件含 {@code user_id}，一条语句完成归属校验。
     */
    @Transactional
    public void removeHolding(String userId, String code) {
        int affected = holdingMapper.delete(Wrappers.<Holding>lambdaQuery()
                .eq(Holding::getUserId, userId)
                .eq(Holding::getCode, normalizeCode(code)));
        if (affected == 0) {
            // 幂等：删不存在的记录不报错，前端重复点击不会弹错误
            log.debug("删除持仓无匹配记录 userId={} code={}", userId, code);
        }
    }

    // ==================================================================

    private Map<String, Quote> fetchQuotes(List<Holding> holdings) {
        Map<String, Quote> quotes = new HashMap<>();
        for (Holding holding : holdings) {
            quotes.computeIfAbsent(holding.getCode(), code -> safeQuote(code));
        }
        return quotes;
    }

    /** 取价失败返回 null，不打断整个列表。 */
    private Quote safeQuote(String code) {
        try {
            return marketDataProvider.getQuote(code);
        } catch (RuntimeException ex) {
            log.debug("持仓取价失败 code={}：{}", code, ex.getMessage());
            return null;
        }
    }

    /**
     * 组装查询视图。
     *
     * <p>股数为 0 时不算盈亏（成本归零、市值归零，收益率无意义），
     * 避免出现「0 成本却有收益率」这种让人困惑的展示。
     */
    private HoldingView toView(Holding holding, Quote quote) {
        return toView(holding, quote, null, null, true);
    }

    /**
     * 组装视图并计算盈亏。
     *
     * @param realizedPnl   本次卖出的已实现盈亏；查询 / 买入场景传 {@code null}
     * @param soldShares    本次卖出股数；查询 / 买入场景传 {@code null}
     * @param computeDayPnl 是否计算当日盈亏。
     *                      <b>卖出场景必须传 {@code false}</b>——成交价推不出昨收，
     *                      「当日盈亏」在那一刻是未知的，应返回 {@code null} 而不是 {@code 0}。
     *                      （早先的实现靠「把成交价同时当作前收」让结果恒为 0，
     *                      这在语义上是编造了一个「今天没涨没跌」的假结论。）
     */
    private HoldingView toView(Holding holding, Quote quote, Double realizedPnl,
                               Integer soldShares, boolean computeDayPnl) {
        int shares = holding.getShares() == null ? 0 : holding.getShares();
        double avgCost = holding.getAvgCost() == null ? 0 : holding.getAvgCost();

        Double currentPrice = null;
        Double marketValue = null;
        Double costTotal = null;
        Double pnl = null;
        Double pnlPct = null;
        Double dayPnl = null;

        if (quote != null && shares > 0) {
            currentPrice = quote.price();
            costTotal = shares * avgCost;
            marketValue = shares * currentPrice;
            pnl = marketValue - costTotal;
            pnlPct = costTotal == 0 ? 0 : pnl / costTotal * 100;
            // 只在使用真实行情时才算当日盈亏；卖出响应的 quote 是成交价代理，不能用来算
            dayPnl = computeDayPnl ? dayPnlOf(shares, currentPrice, quote.prevClose()) : null;
        } else if (shares > 0) {
            // 无行情时至少给出成本，前端可以显示「--」占位
            costTotal = shares * avgCost;
        }

        return new HoldingView(
                holding.getId(),
                holding.getCode(),
                holding.getName(),
                shares,
                avgCost,
                holding.getBuyDate(),
                holding.getCreatedAt(),
                holding.getUpdatedAt(),
                currentPrice,
                round2OrNull(marketValue),
                round2OrNull(costTotal),
                round2OrNull(pnl),
                round2OrNull(pnlPct),
                round2OrNull(dayPnl),
                realizedPnl,
                soldShares);
    }

    /**
     * 当日盈亏 = 股数 ×（现价 − 昨收）。
     *
     * <h2>为什么昨收为 0 时返回 null 而不是照算</h2>
     * 部分数据源（如腾讯）在拿不到昨收字段时会把它解析成 0 而不是报错——
     * 0 在这里是「缺失」的哨兵值，不是一个真实价格。若照代公式，
     * 结果会变成 {@code 股数 × 现价}：以 1000 股 / 现价 12 元为例，
     * 本该是几百元的当日盈亏会被放大成 12000 元，而且接口照常返回 200，
     * 日志一行错误都没有——这正是「给出错误的数字」比报错更糟的情形。
     *
     * <p>因此昨收缺失（{@code <= 0}）时返回 {@code null}，前端渲染 {@code --}，
     * 语义是「此刻不知道」，而不是「今天涨了 12 块」。
     */
    private static Double dayPnlOf(int shares, double currentPrice, double prevClose) {
        if (prevClose <= 0) {
            return null;
        }
        return shares * (currentPrice - prevClose);
    }

    private String resolveName(String provided, String code) {
        if (provided != null && !provided.isBlank()) {
            return provided.trim();
        }
        Quote quote = safeQuote(code);
        return quote != null ? quote.name() : code;
    }

    /**
     * 买入日期：请求未传或为空时用当天（Asia/Shanghai）。
     *
     * <p>格式已在 DTO 层用正则校验为 {@code YYYY-MM-DD}，
     * 这里只负责「缺省补当天」，不再重复解析。
     */
    private String resolveBuyDate(String provided) {
        if (provided != null && !provided.isBlank()) {
            return provided.trim();
        }
        return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
    }

    private static String normalizeCode(String raw) {
        String trimmed = raw.trim();
        int dot = trimmed.indexOf('.');
        return dot > 0 ? trimmed.substring(0, dot) : trimmed;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static Double round2OrNull(Double value) {
        return value == null ? null : round2(value);
    }
}
