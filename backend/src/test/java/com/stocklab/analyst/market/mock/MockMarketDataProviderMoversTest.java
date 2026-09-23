package com.stocklab.analyst.market.mock;

import com.stocklab.analyst.market.model.Mover;
import com.stocklab.analyst.market.model.MoverMetric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 演示源榜单排序语义。
 *
 * <p>榜单最容易被写错的地方不是「取不到数据」，而是<b>方向</b>：
 * 跌幅榜显示的是涨幅最高的几只、limit 不生效多返回几条，
 * 这类错误页面上看起来「也是一堆股票」，不逐项核对根本发现不了。
 * 所以这里按「维度 × 方向 × 裁剪」三个轴逐一断言。
 */
@DisplayName("演示源市场榜单")
class MockMarketDataProviderMoversTest {

    private final MockMarketDataProvider provider = new MockMarketDataProvider();

    @Test
    @DisplayName("涨幅榜（CHANGE_PCT 降序）按涨跌幅从高到低")
    void gainersAreSortedDescending() {
        List<Mover> movers = provider.getTopMovers(MoverMetric.CHANGE_PCT, false, 10);

        assertThat(movers).isNotEmpty();
        for (int i = 1; i < movers.size(); i++) {
            assertThat(movers.get(i - 1).changePct())
                    .as("第 %d 名(%s) 的涨跌幅应不低于第 %d 名(%s)",
                            i, movers.get(i - 1).code(), i + 1, movers.get(i).code())
                    .isGreaterThanOrEqualTo(movers.get(i).changePct());
        }
    }

    @Test
    @DisplayName("跌幅榜（CHANGE_PCT 升序）第一条是全池涨幅最低的")
    void losersAreSortedAscending() {
        List<Mover> descending = provider.getTopMovers(MoverMetric.CHANGE_PCT, false, 10);
        List<Mover> ascending = provider.getTopMovers(MoverMetric.CHANGE_PCT, true, 10);

        assertThat(ascending).isNotEmpty();
        // 升序第一条与降序最后一条是同一只股票——两个方向覆盖了同一个池子的两端
        assertThat(ascending.get(0).changePct())
                .isEqualTo(descending.get(descending.size() - 1).changePct());
        for (int i = 1; i < ascending.size(); i++) {
            assertThat(ascending.get(i - 1).changePct())
                    .isLessThanOrEqualTo(ascending.get(i).changePct());
        }
    }

    @Test
    @DisplayName("成交额榜按成交额从高到低，且金额为非负")
    void amountBoardSortedDescending() {
        List<Mover> movers = provider.getTopMovers(MoverMetric.AMOUNT, false, 5);

        assertThat(movers).hasSize(5);
        for (int i = 1; i < movers.size(); i++) {
            assertThat(movers.get(i - 1).amount())
                    .isGreaterThanOrEqualTo(movers.get(i).amount());
        }
        assertThat(movers).allSatisfy(mover -> assertThat(mover.amount()).isGreaterThanOrEqualTo(0));
    }

    @Test
    @DisplayName("limit 裁剪结果条数；超界值收敛到 [1, 20]")
    void limitIsClampedAndApplied() {
        assertThat(provider.getTopMovers(MoverMetric.CHANGE_PCT, false, 3)).hasSize(3);
        // limit=0 不允许返回 0 条空榜单（那等于「榜单坏了」）——收敛到 1
        assertThat(provider.getTopMovers(MoverMetric.CHANGE_PCT, false, 0)).hasSize(1);
        // 股票池只有 10 只，limit=999 全量返回而不是报错
        assertThat(provider.getTopMovers(MoverMetric.CHANGE_PCT, false, 999)).hasSize(10);
    }

    @Test
    @DisplayName("榜单条目带名称与代码，可点击跳详情")
    void entriesCarryIdentity() {
        List<Mover> movers = provider.getTopMovers(MoverMetric.CHANGE_PCT, false, 10);

        assertThat(movers).allSatisfy(mover -> {
            assertThat(mover.code()).matches("\\d{6}");
            assertThat(mover.name()).isNotBlank();
            assertThat(mover.price()).isPositive();
        });
    }
}
