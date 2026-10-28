package com.stocklab.analyst.modules.analysis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stocklab.analyst.modules.analysis.entity.AnalysisTask;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * AI 分析任务 Mapper。
 *
 * <p>查询语义简单（按主键取一条），用 MyBatis-Plus 的 {@code BaseMapper}
 * 内置方法即可。清理语句需要按「状态 + 时间」组合条件删除，{@code QueryWrapper}
 * 在这里不比手写 SQL 更清楚，且手写能把索引意图与「为什么不删非终态」写进注释，
 * 因此单独一条 {@code @Delete}。
 */
@Mapper
public interface AnalysisTaskMapper extends BaseMapper<AnalysisTask> {

    /**
     * 清理超期的<b>终态</b>任务。
     *
     * <p>两个条件都是必需的，缺任何一个都会造成数据事故：
     *
     * <ul>
     *   <li><b>{@code status} 必须是终态</b>。清理 {@code PENDING} / {@code RUNNING}
     *       会把「正在执行的任务」的状态行删掉，而后台线程稍后写回状态时会
     *       更新到 0 行——对提交方而言任务凭空消失，还会一直轮询到超时。
     *       所以状态条件不是性能优化，是正确性条件。</li>
     *   <li><b>必须带时间下限</b>。没有它这条语句就是「清空任务表」，
     *       而前端仍在轮询的 taskId 会立刻变成 404。</li>
     * </ul>
     *
     * <p>走 {@code idx_analysis_task_created (created_at)}。
     *
     * <p>不分批：本项目的任务量级是「个人 / 小团队按需提交」，
     * 单次删除的行数远达不到长事务的阈值。若将来变成定时批量提交，
     * 这里需要改成「先按 LIMIT 查 ID 再按 ID 删」的循环，避免一个 DELETE 锁太久。
     *
     * @param cutoffEpochSecond 早于该时间（epoch 秒）的任务才会被删除
     * @return 实际删除的行数
     */
    @Delete("""
            DELETE FROM analysis_tasks
            WHERE status IN ('SUCCESS', 'FAILED')
              AND created_at < #{cutoffEpochSecond}
            """)
    int deleteTerminalOlderThan(@Param("cutoffEpochSecond") long cutoffEpochSecond);
}
