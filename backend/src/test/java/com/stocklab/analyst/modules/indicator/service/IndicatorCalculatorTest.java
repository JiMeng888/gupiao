package com.stocklab.analyst.modules.indicator.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link IndicatorCalculator} 的单元测试。
 *
 * <p>这些指标是「纯函数」，最适合用固定输入锁定输出。测试目标不是覆盖率数字，
 * 而是把下面几件容易被改坏、且改坏后不容易被发现的事情钉住：
 *
 * <ol>
 *   <li><b>{@code ma} 的增量窗口维护</b>。它用「加当前值、减滑出值」代替每次重新求和，
 *       复杂度从 O(n·period) 降到 O(n)。优化本身没问题，但一旦加减时机写错，
 *       输出会整体偏移而不报错——图表看起来「还是均线」，只是数值全不对。
 *       因此用朴素滑动窗口做<b>参照实现</b>逐点比对。</li>
 *   <li><b>{@code boll} 内部均值与 {@code ma} 的口径一致性</b>。
 *       {@code boll} 自己算了一遍滑动窗口均值（为了同时算标准差），
 *       如果两者的「样本不足时怎么办」处理不一致，中轨会与单独算出的 MA 对不上。</li>
 *   <li><b>{@code sma} 是通达信递推平滑而非简单均值</b>。RSI 与 KDJ 都建立在它之上，
 *       误用普通均值时曲线形态会明显不同，而这种错误不会抛异常。</li>
 *   <li><b>浮点噪声不能在接口输出里出现</b>（{@code toList} 的量化）。</li>
 * </ol>
 *
 * <p>数值口径（种子、总体标准差等）以类注释为准，测试同时充当「口径可执行文档」。
 */
class IndicatorCalculatorTest {

    // ------------------------------------------------------------------
    // 参照实现：朴素滑动窗口均值
    // ------------------------------------------------------------------

    /**
     * 朴素滑动窗口均值，作为 {@code ma} 的参照实现。
     *
     * <p>刻意不共享生产代码的任何逻辑——如果测试调用被测代码去算期望值，
     * 那个测试就只是在验证「代码等于它自己」。这里独立重写一遍算法：
     * 对每个位置显式限定窗口、重新求和、按<b>实际窗口长度</b>取均值。
     */
    private static double[] naiveMa(double[] values, int period) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            int from = Math.max(0, i - period + 1);
            double sum = 0;
            for (int j = from; j <= i; j++) {
                sum += values[j];
            }
            int windowSize = i - from + 1;
            out[i] = sum / windowSize;
        }
        return out;
    }

    /** 确定性伪随机序列：不用 Random，避免测试出现「偶尔失败」。 */
    private static double[] series(int length, double start, double step, double wobble) {
        double[] out = new double[length];
        for (int i = 0; i < length; i++) {
            // 一个可复现的三角函数扰动，形态上接近价格波动
            out[i] = start + i * step + Math.sin(i * 1.7) * wobble;
        }
        return out;
    }

    /** 逐点比对两个等长序列。 */
    private static void assertSeriesEquals(double[] actual, double[] expected, double tolerance, String what) {
        assertThat(actual).as("%s 长度", what).hasSameSizeAs(expected);
        for (int i = 0; i < expected.length; i++) {
            assertThat(actual[i]).as("%s 第 %d 项", what, i).isCloseTo(expected[i], within(tolerance));
        }
    }

    // ==================================================================
    // ma：增量窗口维护正确性（本文件最重要的测试）
    // ==================================================================

    @Nested
    @DisplayName("ma 简单移动平均")
    class MaTest {

        @Test
        @DisplayName("增量窗口维护的结果必须与朴素滑动窗口逐点一致（含样本不足的前段）")
        void incrementalWindowMatchesNaive() {
            double[] values = series(50, 10.0, 0.37, 1.5);

            // 选 1 / 5 / 20 三个周期：分别覆盖「period=1 退化」「前段样本不足与后段充足
            // 各占一半」「period 远小于长度」三种情形
            for (int period : new int[]{1, 5, 20}) {
                double[] actual = IndicatorCalculator.ma(values, period);
                double[] expected = naiveMa(values, period);
                assertSeriesEquals(actual, expected, 1e-9, "ma(period=" + period + ")");
            }
        }

        @Test
        @DisplayName("窗口内样本不足时按「已有样本均值」输出，保证与 K 线等长")
        void usesAvailableSamplesWhenInsufficient() {
            double[] values = {10, 12, 14, 16, 18};
            int period = 5;
            double[] out = IndicatorCalculator.ma(values, period);

            // i=0: 只有 1 个样本 -> 10/1
            assertThat(out[0]).isCloseTo(10.0, within(1e-9));
            // i=1: 2 个样本 -> (10+12)/2
            assertThat(out[1]).isCloseTo(11.0, within(1e-9));
            // i=2: 3 个样本 -> 36/3
            assertThat(out[2]).isCloseTo(12.0, within(1e-9));
            // i=3: 4 个样本 -> 52/4
            assertThat(out[3]).isCloseTo(13.0, within(1e-9));
            // i=4: 满窗 5 个 -> 70/5
            assertThat(out[4]).isCloseTo(14.0, within(1e-9));

            // 关键：序列长度必须等于输入长度，前端不需要做偏移补偿
            assertThat(out).hasSize(values.length);
        }

        @Test
        @DisplayName("空输入返回空数组而不是抛异常")
        void emptyInput() {
            assertThat(IndicatorCalculator.ma(new double[0], 5)).isEmpty();
            assertThat(IndicatorCalculator.ma(null, 5)).isEmpty();
        }

        @Test
        @DisplayName("period 大于序列长度时不越界，退化为累计均值")
        void periodLongerThanSeries() {
            double[] values = {10, 20, 30};
            double[] out = IndicatorCalculator.ma(values, 60);
            assertThat(out).hasSize(3);
            assertThat(out[0]).isCloseTo(10.0, within(1e-9));
            assertThat(out[1]).isCloseTo(15.0, within(1e-9));
            assertThat(out[2]).isCloseTo(20.0, within(1e-9));
        }
    }

    // ==================================================================
    // EMA / SMA
    // ==================================================================

    @Nested
    @DisplayName("ema 指数移动平均")
    class EmaTest {

        @Test
        @DisplayName("种子取首值（Y[0] = X[0]），不取前 N 根均值")
        void seedIsFirstValue() {
            double[] values = {100, 200, 300};
            double[] out = IndicatorCalculator.ema(values, 3);
            assertThat(out[0]).isCloseTo(100.0, within(1e-12));
        }

        @Test
        @DisplayName("递推公式 k = 2/(period+1) 逐点正确")
        void recurrenceIsCorrect() {
            double[] values = {100, 200, 300, 400};
            int period = 3;
            double k = 2.0 / (period + 1); // = 0.5

            double[] out = IndicatorCalculator.ema(values, period);

            double expected1 = 200 * k + 100 * (1 - k); // 150
            double expected2 = 300 * k + expected1 * (1 - k); // 225
            double expected3 = 400 * k + expected2 * (1 - k); // 312.5

            assertThat(out[1]).isCloseTo(expected1, within(1e-12));
            assertThat(out[2]).isCloseTo(expected2, within(1e-12));
            assertThat(out[3]).isCloseTo(expected3, within(1e-12));
        }

        @Test
        @DisplayName("常量序列的 EMA 恒等于该常量（种子与递推都收敛到原值）")
        void constantSeriesStaysConstant() {
            double[] values = new double[20];
            java.util.Arrays.fill(values, 7.5);
            double[] out = IndicatorCalculator.ema(values, 12);
            for (double v : out) {
                assertThat(v).isCloseTo(7.5, within(1e-12));
            }
        }
    }

    @Nested
    @DisplayName("sma 通达信口径递推平滑")
    class SmaTest {

        @Test
        @DisplayName("是递推平滑 Y[i]=(X[i]+(N-1)Y[i-1])/N，不是简单移动平均")
        void isRecursiveNotSimpleAverage() {
            double[] values = {10, 20, 30};
            int period = 3;

            double[] out = IndicatorCalculator.sma(values, period);

            // Y[0] = 10
            // Y[1] = (20 + 2*10)/3 = 13.333...
            // Y[2] = (30 + 2*13.3333)/3 = 18.888...
            assertThat(out[0]).isCloseTo(10.0, within(1e-9));
            assertThat(out[1]).isCloseTo(40.0 / 3, within(1e-9));
            assertThat(out[2]).isCloseTo(170.0 / 9, within(1e-9));

            // 同时断言它确实**不等于**简单移动平均 —— 否则这个测试可能被误改通过
            double[] simpleAverage = naiveMa(values, period);
            assertThat(out[2]).as("SMA 与简单均值在此例中不应相等").isNotCloseTo(simpleAverage[2], within(1e-6));
        }

        @Test
        @DisplayName("period = 1 时退化为原序列")
        void periodOneIsIdentity() {
            double[] values = {3, 1, 4, 1, 5};
            assertSeriesEquals(IndicatorCalculator.sma(values, 1), values, 1e-12, "sma(period=1)");
        }
    }

    // ==================================================================
    // MACD
    // ==================================================================

    @Nested
    @DisplayName("macd")
    class MacdTest {

        @Test
        @DisplayName("hist = (dif - dea) * 2（乘 2 是国内软件约定）")
        void histIsDoubleSpread() {
            double[] values = series(40, 100, 0.8, 3.0);
            IndicatorCalculator.Macd macd = IndicatorCalculator.macd(values, 12, 26, 9);

            for (int i = 0; i < macd.dif().length; i++) {
                double expected = (macd.dif()[i] - macd.dea()[i]) * 2;
                assertThat(macd.hist()[i]).as("hist[%d]", i).isCloseTo(expected, within(1e-9));
            }
        }

        @Test
        @DisplayName("三线长度一致且等于输入长度")
        void allSeriesHaveInputLength() {
            double[] values = series(40, 100, 0.8, 3.0);
            IndicatorCalculator.Macd macd = IndicatorCalculator.macd(values, 12, 26, 9);

            assertThat(macd.dif()).hasSize(values.length);
            assertThat(macd.dea()).hasSize(values.length);
            assertThat(macd.hist()).hasSize(values.length);
        }

        @Test
        @DisplayName("常量序列的 DIF 与 DEA 恒为 0（快慢 EMA 相同）")
        void constantSeriesGivesZero() {
            double[] values = new double[40];
            java.util.Arrays.fill(values, 55.0);
            IndicatorCalculator.Macd macd = IndicatorCalculator.macd(values, 12, 26, 9);
            for (int i = 0; i < values.length; i++) {
                assertThat(macd.dif()[i]).isCloseTo(0.0, within(1e-12));
                assertThat(macd.dea()[i]).isCloseTo(0.0, within(1e-12));
                assertThat(macd.hist()[i]).isCloseTo(0.0, within(1e-12));
            }
        }
    }

    // ==================================================================
    // RSI
    // ==================================================================

    @Nested
    @DisplayName("rsi")
    class RsiTest {

        @Test
        @DisplayName("单调上涨：平均跌幅为 0，RSI 饱和为 100")
        void monotonicRiseGives100() {
            double[] values = new double[15];
            for (int i = 0; i < values.length; i++) {
                values[i] = 1 + i;
            }
            double[] rsi = IndicatorCalculator.rsi(values, 14);

            assertThat(rsi).hasSize(15);
            assertThat(rsi[0]).as("第 0 根无前值可比，固定为 0").isCloseTo(0.0, within(1e-12));
            for (int i = 1; i < rsi.length; i++) {
                assertThat(rsi[i]).as("rsi[%d]", i).isCloseTo(100.0, within(1e-9));
            }
        }

        @Test
        @DisplayName("单调下跌：平均涨幅为 0，RSI 为 0")
        void monotonicFallGives0() {
            double[] values = new double[15];
            for (int i = 0; i < values.length; i++) {
                values[i] = 100 - i;
            }
            double[] rsi = IndicatorCalculator.rsi(values, 14);

            assertThat(rsi[0]).isCloseTo(0.0, within(1e-12));
            for (int i = 1; i < rsi.length; i++) {
                assertThat(rsi[i]).as("rsi[%d]", i).isCloseTo(0.0, within(1e-9));
            }
        }

        @Test
        @DisplayName("输出长度与输入一致，且取值落在 [0, 100]")
        void staysWithinBounds() {
            double[] values = series(60, 20, 0.25, 4.0);
            double[] rsi = IndicatorCalculator.rsi(values, 14);

            assertThat(rsi).hasSize(values.length);
            for (double v : rsi) {
                assertThat(v).isBetween(0.0, 100.0);
            }
        }

        /**
         * 特征化测试（characterization test）：把「完全横盘」这一输入下的<b>当前</b>行为钉住。
         *
         * <p>当前实现里 {@code avgLoss == 0} 一律返回 100。对「一路上涨」这没错，
         * 但「完全不涨不跌」也落进同一分支，于是横盘序列得到 RSI = 100，
         * 即被判定为<b>极端超买</b>。这是 Wilder 原始定义下的常见实现方式
         * （RS = avgGain/avgLoss，avgLoss 为 0 时 RS 趋于无穷、RSI 趋于 100），
         * 多数库也沿用；但从业务语义看，横盘得出「超买」是可质疑的。
         *
         * <p>这里<b>不断言它「正确」</b>，只是把现状固化：将来若改成横盘返回 50，
         * 这个测试会失败，改动者会被迫看到这条决策并显式更新它——
         * 这正是特征化测试的用途。
         */
        @Test
        @DisplayName("【特征化】完全横盘时当前返回 100（口径选择，非唯一解）")
        void flatSeriesCurrentlyGives100() {
            double[] values = new double[30];
            java.util.Arrays.fill(values, 42.0);
            double[] rsi = IndicatorCalculator.rsi(values, 14);

            assertThat(rsi[0]).isCloseTo(0.0, within(1e-12));
            assertThat(rsi[14]).as("横盘 -> 当前实现返回 100（极端超买）").isCloseTo(100.0, within(1e-9));

            // 与「单调下跌」对比：下跌得到 0，横盘得到 100，两者的极性与直觉相反
            double[] falling = new double[30];
            for (int i = 0; i < falling.length; i++) {
                falling[i] = 100 - i;
            }
            assertThat(IndicatorCalculator.rsi(falling, 14)[14]).isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("空输入返回空数组")
        void emptyInput() {
            assertThat(IndicatorCalculator.rsi(new double[0], 14)).isEmpty();
        }

        @Test
        @DisplayName("长度为 1 的序列：只有第 0 根，返回 [0]")
        void singleValue() {
            double[] rsi = IndicatorCalculator.rsi(new double[]{88.0}, 14);
            assertThat(rsi).hasSize(1);
            assertThat(rsi[0]).isCloseTo(0.0, within(1e-12));
        }
    }

    // ==================================================================
    // BOLL
    // ==================================================================

    @Nested
    @DisplayName("boll 布林带")
    class BollTest {

        @Test
        @DisplayName("中轨必须与单独调用的 ma 完全一致（两处均值口径不能漂移）")
        void midBandMatchesMa() {
            double[] values = series(50, 10, 0.37, 1.5);
            int period = 20;

            IndicatorCalculator.Boll boll = IndicatorCalculator.boll(values, period, 2.0);
            double[] ma = IndicatorCalculator.ma(values, period);

            // 这是本轮最有价值的断言：boll 内部为了算标准差又算了一遍均值，
            // 如果它的「样本不足」处理方式与 ma 不同，中轨就会对不上
            assertSeriesEquals(boll.mid(), ma, 1e-9, "boll.mid vs ma");
        }

        @Test
        @DisplayName("上下轨 = 中轨 ± width × 总体标准差（除以 N，不是 N-1）")
        void bandsUsePopulationStdDev() {
            double[] values = {10, 12, 14, 16, 18};
            int period = 5;

            IndicatorCalculator.Boll boll = IndicatorCalculator.boll(values, period, 2.0);

            // 满窗：均值 14，偏差 -4/-2/0/2/4，平方和 40
            // 总体方差 = 40/5 = 8，总体标准差 = sqrt(8)
            double std = Math.sqrt(8.0);
            assertThat(boll.mid()[4]).isCloseTo(14.0, within(1e-9));
            assertThat(boll.upper()[4]).isCloseTo(14.0 + 2 * std, within(1e-9));
            assertThat(boll.lower()[4]).isCloseTo(14.0 - 2 * std, within(1e-9));

            // 若误用样本标准差（除以 N-1=4），总体方差会变成 10、标准差 sqrt(10)，
            // 上下轨会明显偏宽 —— 显式断言当前不是这种口径
            double sampleStd = Math.sqrt(40.0 / 4);
            assertThat(boll.upper()[4]).as("不应使用样本标准差").isNotCloseTo(14.0 + 2 * sampleStd, within(1e-6));
        }

        @Test
        @DisplayName("样本不足的前段：窗口按现有样本计算，且中轨与 ma 的累计均值一致")
        void earlyWindowUsesAvailableSamples() {
            double[] values = {10, 12, 14, 16, 18};
            IndicatorCalculator.Boll boll = IndicatorCalculator.boll(values, 5, 2.0);

            // i=3: 窗口 [0,3] 共 4 个样本，均值 13，偏差 -3/-1/1/3，平方和 20
            // 总体方差 = 20/4 = 5
            double std = Math.sqrt(5.0);
            assertThat(boll.mid()[3]).isCloseTo(13.0, within(1e-9));
            assertThat(boll.upper()[3]).isCloseTo(13.0 + 2 * std, within(1e-9));
            assertThat(boll.lower()[3]).isCloseTo(13.0 - 2 * std, within(1e-9));
        }

        @Test
        @DisplayName("常量序列标准差为 0，三轨重合")
        void constantSeriesCollapses() {
            double[] values = new double[30];
            java.util.Arrays.fill(values, 33.0);
            IndicatorCalculator.Boll boll = IndicatorCalculator.boll(values, 20, 2.0);

            for (int i = 0; i < values.length; i++) {
                assertThat(boll.upper()[i]).isCloseTo(33.0, within(1e-9));
                assertThat(boll.mid()[i]).isCloseTo(33.0, within(1e-9));
                assertThat(boll.lower()[i]).isCloseTo(33.0, within(1e-9));
            }
        }

        @Test
        @DisplayName("上轨恒不低于下轨")
        void upperNeverBelowLower() {
            double[] values = series(60, 10, 0.3, 5.0);
            IndicatorCalculator.Boll boll = IndicatorCalculator.boll(values, 20, 2.0);
            for (int i = 0; i < values.length; i++) {
                assertThat(boll.upper()[i]).isGreaterThanOrEqualTo(boll.lower()[i]);
            }
        }

        @Test
        @DisplayName("空输入返回三条空轨")
        void emptyInput() {
            IndicatorCalculator.Boll boll = IndicatorCalculator.boll(new double[0], 20, 2.0);
            assertThat(boll.upper()).isEmpty();
            assertThat(boll.mid()).isEmpty();
            assertThat(boll.lower()).isEmpty();
        }
    }

    // ==================================================================
    // KDJ
    // ==================================================================

    @Nested
    @DisplayName("kdj")
    class KdjTest {

        @Test
        @DisplayName("RSV -> K=SMA(RSV,3) -> D=SMA(K,3) -> J=3K-2D，逐点手算比对")
        void matchesHandComputedValues() {
            double[] high = {10, 11, 12};
            double[] low = {9, 10, 11};
            double[] close = {9.5, 10.5, 11.5};

            IndicatorCalculator.Kdj kdj = IndicatorCalculator.kdj(high, low, close, 3);

            // RSV: i=0 window[0,0] 最高10 最低9 -> (9.5-9)/1*100 = 50
            //      i=1 window[0,1] 最高11 最低9 -> (10.5-9)/2*100 = 75
            //      i=2 window[0,2] 最高12 最低9 -> (11.5-9)/3*100 = 83.3333...
            // K = SMA(RSV,3): 50, (75+2*50)/3 = 58.3333, (83.3333+2*58.3333)/3 = 66.6667
            // D = SMA(K,3):   50, (58.3333+2*50)/3 = 52.7778, (66.6667+2*52.7778)/3 = 57.4074
            assertThat(kdj.k()[0]).isCloseTo(50.0, within(1e-6));
            assertThat(kdj.k()[1]).isCloseTo(175.0 / 3, within(1e-6));
            assertThat(kdj.k()[2]).isCloseTo(200.0 / 3, within(1e-6));

            assertThat(kdj.d()[0]).isCloseTo(50.0, within(1e-6));
            assertThat(kdj.d()[1]).isCloseTo(475.0 / 9, within(1e-6));
        }

        @Test
        @DisplayName("J = 3K - 2D 恒成立")
        void jEqualsThreeKMinusTwoD() {
            double[] high = series(40, 12, 0.2, 1.0);
            double[] low = series(40, 10, 0.2, 1.0);
            double[] close = series(40, 11, 0.2, 1.0);

            IndicatorCalculator.Kdj kdj = IndicatorCalculator.kdj(high, low, close, 9);
            for (int i = 0; i < close.length; i++) {
                assertThat(kdj.j()[i]).as("j[%d]", i)
                        .isCloseTo(3 * kdj.k()[i] - 2 * kdj.d()[i], within(1e-9));
            }
        }

        @Test
        @DisplayName("J 可以超出 0~100（这是 KDJ 的正常特性，不是越界 bug）")
        void jMayExceedBounds() {
            // 构造手法：先让价格长期横盘（此时 highest == lowest，RSV 恒为 0，K 与 D 都被压到 0），
            // 随后急涨。K 会快速抬升而 D 滞后，J = 3K - 2D 因此冲高突破 100。
            //
            // 注意不能用「匀速上涨」来构造：那种情况下 K 与 D 同步逼近同一个上限，
            // 差值拉不开，J 最高只能到 99.x。必须靠「先压制、后急拉」制造错位。
            int n = 30;
            double[] high = new double[n];
            double[] low = new double[n];
            double[] close = new double[n];
            for (int i = 0; i < 20; i++) {
                high[i] = 10;
                low[i] = 10;
                close[i] = 10;
            }
            for (int i = 20; i < n; i++) {
                double c = 10 + (i - 19) * 10.0; // 20, 30, 40 ...
                close[i] = c;
                high[i] = c + 0.5;
                low[i] = c - 0.5;
            }

            IndicatorCalculator.Kdj kdj = IndicatorCalculator.kdj(high, low, close, 9);

            double maxJ = Double.NEGATIVE_INFINITY;
            for (double v : kdj.j()) {
                maxJ = Math.max(maxJ, v);
            }
            assertThat(maxJ).as("急涨段的 J 应突破 100").isGreaterThan(100.0);

            // 同时也说明「J 越界」是该指标的正常现象，前端不应把它 clip 到 0~100
            assertThat(kdj.k()[21]).isBetween(0.0, 100.0);
            assertThat(kdj.d()[21]).isBetween(0.0, 100.0);
        }

        @Test
        @DisplayName("最高价等于最低价时 RSV 取 0（避免除零），K/D/J 全为 0")
        void flatBarGivesZeroRsv() {
            double[] high = {10, 10, 10};
            double[] low = {10, 10, 10};
            double[] close = {10, 10, 10};

            IndicatorCalculator.Kdj kdj = IndicatorCalculator.kdj(high, low, close, 3);

            for (int i = 0; i < 3; i++) {
                assertThat(kdj.k()[i]).isCloseTo(0.0, within(1e-12));
                assertThat(kdj.d()[i]).isCloseTo(0.0, within(1e-12));
                assertThat(kdj.j()[i]).isCloseTo(0.0, within(1e-12));
            }
        }

        @Test
        @DisplayName("空输入返回三条空线")
        void emptyInput() {
            IndicatorCalculator.Kdj kdj = IndicatorCalculator.kdj(new double[0], new double[0], new double[0], 9);
            assertThat(kdj.k()).isEmpty();
            assertThat(kdj.d()).isEmpty();
            assertThat(kdj.j()).isEmpty();
        }
    }

    // ==================================================================
    // toList：接口输出的量化
    // ==================================================================

    @Nested
    @DisplayName("toList 序列化辅助")
    class ToListTest {

        @Test
        @DisplayName("保留 4 位小数，消除 double 浮点噪声")
        void quantizesToFourDecimals() {
            // 0.1 + 0.2 = 0.30000000000000004
            double[] values = {0.1 + 0.2};
            assertThat(IndicatorCalculator.toList(values)).containsExactly(0.3);

            // 1266.9800000000002 这类噪声必须被抹掉，否则前端图表出现无意义锯齿
            double[] noisy = {1266.98 + 0.0000000000002};
            assertThat(IndicatorCalculator.toList(noisy)).containsExactly(1266.98);
        }

        @Test
        @DisplayName("四舍五入到 4 位：第 5 位决定进位")
        void roundsHalfUpOnFifthDecimal() {
            double[] values = {1.00004, 1.00005, 1.00006};
            assertThat(IndicatorCalculator.toList(values))
                    .containsExactly(1.0, 1.0001, 1.0001);
        }

        @Test
        @DisplayName("保持长度与顺序，空输入返回空列表")
        void preservesLengthAndOrder() {
            assertThat(IndicatorCalculator.toList(new double[0])).isEmpty();

            double[] values = {3.0, 1.0, 2.0};
            assertThat(IndicatorCalculator.toList(values)).containsExactly(3.0, 1.0, 2.0);
        }
    }
}
