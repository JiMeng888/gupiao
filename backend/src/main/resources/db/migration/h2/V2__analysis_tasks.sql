-- ============================================================================
-- V2 · AI 分析异步任务表（H2 方言，与 mysql/V2__analysis_tasks.sql 结构等价）
-- ============================================================================

CREATE TABLE analysis_tasks (
    id          VARCHAR(36)  NOT NULL,
    user_id     VARCHAR(36),
    code        VARCHAR(12)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    report_json CLOB,
    model       VARCHAR(64),
    error_msg   VARCHAR(500),
    created_at  BIGINT       NOT NULL,
    updated_at  BIGINT       NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_analysis_task_code_created ON analysis_tasks (code, created_at);
CREATE INDEX idx_analysis_task_created ON analysis_tasks (created_at);
