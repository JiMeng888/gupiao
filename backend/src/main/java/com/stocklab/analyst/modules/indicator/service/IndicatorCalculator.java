package com.stocklab.analyst.modules.indicator.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 技术指标算法（纯函数，无状态、不依赖 Spring）。
 *
 * <p>实现与 MyTT / 通达信口径对齐，全部手写，不引入 TA-Lib 之类的本地库：
 * <ul>
 *   <li>TA-Lib 需要安装本地动态库，Docker 镜像与 CI 都要额外配置，
 *       而本项目实际只用到 5 个指标，自实现的收益大于成本</li>
 *   <li>纯函数便于用固定输入验证输出，测试不依赖任何外部依赖</li>
 * </ul>
 *
 * <p><b>数值口径说明（容易被追问的点）</b>：
 * <ol>
 *   <li>所有指标都从序列<b>第 0 根</b>开始产出等长序列，不做「前 N 根置空」处理。
 *       代价是早期的均线值在样本不足时用「已有样本的均值」代替
 *       （{@code ma} 在 {@code i < period-1} 时除以 {@code i+1}），
 *       好处是绘图时序列与 K 线严格对齐，不需要前端做偏移补偿。</li>
 *   <li>{@code SMA} 指的是<b>通达信口径的递推平滑</b>
 *       {@code Y[i] = (X[i] + (N-1) * Y[i-1]) / N}，不是简单移动平均。
 *       RSI 与 KDJ 内部用的都是它，若误用普通均值，曲线形态会明显不同。</li>
 *   <li>EMA 的种子取首值（{@code Y[0] = X[0]}）而不是前 N 根的均值。
 *       不同软件的种子处理不同，会导致前期数值有差异——
 *       这也是同一只股票在不同软件上 MACD 早期数值对不上的常见原因。</li>
 *   <li>BOLL 的标准差用<b>总体标准差</b>（除以 N），不是样本标准差（除以 N-1）。</li>
 * </ol>
 *
 * <p>因此：追踪这些曲线时请<b>同源对比</b>——先把基准软件的参数与种子
 * 规则对齐，再比数值。跨软件的绝对数值差异通常来自口径而非算法错误。
 */
public final class IndicatorCalculator {

    private IndicatorCalculator() {
    }

    /**
     * 指数移动平均 EMA。
     *
     * <p>{@code k = 2 / (period + 1)}，种子为首值。
     */
    public static double[] ema(double[] values, int period) {
        if (values == null || values.length == 0) {
            return new double[0];
        }
        double k = 2.0 / (period + 1);
        double[] result = new double[values.length];
        result[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            result[i] = values[i] * k + result[i - 1] * (1 - k);
        }
        return result;
    }

    /**
     * 通达信口径递推平滑 SMA。
     *
     * <p>{@code Y[i] = (X[i] + (period - 1) * Y[i-1]) / period}，种子为首值。
     * 注意这不是简单移动平均——RSI 与 KDJ 依赖这个口径。
     */
    public static double[] sma(double[] values, int period) {
        if (values == null || values.length == 0) {
            return new double[0];
        }
        double[] result = new double[values.length];
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum = (i == 0) ? values[i] : (values[i] + (period - 1) * sum) / period;
            result[i] = sum;
        }
        return result;
    }

    /**
     * 简单移动平均 MA（滑动窗口求均值）。
     *
     * <p>用「增量维护窗口和」而不是每次重新求和，
     * 复杂度从 O(n·period) 降到 O(n)。K 线默认取 120~500 根、
     * MA60 是最长窗口，这个优化在批量指标计算时是有效的。
     * 窗口内样本不足时按「已有样本均值」输出，保证与 K 线等长。
     */
    public static double[] ma(double[] values, int period) {
        if (values == null || values.length == 0) {
            return new double[0];
        }
        double[] result = new double[values.length];
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= period) {
                sum -= values[i - period];
            }
            result[i] = (i >= period - 1) ? sum / period : sum / (i + 1);
        }
        return result;
    }

    /**
     * MACD。
     *
     * @return {@code [dif, dea, hist]}，其中 {@code hist = (dif - dea) * 2}
     *         （乘 2 是国内软件约定，通达信与东方财富均如此，不乘会导致柱高偏小一半）
     */
    public static Macd macd(double[] values, int fast, int slow, int signal) {
        double[] emaFast = ema(values, fast);
        double[] emaSlow = ema(values, slow);
        int length = Math.min(emaFast.length, emaSlow.length);

        double[] dif = new double[length];
        for (int i = 0; i < length; i++) {
            dif[i] = emaFast[i] - emaSlow[i];
        }
        double[] dea = ema(dif, signal);
        double[] hist = new double[length];
        for (int i = 0; i < length; i++) {
            hist[i] = (dif[i] - dea[i]) * 2;
        }
        return new Macd(dif, dea, hist);
    }

    /**
     * 相对强弱指标 RSI。
     *
     * <p>第 0 根固定为 0（没有前值无法计算涨跌）。
     * 平均跌幅为 0 时直接返回 100，避免除零。
     */
    public static double[] rsi(double[] values, int period) {
        if (values == null || values.length == 0) {
            return new double[0];
        }
        int n = values.length;
        double[] gain = new double[Math.max(0, n - 1)];
        double[] loss = new double[Math.max(0, n - 1)];
        for (int i = 1; i < n; i++) {
            double diff = values[i] - values[i - 1];
            gain[i - 1] = Math.max(diff, 0);
            loss[i - 1] = Math.max(-diff, 0);
        }
        double[] avgGain = sma(gain, period);
        double[] avgLoss = sma(loss, period);

        double[] result = new double[n];
        result[0] = 0;
        for (int i = 0; i < avgGain.length; i++) {
            double l = avgLoss[i];
            result[i + 1] = (l == 0) ? 100.0 : 100.0 - 100.0 / (1.0 + avgGain[i] / l);
        }
        return result;
    }

    /**
     * 布林带 BOLL。
     *
     * <p>中轨为 MA(period)，上下轨为中轨 ± width × 总体标准差。
     * 标准差在「以当前点为末端的滑动窗口」上计算，而非整段序列，
     * 否则轨道会随数据量变化而漂移。
     */
    public static Boll boll(double[] values, int period, double width) {
        if (values == null || values.length == 0) {
            return new Boll(new double[0], new double[0], new double[0]);
        }
        double[] mid = ma(values, period);
        int n = values.length;
        double[] upper = new double[n];
        double[] lower = new double[n];

        for (int i = 0; i < n; i++) {
            int from = Math.max(0, i - period + 1);
            int windowSize = i - from + 1;
            double sum = 0;
            for (int j = from; j <= i; j++) {
                sum += values[j];
            }
            double mean = sum / windowSize;
            double variance = 0;
            for (int j = from; j <= i; j++) {
                double delta = values[j] - mean;
                variance += delta * delta;
            }
            variance /= windowSize;
            double std = Math.sqrt(variance);
            upper[i] = mid[i] + width * std;
            lower[i] = mid[i] - width * std;
        }
        return new Boll(upper, mid, lower);
    }

    /**
     * 随机指标 KDJ。
     *
     * <p>先算 RSV（原始随机值），再用递推平滑 SMA(3) 依次得到 K、D，
     * 最后 {@code J = 3K - 2D}。J 会超出 0~100，这是它的正常特性。
     */
    public static Kdj kdj(double[] high, double[] low, double[] close, int period) {
        int n = close.length;
        if (n == 0) {
            return new Kdj(new double[0], new double[0], new double[0]);
        }
        double[] rsv = new double[n];
        for (int i = 0; i < n; i++) {
            int from = Math.max(0, i - period + 1);
            double lowest = Double.MAX_VALUE;
            double highest = -Double.MAX_VALUE;
            for (int j = from; j <= i; j++) {
                lowest = Math.min(lowest, low[j]);
                highest = Math.max(highest, high[j]);
            }
            rsv[i] = (highest == lowest) ? 0.0 : (close[i] - lowest) / (highest - lowest) * 100.0;
        }
        double[] k = sma(rsv, 3);
        double[] d = sma(k, 3);
        double[] j = new double[n];
        for (int i = 0; i < n; i++) {
            j[i] = 3 * k[i] - 2 * d[i];
        }
        return new Kdj(k, d, j);
    }

    // ------------------------------------------------------------------
    // 返回结构（数组而非 List，避免逐元素装箱；序列化为 JSON 时形态一致）
    // ------------------------------------------------------------------

    /** MACD 三线。 */
    public record Macd(double[] dif, double[] dea, double[] hist) {
    }

    /** 布林带三轨。 */
    public record Boll(double[] upper, double[] mid, double[] lower) {
    }

    /** KDJ 三线。 */
    public record Kdj(double[] k, double[] d, double[] j) {
    }

    /** 便捷方法：把 {@code double[]} 转为可供 Jackson 序列化的 {@code List}。 */
    public static List<Double> toList(double[] values) {
        List<Double> list = new ArrayList<>(values.length);
        for (double value : values) {
            // 统一保留 4 位小数：原始 double 会有 1266.9800000000002 这类浮点噪声，
            // 直接下发会让前端图表出现无意义的锯齿，也显著增大响应体
            list.add(Math.round(value * 10_000.0) / 10_000.0);
        }
        return list;
    }
}
