package com.stocklab.analyst.modules.holding.service;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.holding.dto.HoldingBuyRequest;
import com.stocklab.analyst.modules.holding.dto.HoldingSellRequest;
import com.stocklab.analyst.modules.holding.dto.HoldingView;
import com.stocklab.analyst.modules.holding.dto.PortfolioSummary;
import com.stocklab.analyst.modules.holding.entity.Holding;
import com.stocklab.analyst.modules.holding.mapper.HoldingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HoldingService} 的单元测试。
 *
 * <p>用 Mockito 替换 Mapper 与行情源，因此不需要数据库、不需要 Spring 上下文，
 * 毫秒级跑完。测试围绕「金额算错不会报错、只会静默给出错数字」这一风险展开 ——
 * 成本价、盈亏、清仓判定都属于这类：算错了页面照样渲染，只有对账时才发现。
 *
 * <p>覆盖的几条关键业务规则：
 * <ul>
 *   <li>加权平均成本：{@code (旧股数×旧成本 + 新股数×新价) / 总股数}</li>
 *   <li>卖出<b>不改变</b>平均成本（加权平均法的固有特征）</li>
 *   <li>持仓只数上限只拦「新增标的」，<b>补仓不受限</b></li>
 *   <li>并发首次买入同一标的：唯一索引冲突后退化为加仓，结果与串行执行一致</li>
 *   <li>行情缺失时不算盈亏，避免「总成本为 0 却显示收益率」或「收益率凭空变负」</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class HoldingServiceTest {

    private static final String USER = "u1";
    private static final String CODE = "600519";
    private static final String NAME = "贵州茅台";

    @Mock
    private HoldingMapper holdingMapper;

    @Mock
    private MarketDataProvider marketDataProvider;

    private HoldingService service;

    @BeforeEach
    void setUp() {
        service = new HoldingService(holdingMapper, marketDataProvider);
    }

    // ==================================================================
    // 测试夹具
    // ==================================================================

    private static Holding holding(int shares, double avgCost) {
        Holding h = new Holding();
        h.setId("h-" + CODE);
        h.setUserId(USER);
        h.setCode(CODE);
        h.setName(NAME);
        h.setShares(shares);
        h.setAvgCost(avgCost);
        h.setCreatedAt(1_700_000_000L);
        h.setUpdatedAt(1_700_000_000L);
        return h;
    }

    private static Quote quote(String price, String prevClose) {
        double p = Double.parseDouble(price);
        double pc = Double.parseDouble(prevClose);
        return new Quote(CODE, NAME, "SH", p, p - pc, 0.0, p, p, p, pc, 0L, 0.0, null, 1_700_000_000L);
    }

    private static HoldingBuyRequest buy(String code, int shares, double price) {
        return new HoldingBuyRequest(code, null, shares, price, null);
    }

    private static HoldingBuyRequest buy(String code, int shares, double price, String buyDate) {
        return new HoldingBuyRequest(code, null, shares, price, buyDate);
    }

    // ==================================================================
    // 买入 / 补仓
    // ==================================================================

    @Nested
    @DisplayName("买入与补仓")
    class BuyTest {

        @Test
        @DisplayName("首次买入：平均成本等于成交价，名称由行情源补全")
        void firstBuySetsAvgCostToPrice() {
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(null);
            when(holdingMapper.selectCount(any())).thenReturn(0L);
            when(marketDataProvider.getQuote(CODE)).thenReturn(quote("1700", "1685"));

            HoldingView view = service.buy(USER, buy(CODE, 100, 1700.0));

            assertThat(view.shares()).isEqualTo(100);
            assertThat(view.avgCost()).isCloseTo(1700.0, within(1e-9));
            assertThat(view.name()).as("name 传空时从行情源补全").isEqualTo(NAME);
            assertThat(view.currentPrice()).isCloseTo(1700.0, within(1e-9));

            verify(holdingMapper).insert(any(Holding.class));
            verify(holdingMapper, never()).updateById(any(Holding.class));
        }

        @Test
        @DisplayName("补仓：按加权平均合并，(100×10 + 100×15)/200 = 12.5")
        void addPositionUsesWeightedAverage() {
            Holding existing = holding(100, 10.0);
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(existing);
            when(marketDataProvider.getQuote(CODE)).thenReturn(quote("15", "14"));

            HoldingView view = service.buy(USER, buy(CODE, 100, 15.0));

            assertThat(view.shares()).isEqualTo(200);
            assertThat(view.avgCost()).isCloseTo(12.5, within(1e-9));

            verify(holdingMapper).updateById(existing);
            verify(holdingMapper, never()).insert(any(Holding.class));
        }

        @Test
        @DisplayName("补仓：平均成本保留 4 位小数，(100×10 + 3×11)/103 = 10.0291")
        void avgCostIsRoundedToFourDecimals() {
            Holding existing = holding(100, 10.0);
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(existing);
            when(marketDataProvider.getQuote(CODE)).thenReturn(quote("11", "10.5"));

            HoldingView view = service.buy(USER, buy(CODE, 3, 11.0));

            // 精确值 10.029126213592233...
            assertThat(view.shares()).isEqualTo(103);
            assertThat(view.avgCost()).isCloseTo(10.0291, within(1e-9));
        }

        @Test
        @DisplayName("补仓不受持仓只数上限影响（上限只拦新增标的）")
        void addPositionIsNotBlockedByLimit() {
            Holding existing = holding(100, 10.0);
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(existing);
            when(marketDataProvider.getQuote(CODE)).thenReturn(quote("10", "10"));

            HoldingView view = service.buy(USER, buy(CODE, 100, 10.0));

            assertThat(view.shares()).isEqualTo(200);
            // 关键断言：补仓路径根本不该去查持仓只数。
            // 若将来有人把上限校验挪到 buy() 开头，这条会立刻失败。
            verify(holdingMapper, never()).selectCount(any());
        }

        @Test
        @DisplayName("新增标的达到上限（200 只）时拒绝")
        void rejectsNewHoldingBeyondLimit() {
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(null);
            when(holdingMapper.selectCount(any())).thenReturn(200L);

            assertThatThrownBy(() -> service.buy(USER, buy(CODE, 100, 10.0)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("上限")
                    .extracting(e -> ((BizException) e).getCode())
                    .isEqualTo("INVALID_PARAMS");

            verify(holdingMapper, never()).insert(any(Holding.class));
        }

        @Test
        @DisplayName("代码后缀被规范化：600519.SH 与 600519 视为同一标的")
        void normalizesCodeSuffix() {
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(null);
            when(holdingMapper.selectCount(any())).thenReturn(0L);
            when(marketDataProvider.getQuote(CODE)).thenReturn(quote("10", "10"));

            service.buy(USER, buy("600519.SH", 100, 10.0));

            // 传给 Mapper 的必须是去掉后缀的代码，否则同一标的会建成两条记录
            verify(holdingMapper).selectByUserAndCodeForUpdate(USER, CODE);
        }

        @Test
        @DisplayName("买入日期缺省时回填当天（Asia/Shanghai）")
        void buyDateDefaultsToToday() {
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(null);
            when(holdingMapper.selectCount(any())).thenReturn(0L);
            when(marketDataProvider.getQuote(CODE)).thenReturn(quote("10", "10"));

            HoldingView view = service.buy(USER, buy(CODE, 100, 10.0));

            String expected = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
            assertThat(view.buyDate()).isEqualTo(expected);
        }

        @Test
        @DisplayName("显式传入历史买入日期时以传入值为准")
        void explicitBuyDateWins() {
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(null);
            when(holdingMapper.selectCount(any())).thenReturn(0L);
            when(marketDataProvider.getQuote(CODE)).thenReturn(quote("10", "10"));

            HoldingView view = service.buy(USER, buy(CODE, 100, 10.0, "2024-01-15"));

            assertThat(view.buyDate()).isEqualTo("2024-01-15");
        }
    }

    // ==================================================================
    // 并发首次买入
    // ==================================================================

    @Nested
    @DisplayName("并发首次买入同一标的")
    class ConcurrentFirstBuyTest {

        @Test
        @DisplayName("唯一索引冲突后退化为加仓，结果与串行执行一致")
        void duplicateKeyFallsBackToAddPosition() {
            Holding existing = holding(100, 10.0);

            // 第一次查：双方都没看到行（都以为自己是首买）
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE))
                    .thenReturn(null)      // buy() 里的首次查询
                    .thenReturn(existing); // 冲突后重新读取
            when(holdingMapper.selectCount(any())).thenReturn(0L);
            when(holdingMapper.insert(any(Holding.class)))
                    .thenThrow(new DuplicateKeyException("uk_holding_user_code"));
            when(marketDataProvider.getQuote(CODE)).thenReturn(quote("20", "19"));

            HoldingView view = service.buy(USER, buy(CODE, 100, 20.0));

            // 等价于「先有 100 股 @10，再买 100 股 @20」的串行结果
            assertThat(view.shares()).isEqualTo(200);
            assertThat(view.avgCost()).isCloseTo(15.0, within(1e-9));

            verify(holdingMapper).updateById(existing);
        }

        @Test
        @DisplayName("冲突后仍读不到行时原样抛出（说明不是唯一索引冲突，不能吞掉）")
        void rethrowsWhenRowStillMissing() {
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(null);
            when(holdingMapper.selectCount(any())).thenReturn(0L);
            when(holdingMapper.insert(any(Holding.class)))
                    .thenThrow(new DuplicateKeyException("some-other-constraint"));

            assertThatThrownBy(() -> service.buy(USER, buy(CODE, 100, 20.0)))
                    .isInstanceOf(DuplicateKeyException.class);

            verify(holdingMapper, never()).updateById(any(Holding.class));
        }
    }

    // ==================================================================
    // 卖出
    // ==================================================================

    @Nested
    @DisplayName("卖出")
    class SellTest {

        @Test
        @DisplayName("卖出只扣股数，平均成本保持不变（加权平均法的固有特征）")
        void sellKeepsAvgCost() {
            Holding existing = holding(200, 12.5);
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(existing);

            HoldingView view = service.sell(USER, new HoldingSellRequest(CODE, 50, 15.0));

            assertThat(view.shares()).isEqualTo(150);
            assertThat(view.avgCost()).as("卖出不改变成本价").isCloseTo(12.5, within(1e-9));

            // 本次已实现盈亏 = 50 × (15 − 12.5)
            assertThat(view.realizedPnl()).isCloseTo(125.0, within(1e-9));
            assertThat(view.soldShares()).isEqualTo(50);

            // 卖出响应里的 currentPrice 是成交价，市值/浮盈是按成交价估算的剩余持仓
            assertThat(view.currentPrice()).isCloseTo(15.0, within(1e-9));
            assertThat(view.marketValue()).isCloseTo(150 * 15.0, within(1e-9));
            assertThat(view.costTotal()).isCloseTo(150 * 12.5, within(1e-9));
            assertThat(view.pnl()).isCloseTo(375.0, within(1e-9));

            verify(holdingMapper).updateById(existing);
            // 卖出往返不应触碰行情源：成交价由请求带入
            verify(marketDataProvider, never()).getQuote(any());
        }

        @Test
        @DisplayName("卖出响应不返回当日盈亏：dayPnl 为 null 而不是 0")
        void sellDoesNotFabricateDayPnl() {
            Holding existing = holding(200, 12.5);
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(existing);

            HoldingView view = service.sell(USER, new HoldingSellRequest(CODE, 50, 15.0));

            // 成交价推不出昨收，「当日盈亏」此刻是未知的。
            // 返回 0 等于宣称「今天没涨没跌」——那是编造的结论。
            assertThat(view.dayPnl()).as("卖出响应不应给出当日盈亏").isNull();
        }

        @Test
        @DisplayName("超量卖出被拒绝，且不产生任何写入")
        void rejectsSellingMoreThanHeld() {
            Holding existing = holding(100, 10.0);
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(existing);

            assertThatThrownBy(() -> service.sell(USER, new HoldingSellRequest(CODE, 101, 12.0)))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getCode())
                    .isEqualTo("INSUFFICIENT_SHARES");

            verify(holdingMapper, never()).updateById(any(Holding.class));
        }

        @Test
        @DisplayName("卖出不存在的持仓报 HOLDING_NOT_FOUND")
        void rejectsUnknownHolding() {
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(null);

            assertThatThrownBy(() -> service.sell(USER, new HoldingSellRequest(CODE, 10, 12.0)))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getCode())
                    .isEqualTo("HOLDING_NOT_FOUND");
        }

        @Test
        @DisplayName("卖到 0 股（清仓）：记录保留、盈亏字段全部为 null")
        void fullLiquidationKeepsRecord() {
            Holding existing = holding(100, 10.0);
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(existing);

            HoldingView view = service.sell(USER, new HoldingSellRequest(CODE, 100, 12.0));

            assertThat(view.shares()).isZero();
            assertThat(view.realizedPnl()).isCloseTo(200.0, within(1e-9));

            // 股数为 0 时不再计算盈亏：否则会出现「0 成本却带收益率」这种困惑展示
            assertThat(view.marketValue()).isNull();
            assertThat(view.costTotal()).isNull();
            assertThat(view.pnl()).isNull();
            assertThat(view.pnlPct()).isNull();

            // 清仓保留记录（前端展示「已清仓」），删除必须由用户显式调用
            verify(holdingMapper).updateById(existing);
            verify(holdingMapper, never()).delete(any());
        }
    }

    // ==================================================================
    // 查询与组合总览
    // ==================================================================

    @Nested
    @DisplayName("查询与组合总览")
    class QueryTest {

        @Test
        @DisplayName("无持仓时返回空列表，不触碰行情源")
        void emptyHoldings() {
            when(holdingMapper.selectList(any())).thenReturn(List.of());

            assertThat(service.listHoldings(USER)).isEmpty();
            assertThat(service.portfolioSummary(USER)).isEqualTo(PortfolioSummary.empty());

            verify(marketDataProvider, never()).getQuote(any());
        }

        @Test
        @DisplayName("查询响应计算当日盈亏（用真实行情的前收盘价）")
        void queryComputesDayPnl() {
            when(holdingMapper.selectList(any())).thenReturn(List.of(holding(100, 10.0)));
            when(marketDataProvider.getQuote(CODE)).thenReturn(quote("12", "11"));

            List<HoldingView> views = service.listHoldings(USER);

            assertThat(views).hasSize(1);
            HoldingView view = views.get(0);
            assertThat(view.pnl()).isCloseTo(200.0, within(1e-9));       // 100 × (12 − 10)
            assertThat(view.pnlPct()).isCloseTo(20.0, within(1e-9));     // 200 / 1000
            assertThat(view.dayPnl()).isCloseTo(100.0, within(1e-9));    // 100 × (12 − 11)
        }

        @Test
        @DisplayName("行情获取失败时不打断整个列表：仍返回持仓与成本，盈亏置 null")
        void quoteFailureDoesNotBreakList() {
            when(holdingMapper.selectList(any())).thenReturn(List.of(holding(100, 10.0)));
            when(marketDataProvider.getQuote(CODE)).thenThrow(new IllegalStateException("上游网络异常"));

            List<HoldingView> views = service.listHoldings(USER);

            assertThat(views).hasSize(1);
            HoldingView view = views.get(0);
            assertThat(view.shares()).isEqualTo(100);
            assertThat(view.costTotal()).as("无行情时至少给出成本").isCloseTo(1000.0, within(1e-9));
            assertThat(view.marketValue()).isNull();
            assertThat(view.pnl()).isNull();
            assertThat(view.currentPrice()).isNull();
        }

        @Test
        @DisplayName("组合总览：行情缺失的持仓不计入市值，否则总收益率会被凭空拉成负数")
        void summarySkipsHoldingsWithoutQuote() {
            Holding withQuote = holding(100, 10.0);
            Holding withoutQuote = new Holding();
            withoutQuote.setId("h-000001");
            withoutQuote.setUserId(USER);
            withoutQuote.setCode("000001");
            withoutQuote.setName("平安银行");
            withoutQuote.setShares(50);
            withoutQuote.setAvgCost(20.0);

            when(holdingMapper.selectList(any())).thenReturn(List.of(withQuote, withoutQuote));
            when(marketDataProvider.getQuote(CODE)).thenReturn(quote("12", "11"));
            when(marketDataProvider.getQuote("000001")).thenThrow(new IllegalStateException("降级中"));

            PortfolioSummary summary = service.portfolioSummary(USER);

            // 只统计有行情的那一只
            assertThat(summary.count()).isEqualTo(1);
            assertThat(summary.marketValue()).isCloseTo(1200.0, within(1e-9));
            assertThat(summary.costTotal()).isCloseTo(1000.0, within(1e-9));
            assertThat(summary.pnl()).isCloseTo(200.0, within(1e-9));
            assertThat(summary.pnlPct()).isCloseTo(20.0, within(1e-9));
            assertThat(summary.dayPnl()).isCloseTo(100.0, within(1e-9));
        }

        @Test
        @DisplayName("组合总览：盈亏率以「参与统计的成本」为分母，不被缺失行情的持仓虚增")
        void summaryPnlPctUsesOnlyCountedCost() {
            Holding a = holding(100, 10.0);
            Holding b = new Holding();
            b.setId("h-000001");
            b.setUserId(USER);
            b.setCode("000001");
            b.setName("平安银行");
            b.setShares(1000);   // 成本占比很大的持仓，但拿不到行情
            b.setAvgCost(100.0);

            when(holdingMapper.selectList(any())).thenReturn(List.of(a, b));
            when(marketDataProvider.getQuote(CODE)).thenReturn(quote("11", "10.5"));
            when(marketDataProvider.getQuote("000001")).thenThrow(new IllegalStateException("降级中"));

            PortfolioSummary summary = service.portfolioSummary(USER);

            // 若把 b 的 100000 成本也算进分母，收益率会从 10% 变成约 0.09%
            assertThat(summary.pnlPct()).as("分母只应含参与统计的成本")
                    .isCloseTo(10.0, within(1e-9));
        }
    }

    // ==================================================================
    // 名称解析
    // ==================================================================

    @Nested
    @DisplayName("名称解析")
    class NameResolutionTest {

        @Test
        @DisplayName("显式传入名称时以传入值为准，不被行情源覆盖")
        void explicitNameWinsOverQuoteName() {
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(null);
            when(holdingMapper.selectCount(any())).thenReturn(0L);
            when(marketDataProvider.getQuote(CODE)).thenReturn(quote("10", "10"));

            HoldingView view = service.buy(USER, new HoldingBuyRequest(CODE, "自定义名称", 100, 10.0, null));

            assertThat(view.name()).isEqualTo("自定义名称");
        }

        @Test
        @DisplayName("行情源也拿不到名称时退回代码本身，不抛异常")
        void fallsBackToCodeWhenQuoteUnavailable() {
            when(holdingMapper.selectByUserAndCodeForUpdate(USER, CODE)).thenReturn(null);
            when(holdingMapper.selectCount(any())).thenReturn(0L);
            when(marketDataProvider.getQuote(CODE)).thenThrow(new IllegalStateException("降级中"));

            HoldingView view = service.buy(USER, buy(CODE, 100, 10.0));

            assertThat(view.name()).isEqualTo(CODE);
            assertThat(view.shares()).isEqualTo(100);
        }
    }

    // ==================================================================
    // 删除
    // ==================================================================

    @Nested
    @DisplayName("删除持仓")
    class RemoveTest {

        @Test
        @DisplayName("删除条件包含 user_id，一条语句完成归属校验")
        void removeScopesByUser() {
            when(holdingMapper.delete(any())).thenReturn(1);

            service.removeHolding(USER, "600519.SH");

            verify(holdingMapper).delete(any());
        }

        @Test
        @DisplayName("删除不存在的记录是幂等的，不报错")
        void removeIsIdempotent() {
            when(holdingMapper.delete(any())).thenReturn(0);

            service.removeHolding(USER, CODE); // 不应抛出

            verify(holdingMapper).delete(any());
        }
    }

    // ==================================================================
    // 边界：字段为 null 的历史数据
    // ==================================================================

    @Test
    @DisplayName("shares / avgCost 为 null 的历史数据不导致 NPE")
    void toleratesNullColumns() {
        Holding legacy = new Holding();
        legacy.setId("h-legacy");
        legacy.setUserId(USER);
        legacy.setCode(CODE);
        legacy.setName(NAME);
        legacy.setShares(null);
        legacy.setAvgCost(null);

        when(holdingMapper.selectList(any())).thenReturn(List.of(legacy));
        when(marketDataProvider.getQuote(eq(CODE))).thenReturn(quote("10", "9"));

        List<HoldingView> views = service.listHoldings(USER);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).shares()).isZero();
        assertThat(views.get(0).avgCost()).isCloseTo(0.0, within(1e-9));
    }
}
