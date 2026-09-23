-- ============================================================================
-- 股票分析师系统 · H2 开发库建表脚本
--
-- 用途：本地开发与演示。默认 profile 为 dev，应用启动时自动执行本脚本。
--       H2 使用内存库，进程退出即清空——每次重启都是一份干净的初始数据。
--
-- 与 MySQL 版本的差异（schema-mysql.sql）：
--   * 无 ENGINE / CHARSET 子句
--   * 时间字段用 BIGINT（epoch 秒），两边一致，避免时区与驱动差异
--   * 不使用 AUTO_INCREMENT（主键为 UUID 字符串，由应用生成）
-- ============================================================================

-- 先删后建，保证重复启动时结构一致（H2 内存库一般无残留，但显式写更稳）
DROP TABLE IF EXISTS holdings;
DROP TABLE IF EXISTS ai_reports;
DROP TABLE IF EXISTS watchlist_items;
DROP TABLE IF EXISTS watchlist_groups;
DROP TABLE IF EXISTS users;

-- ---------------------------------------------------------------------------
-- 用户
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            VARCHAR(36)  NOT NULL,
    -- 手机号作为登录名。唯一约束是「账号不重复」的最终保证：
    -- 应用层的 countByPhone 只能用于友好提示，不能用于并发下的唯一性判断。
    phone         VARCHAR(20)  NOT NULL,
    -- BCrypt 哈希，长度固定 60 字符；留 100 以容纳未来更换算法（如 Argon2）
    password_hash VARCHAR(100) NOT NULL,
    nickname      VARCHAR(50),
    created_at    BIGINT       NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_phone UNIQUE (phone)
);

-- ---------------------------------------------------------------------------
-- 自选股分组
-- ---------------------------------------------------------------------------
CREATE TABLE watchlist_groups (
    id         VARCHAR(36) NOT NULL,
    user_id    VARCHAR(36) NOT NULL,
    name       VARCHAR(50) NOT NULL,
    sort_order INT         NOT NULL DEFAULT 0,
    -- 父分组 ID，支持两级分组；NULL 表示顶级分组
    parent_id  VARCHAR(36),
    PRIMARY KEY (id),
    -- 同一用户下分组名不可重复。应用层懒创建默认分组时靠它兜住并发。
    CONSTRAINT uk_watchlist_group_user_name UNIQUE (user_id, name)
);

CREATE INDEX idx_watchlist_group_user ON watchlist_groups (user_id);

-- ---------------------------------------------------------------------------
-- 自选股条目
-- ---------------------------------------------------------------------------
CREATE TABLE watchlist_items (
    id         VARCHAR(36) NOT NULL,
    user_id    VARCHAR(36) NOT NULL,
    group_id   VARCHAR(36) NOT NULL,
    code       VARCHAR(12) NOT NULL,
    sort_order INT         NOT NULL DEFAULT 0,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (id),
    -- 同一用户同一只股票只能有一条记录（跨分组也算重复），
    -- 去重完全依赖这个约束 + 应用层捕获冲突，不做「先查后插」
    CONSTRAINT uk_watchlist_item_user_code UNIQUE (user_id, code)
);

CREATE INDEX idx_watchlist_item_group ON watchlist_items (user_id, group_id);

-- ---------------------------------------------------------------------------
-- AI 分析报告存档
-- ---------------------------------------------------------------------------
CREATE TABLE ai_reports (
    id          VARCHAR(36) NOT NULL,
    -- 触发者；游客触发时为 NULL。仅作审计字段，不参与查询过滤
    user_id     VARCHAR(36),
    code        VARCHAR(12) NOT NULL,
    -- 报告全文 JSON。用 CLOB（对应 MySQL 的 TEXT）而不是 VARCHAR(4000)，
    -- 避免报告变长后被截断成非法 JSON
    report_json CLOB        NOT NULL,
    -- 实际产出报告的模型名，便于回溯「同一标的的结论由哪个模型给出」
    model       VARCHAR(64) NOT NULL,
    created_at  BIGINT      NOT NULL,
    PRIMARY KEY (id)
);

-- 支撑「按代码取最新一份」与历史列表
CREATE INDEX idx_ai_report_code_created ON ai_reports (code, created_at);

-- ---------------------------------------------------------------------------
-- 持仓
-- ---------------------------------------------------------------------------
CREATE TABLE holdings (
    id         VARCHAR(36)   NOT NULL,
    user_id    VARCHAR(36)   NOT NULL,
    code       VARCHAR(12)   NOT NULL,
    name       VARCHAR(50),
    -- 股数。不存金额，市值由「股数 × 行情」实时计算
    shares     INT           NOT NULL DEFAULT 0,
    -- 加权平均成本，保留 4 位小数
    avg_cost   DECIMAL(18, 4) NOT NULL DEFAULT 0,
    -- 买入日期（YYYY-MM-DD），用户记账口径，可补录历史
    buy_date   VARCHAR(10),
    created_at BIGINT        NOT NULL,
    updated_at BIGINT        NOT NULL,
    PRIMARY KEY (id),
    -- 每只股票一行。这个约束是「并发首次买入」正确性的必要条件，
    -- 详见 HoldingService#insertNewHolding 的说明
    CONSTRAINT uk_holding_user_code UNIQUE (user_id, code)
);

CREATE INDEX idx_holding_user ON holdings (user_id);
