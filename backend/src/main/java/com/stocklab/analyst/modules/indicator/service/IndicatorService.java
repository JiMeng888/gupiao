package com.stocklab.analyst.modules.indicator.service;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.MarketPeriod;
import com.stocklab.analyst.modules.indicator.dto.IndicatorName;
import com.stocklab.analyst.modules.indicator.dto.IndicatorResponse;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技术指标服务：取 K 线 → 计算指标 → 生成解读。
 *
 * <p>分层职责：本类只做「取数 + 编排 + 解读」，
 * 纯数学计算全部在 {@link IndicatorCalculator}，后者不依赖 Spring，
 * 可以用固定输入做单元测试，不受行情源波动影响。
 */
@Service
public class IndicatorService {

    /** 解读需要的最小样本量：MA20 与 BOLL(20) 至少要 20 根才有意义。 */
    private static final int MIN_BARS_FOR_SIGNAL = 20;

    private final MarketDataProvider marketDataProvider;

    public IndicatorService(MarketDataProvider marketDataProvider) {
        this.marketDataProvider = marketDataProvider;
    }

    /**
     * 计算指定指标。
     *
     * @param code  股票代码
     * @param names 需要计算的指标集合（非空）
     * @param limit 参与计算的 K 线根数
     */
    public IndicatorResponse compute(String code, Set<IndicatorName> names, int limit) {
        List<Bar> bars = marketDataProvider.getBars(code, MarketPeriod.DAILY, limit);
        if (bars.isEmpty()) {
            throw new BizException(ErrorCode.QUOTE_NOT_FOUND, "未获取到 " + code + " 的 K 线数据");
        }

        double[] close = new double[bars.size()];
        double[] high = new double[bars.size()];
        double[] low = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            close[i] = bar.close();
            high[i] = bar.high();
            low[i] = bar.low();
        }

        IndicatorResponse.MacdSeries macdSeries = null;
        List<Double> rsiSeries = null;
        IndicatorResponse.BollSeries bollSeries = null;
        IndicatorResponse.KdjSeries kdjSeries = null;
        IndicatorResponse.MaSeries maSeries = null;

        if (names.contains(IndicatorName.MACD)) {
            macdSeries = IndicatorResponse.ofMacd(IndicatorCalculator.macd(close, 12, 26, 9));
        }
        if (names.contains(IndicatorName.RSI)) {
            rsiSeries = IndicatorCalculator.toList(IndicatorCalculator.rsi(close, 14));
        }
        if (names.contains(IndicatorName.BOLL)) {
            bollSeries = IndicatorResponse.ofBoll(IndicatorCalculator.boll(close, 20, 2.0));
        }
        if (names.contains(IndicatorName.KDJ)) {
            kdjSeries = IndicatorResponse.ofKdj(IndicatorCalculator.kdj(high, low, close, 9));
        }
        if (names.contains(IndicatorName.MA)) {
            maSeries = new IndicatorResponse.MaSeries(
                    IndicatorCalculator.toList(IndicatorCalculator.ma(close, 5)),
                    IndicatorCalculator.toList(IndicatorCalculator.ma(close, 10)),
                    IndicatorCalculator.toList(IndicatorCalculator.ma(close, 20)),
                    IndicatorCalculator.toList(IndicatorCalculator.ma(close, 60)));
        }

        Map<String, String> signals = buildSignals(close, macdSeries, rsiSeries, bollSeries, kdjSeries, maSeries);

        return new IndicatorResponse(macdSeries, rsiSeries, bollSeries, kdjSeries, maSeries, signals);
    }

    /**
     * 按指标末值生成一句话解读。
     *
     * <p>几个刻意的取舍：
     * <ul>
     *   <li>样本不足（&lt; {@value #MIN_BARS_FOR_SIGNAL} 根）时不下结论，
     *       返回「数据不足」。这是必要的防御——MA20 在第 3 根时算出来的是
     *       「3 根的平均价」，拿它判断多头排列会给出完全错误的信号。</li>
     *   <li>交叉判断比较的是<b>最后两根</b>：金叉指的是「上一根还在下方、
     *       这根上穿」。只看 {@code dif > dea} 会把持续多日的老金叉
     *       一直报成新信号，失去时效性。</li>
     *   <li>解读只描述状态，不构成买卖建议。</li>
     * </ul>
     */
    private Map<String, String> buildSignals(double[] close,
                                             IndicatorResponse.MacdSeries macd,
                                             List<Double> rsi,
                                             IndicatorResponse.BollSeries boll,
                                             IndicatorResponse.KdjSeries kdj,
                                             IndicatorResponse.MaSeries ma) {
        Map<String, String> signals = new LinkedHashMap<>();
        if (close.length < MIN_BARS_FOR_SIGNAL) {
            signals.put("summary", "数据不足（少于 " + MIN_BARS_FOR_SIGNAL + " 根 K 线），暂不给出解读");
            return signals;
        }
        double lastClose = close[close.length - 1];

        if (macd != null && macd.dif().size() >= 2) {
            double dif = last(macd.dif());
            double dea = last(macd.dea());
            double prevDif = macd.dif().get(macd.dif().size() - 2);
            double prevDea = macd.dea().get(macd.dea().size() - 2);
            if (prevDif <= prevDea && dif > dea) {
                signals.put("macd", "DIF 上穿 DEA 形成金叉，短期动能转强");
            } else if (prevDif >= prevDea && dif < dea) {
                signals.put("macd", "DIF 下穿 DEA 形成死叉，短期动能转弱");
            } else if (dif > dea) {
                signals.put("macd", "DIF 位于 DEA 上方，多头格局延续");
            } else {
                signals.put("macd", "DIF 位于 DEA 下方，空头格局延续");
            }
        }

        if (rsi != null && !rsi.isEmpty()) {
            double value = last(rsi);
            if (value >= 70) {
                signals.put("rsi", String.format("RSI %.2f，进入超买区间，注意回调风险", value));
            } else if (value <= 30) {
                signals.put("rsi", String.format("RSI %.2f，进入超卖区间，关注反弹机会", value));
            } else {
                signals.put("rsi", String.format("RSI %.2f，处于中性区间", value));
            }
        }

        if (boll != null && !boll.upper().isEmpty()) {
            double upper = last(boll.upper());
            double lower = last(boll.lower());
            double mid = last(boll.mid());
            if (lastClose > upper) {
                signals.put("boll", "价格突破上轨，短期偏强但偏离均值较大");
            } else if (lastClose < lower) {
                signals.put("boll", "价格跌破下轨，短期偏弱但超跌概率上升");
            } else if (lastClose > mid) {
                signals.put("boll", "价格运行于中轨上方，通道内偏强");
            } else {
                signals.put("boll", "价格运行于中轨下方，通道内偏弱");
            }
        }

        if (kdj != null && !kdj.k().isEmpty()) {
            double k = last(kdj.k());
            double d = last(kdj.d());
            double prevK = kdj.k().size() >= 2 ? kdj.k().get(kdj.k().size() - 2) : k;
            double prevD = kdj.d().size() >= 2 ? kdj.d().get(kdj.d().size() - 2) : d;
            if (prevK <= prevD && k > d) {
                signals.put("kdj", "K 线上穿 D 线形成金叉");
            } else if (prevK >= prevD && k < d) {
                signals.put("kdj", "K 线下穿 D 线形成死叉");
            } else if (k >= 80 && d >= 80) {
                signals.put("kdj", "KDJ 双线处于 80 以上超买区");
            } else if (k <= 20 && d <= 20) {
                signals.put("kdj", "KDJ 双线处于 20 以下超卖区");
            } else {
                signals.put("kdj", "KDJ 处于常态区间，无明确信号");
            }
        }

        if (ma != null && !ma.ma60().isEmpty()) {
            double ma5 = last(ma.ma5());
            double ma10 = last(ma.ma10());
            double ma20 = last(ma.ma20());
            double ma60 = last(ma.ma60());
            if (lastClose > ma5 && ma5 > ma10 && ma10 > ma20 && ma20 > ma60) {
                signals.put("ma", "均线多头排列（价 > MA5 > MA10 > MA20 > MA60），趋势向上");
            } else if (lastClose < ma5 && ma5 < ma10 && ma10 < ma20 && ma20 < ma60) {
                signals.put("ma", "均线空头排列（价 < MA5 < MA10 < MA20 < MA60），趋势向下");
            } else if (lastClose > ma20 && ma20 > ma60) {
                signals.put("ma", "价格站上 MA20 且 MA20 高于 MA60，中期偏强");
            } else if (lastClose < ma20 && ma20 < ma60) {
                signals.put("ma", "价格跌破 MA20 且 MA20 低于 MA60，中期偏弱");
            } else {
                signals.put("ma", "短中期均线相互纠缠，方向不明");
            }
        }

        return signals;
    }

    private double last(List<Double> values) {
        return values.get(values.size() - 1);
    }
}
