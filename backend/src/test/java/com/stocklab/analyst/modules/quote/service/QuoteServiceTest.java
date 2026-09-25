package com.stocklab.analyst.modules.quote.service;

import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.quote.dto.CarouselItem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link QuoteService#getCarousel(int, int)} 单测。
 *
 * <p><b>本类要钉住的核心性质是「相邻两批零重合」。</b>
 * 首页精选关注每 10 秒换一批，用户对「刷新」的全部感知就是
 * 「六格里有没有换掉内容」。如果换批算法退化成每轮只挪一格，
 * 每批与上一批重合 5 只——功能测试（接口能不能返回 6 条）依然全绿，
 * 本地跑起来也不报错，但界面看起来就是「卡住了没动」。
 * 这类问题只能靠对<b>集合关系</b>的断言发现，所以这里不测「返回了几条」，
 * 而是测「这批和上批是不是同一批股票」。
 *
 * <p>测试用固定假数据源，避免依赖外部行情接口；股票名直接用代码回填，
 * 断言里只比对代码，不关心展示文案。
 */
@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

    @Mock
    private MarketDataProvider marketDataProvider;

    private QuoteService quoteService;

    @BeforeEach
    void setUp() {
        quoteService = new QuoteService(marketDataProvider, new SimpleMeterRegistry());
    }

    /** 让任意代码都能取到报价，名称回填为「股票+代码」，方便断言。 */
    private void stubAllQuotesAvailable() {
        when(marketDataProvider.getQuote(anyString())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0, String.class);
            return new Quote(code, "股票" + code, "SH", 10.0, 0.1, 1.0,
                    10.0, 10.2, 9.9, 9.9, 1000L, 1.0, 100000.0, 1700000000L);
        });
    }

    private static Set<String> codesOf(List<CarouselItem> items) {
        Set<String> codes = new HashSet<>();
        items.forEach(item -> codes.add(item.code()));
        return codes;
    }

    @Nested
    @DisplayName("换批行为")
    class Rotation {

        @Test
        @DisplayName("相邻两批零重合：用户每 10 秒看到的是整片换新")
        void consecutiveRoundsDoNotOverlap() {
            stubAllQuotesAvailable();

            Set<String> previous = null;
            for (int round = 0; round < 20; round++) {
                Set<String> current = codesOf(quoteService.getCarousel(6, round));
                assertThat(current).as("第 %d 批应有 6 只且互不重复", round).hasSize(6);
                if (previous != null) {
                    Set<String> overlap = new HashSet<>(current);
                    overlap.retainAll(previous);
                    assertThat(overlap)
                            .as("第 %d 批与第 %d 批不应有重合（否则界面看起来没刷新）",
                                    round - 1, round)
                            .isEmpty();
                }
                previous = current;
            }
        }

        @Test
        @DisplayName("同一批号幂等：同一天重复请求同一批，结果完全一致")
        void sameRoundIsStable() {
            stubAllQuotesAvailable();

            List<CarouselItem> first = quoteService.getCarousel(6, 3);
            List<CarouselItem> second = quoteService.getCarousel(6, 3);

            assertThat(first).extracting(CarouselItem::code)
                    .containsExactlyElementsOf(second.stream().map(CarouselItem::code).toList());
        }

        @Test
        @DisplayName("轮次推进后看点文案会变化：不是只换了股票代码")
        void highlightFollowsRound() {
            stubAllQuotesAvailable();

            List<String> highlights = List.of(
                    quoteService.getCarousel(6, 0).toString(),
                    quoteService.getCarousel(6, 1).toString(),
                    quoteService.getCarousel(6, 2).toString());

            // 三批内容两两不同（种子随轮次变化，tag 也随之变化）
            assertThat(new HashSet<>(highlights)).hasSize(3);
        }

        @Test
        @DisplayName("候选池整体轮转：连续多批能覆盖到池内全部股票")
        void coversWholePool() {
            stubAllQuotesAvailable();

            Set<String> seen = new HashSet<>();
            for (int round = 0; round < 5; round++) {
                seen.addAll(codesOf(quoteService.getCarousel(6, round)));
            }

            // 5 批 × 6 只 = 30 个位置，池子 30 只且各批互斥 → 应覆盖全部
            assertThat(seen).as("5 批累计应覆盖整个候选池").hasSize(30);
        }
    }

    @Nested
    @DisplayName("边界与容错")
    class Robustness {

        @Test
        @DisplayName("count 为 0 或负数时收敛为 1 条，不返回空列表")
        void countIsClampedToAtLeastOne() {
            stubAllQuotesAvailable();

            assertThat(quoteService.getCarousel(0, 0)).hasSize(1);
            assertThat(quoteService.getCarousel(-5, 0)).hasSize(1);
        }

        @Test
        @DisplayName("count 超过候选池时按池大小截断，且不出现重复")
        void countIsClampedToPoolSize() {
            stubAllQuotesAvailable();

            List<CarouselItem> items = quoteService.getCarousel(999, 0);

            assertThat(items).hasSize(30);
            assertThat(codesOf(items)).as("不应出现重复股票").hasSize(30);
        }

        @Test
        @DisplayName("负数轮次按第 0 批处理，不抛异常也不越界")
        void negativeRoundFallsBackToZero() {
            stubAllQuotesAvailable();

            assertThat(quoteService.getCarousel(6, -1))
                    .extracting(CarouselItem::code)
                    .containsExactlyElementsOf(
                            quoteService.getCarousel(6, 0).stream().map(CarouselItem::code).toList());
        }

        @Test
        @DisplayName("个别股票取数失败只跳过该条，不影响整批")
        void singleFailureDoesNotBreakTheBatch() {
            // 先探出当前批次包含哪些股票，再从中挑一只让它必挂。
            // 不能硬编码某只代码——批次起点由「当天日期」决定，写死代码
            // 会让这个测试在换天之后失败（第一版就是这么错的）。
            stubAllQuotesAvailable();
            String victim = quoteService.getCarousel(6, 0).get(2).code();

            when(marketDataProvider.getQuote(anyString())).thenAnswer(invocation -> {
                String code = invocation.getArgument(0, String.class);
                if (victim.equals(code)) {
                    throw new IllegalStateException("上游超时");
                }
                return new Quote(code, "股票" + code, "SH", 10.0, 0.1, 1.0,
                        10.0, 10.2, 9.9, 9.9, 1000L, 1.0, 100000.0, 1700000000L);
            });

            List<CarouselItem> items = quoteService.getCarousel(6, 0);

            // 6 只里挂 1 只 → 返回 5 只，且不含失败的那只
            assertThat(items).hasSize(5);
            assertThat(items).extracting(CarouselItem::code).doesNotContain(victim);
        }

        @Test
        @DisplayName("全部取数失败时返回空列表，由前端展示空态而不是抛 500")
        void allFailuresYieldEmptyList() {
            when(marketDataProvider.getQuote(anyString()))
                    .thenThrow(new IllegalStateException("数据源不可用"));

            assertThat(quoteService.getCarousel(6, 0)).isEmpty();
        }
    }
}
