package com.stocklab.analyst.market;

import com.stocklab.analyst.market.model.Bar;
import com.stocklab.analyst.market.model.IndexSnapshot;
import com.stocklab.analyst.market.model.MarketPeriod;
import com.stocklab.analyst.market.model.Mover;
import com.stocklab.analyst.market.model.MoverMetric;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.market.model.StockBrief;

import java.util.List;
import java.util.Map;

/**
 * 行情数据源抽象。
 *
 * <p><b>本包（{@code market}）是整个工程中唯一允许调用外部行情 HTTP 接口的地方。</b>
 * 业务层只依赖本接口，因此更换数据源（东方财富 / 新浪 / 同花顺 / 商业 API）
 * 只需新增一个实现类并在 {@code MarketDataSupportConfig} 中注册，
 * Service 与 Controller 无需任何改动。
 *
 * <p>实现约定：
 * <ol>
 *   <li>代码无法识别或上游明确表示标的不存在时抛
 *       {@link com.stocklab.analyst.common.exception.ErrorCode#QUOTE_NOT_FOUND}；
 *       不要把「查不到」和「上游炸了」混成同一个异常——前者是 404，后者是 5xx，
 *       前端的处理完全不同（提示改代码 vs 提示稍后重试）。</li>
 *   <li>上游网络异常、超时、返回结构异常时抛 {@code MarketDataUnavailableException}
 *       （继承自 {@code BizException}，映射为 503），由路由器决定是否降级。</li>
 *   <li>返回的集合永不为 {@code null}；无数据时返回空集合。</li>
 * </ol>
 */
public interface MarketDataProvider {

    /** 数据源标识，用于日志与 /source 端点展示。 */
    String name();

    /** 是否具备服务能力（如真实源在其依赖不可达时可返回 false）。 */
    default boolean available() {
        return true;
    }

    /**
     * 各操作<b>当前实际</b>由谁提供服务，键为操作名（{@code getQuote} / {@code getBars} …），
     * 值为数据源标识。
     *
     * <p>为什么需要「按操作」而不是一个全局标识：降级是<b>逐次调用</b>发生的。
     * 真实场景里完全可能出现「K 线接口可用、实时报价接口被风控拦截」——
     * 此时同一个时刻，{@code getBars} 来自真实源、{@code getQuote} 来自降级源。
     * 用一个全局字段描述这种状态，无论填哪个都会误导一半的调用方。
     *
     * <p>默认返回空 Map，表示「本实现不存在降级概念」。
     */
    default Map<String, String> servingByAction() {
        return Map.of();
    }

    /**
     * 当前是否存在「实际由降级源提供服务」的操作。
     *
     * <p>前端据此决定是否要给行情数字打上「演示数据」标记。
     * 默认 {@code false}：不涉及降级的实现永远处于未降级状态。
     */
    default boolean degraded() {
        return false;
    }

    /**
     * 查询单只标的实时报价。
     *
     * @param code 已规范化的代码（如 {@code 600519} 或 {@code 000001.SH}）
     */
    Quote getQuote(String code);

    /**
     * 查询 K 线序列，按时间升序返回。
     *
     * @param limit 期望的最大根数，由调用方保证在合理区间内
     */
    List<Bar> getBars(String code, MarketPeriod period, int limit);

    /** 大盘指数概览。 */
    List<IndexSnapshot> getIndices();

    /** 按代码或名称模糊检索标的。 */
    List<StockBrief> search(String keyword);

    /**
     * 市场榜单：按指定维度排序的 A 股前列标的。
     *
     * <p><b>上游事实（2026-09-19 实测）</b>：榜单走的是与「全 A 股清单预热」
     * 同款的 {@code clist/get}（{@code fid} 指定排序字段、{@code po} 指定方向、
     * {@code pz} 指定条数），三个方向都实测返回真实数据。
     * 注意带 {@code .np} 的变体（{@code clist.np/get}、{@code ulist.np/get + fs}）
     * 对无 Cookie 的服务端请求<b>不可用</b>（空响应 / {@code rc:102, data:null}）——
     * 不要「顺手统一」成 .np 形态。
     *
     * @param metric    排序维度；{@code CHANGE_PCT} 配合 {@code ascending=true}
     *                  即跌幅榜（前 N 名 = 跌得最多的 N 只）
     * @param ascending false 取最大值一侧（涨幅榜 / 成交额榜），true 取最小值一侧
     * @param limit     期望条数，实现方负责收敛到合理区间（1..20）
     */
    List<Mover> getTopMovers(MoverMetric metric, boolean ascending, int limit);

    /**
     * 启动预热（可选）。
     *
     * <p>用于把全市场代码清单等「读多写极少」的数据提前加载到内存，
     * 使搜索请求不必访问上游。实现应保证幂等，且失败不阻断应用启动。
     */
    default void warmup() {
    }
}
