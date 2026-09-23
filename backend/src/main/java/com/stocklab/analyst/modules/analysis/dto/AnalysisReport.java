package com.stocklab.analyst.modules.analysis.dto;

import java.util.List;

/**
 * 结构化 AI 分析报告。
 *
 * <h2>相对原 FastAPI 版本的字段命名变更</h2>
 * 原实现使用 Pydantic 的 snake_case（{@code bull_case} / {@code valuation_note} /
 * {@code action_hint} / {@code generated_at}），Java 侧统一用 camelCase。
 * 前端由本项目自行实现，因此不需要为兼容而保留 snake_case。
 *
 * <h2>三个必须展示给用户的字段</h2>
 * <ul>
 *   <li>{@code model} —— 实际产出该报告的模型名。<b>{@code rule-based} 表示没有调用大模型</b>，
 *       而是由规则拼装（无 API Key，或调用失败后降级）。前端必须把这个值显示出来，
 *       否则用户会把规则化文案当成大模型分析。</li>
 *   <li>{@code fromArchive} —— 是否直接读取的历史存档。{@code true} 时表示这份报告
 *       不是刚刚生成的，配合 {@code generatedAt} 判断是否已过时。</li>
 *   <li>{@code notice} —— 免责声明或降级原因说明。</li>
 * </ul>
 *
 * <h2>dataBasis 的价值</h2>
 * 列出本次分析真正喂给模型的输入项（现价 / K 线区间 / 技术指标 / 财务指标）。
 * 大模型最容易露馅的地方就是「凭常识编一个 PE 数字」——把输入清单摆在页面上，
 * 用户能一眼看出哪些结论有数据支撑、哪些是泛泛而谈。
 *
 * @param code          股票代码
 * @param name          股票名称
 * @param generatedAt   生成时间，ISO-8601 带时区偏移
 * @param thesis        核心论点
 * @param bullCase      看多逻辑
 * @param bearCase      看空逻辑
 * @param risks         风险因素
 * @param valuationNote 估值参考
 * @param actionHint    操作建议
 * @param model         产出该报告的模型名；{@code rule-based} 表示规则化降级
 * @param fromArchive   是否来自历史存档（未重新生成）
 * @param dataBasis     本次分析使用的数据清单
 * @param notice        免责声明或降级说明
 */
public record AnalysisReport(
        String code,
        String name,
        String generatedAt,
        String thesis,
        List<String> bullCase,
        List<String> bearCase,
        List<String> risks,
        String valuationNote,
        String actionHint,
        String model,
        boolean fromArchive,
        List<String> dataBasis,
        String notice
) {
}
