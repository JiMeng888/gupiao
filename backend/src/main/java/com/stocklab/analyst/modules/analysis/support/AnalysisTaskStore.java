package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.modules.analysis.dto.AnalysisReport;
import com.stocklab.analyst.modules.analysis.entity.AnalysisTask;
import com.stocklab.analyst.modules.analysis.entity.AnalysisTaskStatus;
import com.stocklab.analyst.modules.analysis.mapper.AnalysisTaskMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * AI 分析异步任务的持久化。
 *
 * <p>职责单一：把任务的「创建 / 状态流转 / 结果写回 / 查询」收在一处，
 * 让 {@code AnalysisService} 只关心业务编排、{@code AnalysisTaskExecutor}
 * 只关心「跑一次生成」。状态机的唯一事实来源是 {@link AnalysisTaskStatus}。
 *
 * <h2>一个贯穿的原则：落库失败不阻塞主流程</h2>
 * 报告已经生成好了，归档/写状态失败只记日志——不能让一次数据库抖动
 * 把已经拿到手的分析结果丢给用户。查询失败当作「任务不存在」处理。
 */
@Component
public class AnalysisTaskStore {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskStore.class);

    private final AnalysisTaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    public AnalysisTaskStore(AnalysisTaskMapper taskMapper, ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
    }

    /** 创建一条 PENDING 任务，返回任务 ID。 */
    public String create(String code, String userId) {
        AnalysisTask task = new AnalysisTask();
        task.setCode(code);
        task.setUserId(userId);
        task.setStatus(AnalysisTaskStatus.PENDING.name());
        taskMapper.insert(task);
        return task.getId();
    }

    /** 任务进入 RUNNING。 */
    public void markRunning(String taskId) {
        updateStatus(taskId, AnalysisTaskStatus.RUNNING, null, null);
    }

    /** 任务成功，写回报告与模型名。 */
    public void markSuccess(String taskId, AnalysisReport report) {
        try {
            String json = objectMapper.writeValueAsString(report);
            updateStatus(taskId, AnalysisTaskStatus.SUCCESS, json, report.model());
        } catch (RuntimeException ex) {
            // 序列化失败理论上不会发生（报告字段都是简单类型），但兜底记 FAILED 而非让后台线程抛异常
            log.warn("任务 {} 报告序列化失败，标记为 FAILED：{}", taskId, ex.getMessage());
            updateStatus(taskId, AnalysisTaskStatus.FAILED, null, "报告序列化失败");
        }
    }

    /** 任务失败，写回失败原因。 */
    public void markFailed(String taskId, String error) {
        updateStatus(taskId, AnalysisTaskStatus.FAILED, null, truncate(error));
    }

    /** 按 ID 查询任务（不带归属校验，仅供后台执行器内部使用）。 */
    public Optional<AnalysisTask> findById(String taskId) {
        return Optional.ofNullable(taskMapper.selectById(taskId));
    }

    /**
     * 按「任务 ID + 提交者」查询任务。
     *
     * <h2>为什么查询必须带归属条件</h2>
     * 任务 ID 是客户端可见的（提交后返回、轮询时带上），只按 ID 查等于
     * 任何登录用户都能读到他人任务的分析报告——这是对象级越权（IDOR）。
     * 报告内容虽不含个人信息，但「谁在看什么股票」本身属于用户行为数据，
     * 不应无条件开放。
     *
     * <p>归属条件写进 SQL 而不是「先查出来再比对」：后者在并发下依赖
     * 应用层判断，且一旦将来有人复制这段代码就漏掉校验。
     *
     * @param userId 提交者；{@code null} 表示匿名提交，此时只匹配匿名任务
     */
    public Optional<AnalysisTask> findOwnedById(String taskId, String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.ofNullable(taskMapper.selectOne(Wrappers.<AnalysisTask>lambdaQuery()
                    .eq(AnalysisTask::getId, taskId)
                    .isNull(AnalysisTask::getUserId)));
        }
        return Optional.ofNullable(taskMapper.selectOne(Wrappers.<AnalysisTask>lambdaQuery()
                .eq(AnalysisTask::getId, taskId)
                .eq(AnalysisTask::getUserId, userId)));
    }

    /**
     * 查询任务并把 {@code reportJson} 反序列化为报告。
     *
     * <p>反序列化失败当作「报告不可用」处理（返回 FAILED 视图），
     * 而不是抛异常让轮询接口 500。
     */
    public AnalysisReport parseReport(AnalysisTask task) {
        if (task.getReportJson() == null || task.getReportJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(task.getReportJson(), AnalysisReport.class);
        } catch (RuntimeException ex) {
            log.warn("任务 {} 报告反序列化失败：{}", task.getId(), ex.getMessage());
            return null;
        }
    }

    // ==================================================================

    private void updateStatus(String taskId, AnalysisTaskStatus status,
                              String reportJson, String model) {
        AnalysisTask patch = new AnalysisTask();
        patch.setId(taskId);
        patch.setStatus(status.name());
        if (reportJson != null) {
            patch.setReportJson(reportJson);
        }
        if (model != null) {
            patch.setModel(model);
        }
        // 只更新非空字段，避免把 reportJson / model 意外清空
        taskMapper.updateById(patch);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "未知错误";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }
}
