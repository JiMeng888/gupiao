-- ============================================================================
-- V2 · AI 分析异步任务表
--
-- 为什么需要这张表：原先 POST /analysis/{code} 在 HTTP 请求线程上同步等待
-- 大模型返回（最长 45 秒），约 5 个并发就能吃掉大半 Tomcat 线程池。
-- 异步化后，请求线程只负责「校验代码 + 投递任务 + 立刻返回 taskId」，
-- 真正的取数 + 调模型 + 归档在后台线程执行，结果按 taskId 落库，
-- 前端轮询 taskId 即可拿到进度与最终报告。
--
-- 与 ai_reports 的关系：ai_reports 是「报告的最终存档」（按 code 复用，
-- 一份报告服务所有用户）；analysis_tasks 是「一次提交的生命周期」，
-- 记录 PENDING → RUNNING → SUCCESS / FAILED 的状态流转，两者职责不同，
-- 不合并。
-- ============================================================================

CREATE TABLE IF NOT EXISTS analysis_tasks (
    id          VARCHAR(36)  NOT NULL COMMENT '任务 ID（UUID）',
    user_id     VARCHAR(36)      NULL COMMENT '触发者；游客触发为 NULL',
    code        VARCHAR(12)  NOT NULL COMMENT '股票代码（6 位数字）',
    status      VARCHAR(16)  NOT NULL COMMENT '任务状态：PENDING / RUNNING / SUCCESS / FAILED',
    report_json TEXT             NULL COMMENT '成功后的报告全文 JSON；未完成时为 NULL',
    model       VARCHAR(64)      NULL COMMENT '产出报告的模型名（rule-based 表示规则化降级）',
    error_msg   VARCHAR(500)     NULL COMMENT '失败原因（面向用户的简短说明）',
    created_at  BIGINT       NOT NULL COMMENT '创建时间（epoch 秒）',
    updated_at  BIGINT       NOT NULL COMMENT '最后更新时间（epoch 秒）',
    PRIMARY KEY (id),
    -- 支撑「查询某个用户最近提交的任务」与「按状态清理过期任务」。
    -- 报告正文不与用户绑定（同一标的结论对所有用户一致），因此查询不带 user 条件，
    -- 但保留 user_id 作审计，索引按 code 组织以对齐轮询热点
    KEY idx_analysis_task_code_created (code, created_at),
    KEY idx_analysis_task_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'AI 分析异步任务';
