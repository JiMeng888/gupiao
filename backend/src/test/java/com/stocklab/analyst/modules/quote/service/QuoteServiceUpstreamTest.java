package com.stocklab.analyst.modules.quote.service;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.MarketPeriod;
import com.stocklab.analyst.market.model.Mover;
import com.stocklab.analyst.market.model.MoverMetric;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.market.model.Sector;
import com.stocklab.analyst.modules.quote.dto.MarketSourceStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link QuoteService} 上游包装方法单测。
 *
 * <p>此前本服务只有 {@code getCarousel} 有测试，{@code getQuote / getBars /
 * topMovers / sectors / sourceStatus} 全都靠 HTTP 冒烟兜着。这些方法的共同特点是
 * <b>失效时不报错</b>：limit 少夹一次、日期过滤少写一个条件、数据源状态少传一个字段，
 * 接口照样返回 200，日志里一行 ERROR 都不会有。所以这里逐条钉住契约。
 *
 * <p>不触网：{@link MarketDataProvider} 是接口，直接用 Mockito 替身。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuoteService 上游包装契约")
class QuoteServiceUpstreamTest {

    @Mock
    private MarketDataProvider marketDataProvider;

    private QuoteService service;

    @BeforeEach
    void setUp() {
        service = new QuoteService(marketDataProvider, new SimpleMeterRegistry(), Runnable::run);
    }

    private static Bar bar(String date, double close) {
        return new Bar(date, close, close, close, close, 1000L, 1000.0, close);
    }

    private static Quote quote(String code) {
        return new Quote(code, "股票" + code, "SH", 10.0, 0.1, 1.0,
                10.0, 10.2, 9.9, 9.9, 1000L, 1.0, 100000.0, 1700000000L);
    }

    @Nested
    @DisplayName("报价与 K 线")
    class QuoteAndBars {

        @Test
        @DisplayName("getQuote 直通数据源，不为失败兜底成空结果")
        void getQuotePassesThrough() {
            when(marketDataProvider.getQuote("600519")).thenReturn(quote("600519"));

            assertThat(service.getQuote("600519").code()).isEqualTo("600519");
        }

        @Test
        @DisplayName("上游取价失败：异常原样上抛，不静默降级成 null")
        void getQuotePropagatesFailure() {
            when(marketDataProvider.getQuote("600519"))
                    .thenThrow(new IllegalStateException("上游不可达"));

            assertThatThrownBy(() -> service.getQuote("600519"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("上游不可达");
        }

        @Test
        @DisplayName("K 线为空：抛 404，而不是返回空列表让前端分不清「没数据」与「故障」")
        void emptyBarsRaiseNotFound() {
            when(marketDataProvider.getBars(anyString(), any(), anyInt())).thenReturn(List.of());

            assertThatThrownBy(() -> service.getBars("600519", MarketPeriod.DAILY, 60, null, null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getCode())
                    .isEqualTo(ErrorCode.QUOTE_NOT_FOUND.name());
        }

        @Test
        @DisplayName("start / end 真实生效（收下的参数不能不用）")
        void barsAreFilteredByDateRange() {
            when(marketDataProvider.getBars("600519", MarketPeriod.DAILY, 60)).thenReturn(List.of(
                    bar("2026-01-05", 10.0),
                    bar("2026-01-06", 11.0),
                    bar("2026-01-07", 12.0),
                    bar("2026-01-08", 13.0)));

            List<Bar> filtered = service.getBars("600519", MarketPeriod.DAILY, 60, "2026-01-06", "2026-01-07");

            assertThat(filtered).extracting(Bar::date)
                    .containsExactly("2026-01-06", "2026-01-07");
        }

        @Test
        @DisplayName("只给 start / 只给 end 时单边生效；两端都不给则原样返回")
        void barsSupportOpenEndedRange() {
            when(marketDataProvider.getBars("600519", MarketPeriod.DAILY, 60)).thenReturn(List.of(
                    bar("2026-01-05", 10.0),
                    bar("2026-01-06", 11.0),
                    bar("2026-01-07", 12.0)));

            assertThat(service.getBars("600519", MarketPeriod.DAILY, 60, "2026-01-06", null))
                    .extracting(Bar::date).containsExactly("2026-01-06", "2026-01-07");
            assertThat(service.getBars("600519", MarketPeriod.DAILY, 60, null, "2026-01-06"))
                    .extracting(Bar::date).containsExactly("2026-01-05", "2026-01-06");
            assertThat(service.getBars("600519", MarketPeriod.DAILY, 60, null, null)).hasSize(3);
        }

        @Test
        @DisplayName("空白日期参数等同未传（前端常传空串）")
        void blankDateRangeIsIgnored() {
            when(marketDataProvider.getBars("600519", MarketPeriod.DAILY, 60))
                    .thenReturn(List.of(bar("2026-01-05", 10.0)));

            assertThat(service.getBars("600519", MarketPeriod.DAILY, 60, "  ", "")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("榜单条数收敛")
    class LimitClamping {

        @Test
        @DisplayName("涨幅榜 limit 被夹到 [1, 20]：既防 0 条也防一次拉全市场")
        void topMoversLimitIsClamped() {
            when(marketDataProvider.getTopMovers(MoverMetric.CHANGE_PCT, false, 20))
                    .thenReturn(List.of(new Mover("600519", "贵州茅台", 1800, 10, 0.5, 1e9)));

            service.topMovers(MoverMetric.CHANGE_PCT, false, 999);
            verify(marketDataProvider).getTopMovers(MoverMetric.CHANGE_PCT, false, 20);

            service.topMovers(MoverMetric.CHANGE_PCT, false, 0);
            verify(marketDataProvider).getTopMovers(MoverMetric.CHANGE_PCT, false, 1);
        }

        @Test
        @DisplayName("板块榜 limit 同样被夹到 [1, 20]")
        void sectorsLimitIsClamped() {
            when(marketDataProvider.getSectors(anyInt()))
                    .thenReturn(List.of(new Sector("BK01", "白酒", 1.2, 10, 3, "贵州茅台", "600519", 2.1)));

            service.sectors(500);
            verify(marketDataProvider).getSectors(20);

            service.sectors(-3);
            verify(marketDataProvider).getSectors(1);
        }

        @Test
        @DisplayName("板块榜不吞上游异常：真实源不可用时必须抛出去映射成 503")
        void sectorsPropagatesUnavailable() {
            when(marketDataProvider.getSectors(anyInt()))
                    .thenThrow(new IllegalStateException("板块数据暂不可用"));

            assertThatThrownBy(() -> service.sectors(10))
                    .as("返回空列表会让前端无法区分「今天没有板块数据」与「上游故障」")
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("数据源状态")
    class SourceStatus {

        @Test
        @DisplayName("如实反映降级状态与按操作的实际来源，不播报主数据源的名字")
        void statusReflectsRealServingSource() {
            when(marketDataProvider.name()).thenReturn("mock");
            when(marketDataProvider.available()).thenReturn(true);
            when(marketDataProvider.degraded()).thenReturn(true);
            when(marketDataProvider.servingByAction())
                    .thenReturn(Map.of("getQuote", "mock", "getIndices", "eastmoney"));

            MarketSourceStatus status = service.sourceStatus();

            assertThat(status.provider()).isEqualTo("mock");
            assertThat(status.degraded()).isTrue();
            assertThat(status.ready()).isTrue();
            assertThat(status.servingByAction())
                    .containsEntry("getIndices", "eastmoney")
                    .containsEntry("getQuote", "mock");
        }

        @Test
        @DisplayName("状态查询不触发任何行情请求（它是诊断接口，不该放大上游压力）")
        void statusDoesNotFetchQuotes() {
            when(marketDataProvider.name()).thenReturn("eastmoney");
            when(marketDataProvider.available()).thenReturn(true);
            when(marketDataProvider.degraded()).thenReturn(false);
            when(marketDataProvider.servingByAction()).thenReturn(Map.of());

            service.sourceStatus();

            verify(marketDataProvider, org.mockito.Mockito.never()).getQuote(anyString());
        }
    }
}
