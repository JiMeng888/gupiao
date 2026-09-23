-- ============================================================================
-- 股票分析师系统 · MySQL 8 建表脚本
--
-- 使用方式（数据库需先手动创建，见文件末尾）：
--   mysql -u<user> -p < schema-mysql.sql
--
-- 设计约定：
--   1. 主键统一为 VARCHAR(36) 的 UUID 字符串，由应用侧生成。
--      不用自增整数是为了让「自选股条目 ID」这类标识出现在 URL 里时
--      不泄漏业务规模，也便于将来做多库合并/数据迁移。
--   2. 时间统一用 BIGINT 存 epoch 秒。
--      不用 DATETIME 的理由：跨时区部署时 DATETIME 不带时区信息，
--      驱动与服务端的 timezone 参数不一致就会静默偏移几小时；
--      epoch 秒是绝对时间，不存在这个问题。展示时区由前端决定。
--   3. utf8mb4：股票名称与资讯标题里会出现生僻字（如「㟃」「甪」）与 emoji，
--      utf8（3 字节）会直接报错或截断，utf8mb4 是唯一正确选择。
--   4. 所有唯一约束都是应用层逻辑正确性的一部分，不只是「性能优化」，
--      详见各表注释。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 用户
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id            VARCHAR(36)  NOT NULL COMMENT '用户 ID（UUID）',
    phone         VARCHAR(20)  NOT NULL COMMENT '手机号，登录名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希（60 字符，留余量以支持未来更换算法）',
    nickname      VARCHAR(50)      NULL COMMENT '昵称',
    created_at    BIGINT       NOT NULL COMMENT '创建时间（epoch 秒）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户';

-- ---------------------------------------------------------------------------
-- 自选股分组
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS watchlist_groups (
    id         VARCHAR(36) NOT NULL COMMENT '分组 ID（UUID）',
    user_id    VARCHAR(36) NOT NULL COMMENT '归属用户',
    name       VARCHAR(50) NOT NULL COMMENT '分组名称',
    sort_order INT         NOT NULL DEFAULT 0 COMMENT '排序权重，越小越靠前',
    parent_id  VARCHAR(36)     NULL COMMENT '父分组 ID，支持两级分组；NULL 为顶级',
    PRIMARY KEY (id),
    UNIQUE KEY uk_watchlist_group_user_name (user_id, name),
    KEY idx_watchlist_group_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '自选股分组';

-- ---------------------------------------------------------------------------
-- 自选股条目
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS watchlist_items (
    id         VARCHAR(36) NOT NULL COMMENT '条目 ID（UUID）',
    user_id    VARCHAR(36) NOT NULL COMMENT '归属用户（数据隔离字段）',
    group_id   VARCHAR(36) NOT NULL COMMENT '所属分组',
    code       VARCHAR(12) NOT NULL COMMENT '股票代码（6 位数字，如 600519）',
    sort_order INT         NOT NULL DEFAULT 0 COMMENT '组内排序权重',
    created_at BIGINT      NOT NULL COMMENT '创建时间（epoch 秒）',
    PRIMARY KEY (id),
    -- 同一用户同一只股票只允许一条记录（跨分组也算重复）。
    -- 去重完全依赖这个约束：应用层捕获 DuplicateKeyException 转换为 409，
    -- 不做「先 SELECT 再 INSERT」（那个写法在并发下必然产生重复数据）
    UNIQUE KEY uk_watchlist_item_user_code (user_id, code),
    KEY idx_watchlist_item_group (user_id, group_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '自选股条目';

-- ---------------------------------------------------------------------------
-- AI 分析报告存档
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_reports (
    id          VARCHAR(36) NOT NULL COMMENT '报告 ID（UUID）',
    user_id     VARCHAR(36)     NULL COMMENT '触发者；游客触发为 NULL，仅作审计',
    code        VARCHAR(12) NOT NULL COMMENT '股票代码',
    report_json TEXT        NOT NULL COMMENT '报告全文 JSON',
    model       VARCHAR(64) NOT NULL COMMENT '产出报告的模型名（rule-based 表示规则化降级）',
    created_at  BIGINT      NOT NULL COMMENT '生成时间（epoch 秒）',
    PRIMARY KEY (id),
    -- 支撑「取某标的的最新一份报告」与历史列表。
    -- 这条索引是成本控制的一部分：同一标的的报告对所有用户复用，
    -- 命中索引后就不需要再调用一次大模型
    KEY idx_ai_report_code_created (code, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'AI 分析报告存档';

-- ---------------------------------------------------------------------------
-- 持仓
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS holdings (
    id         VARCHAR(36)    NOT NULL COMMENT '持仓 ID（UUID）',
    user_id    VARCHAR(36)    NOT NULL COMMENT '归属用户（数据隔离字段）',
    code       VARCHAR(12)    NOT NULL COMMENT '股票代码',
    name       VARCHAR(50)        NULL COMMENT '股票名称快照',
    shares     INT            NOT NULL DEFAULT 0 COMMENT '持有股数',
    avg_cost   DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '加权平均成本',
    buy_date   VARCHAR(10)        NULL COMMENT '买入日期（YYYY-MM-DD，用户记账口径，可补录历史）',
    created_at BIGINT         NOT NULL COMMENT '建仓时间（epoch 秒）',
    updated_at BIGINT         NOT NULL COMMENT '最后变动时间（epoch 秒）',
    PRIMARY KEY (id),
    -- 每只股票一行。这是并发首次买入正确性的必要条件：
    -- SELECT ... FOR UPDATE 锁不住「还不存在的行」，两个并发请求会双双 INSERT，
    -- 产生同一标的的两行记录，之后按唯一结果读取会抛 TooManyResultsException。
    -- 应用层捕获此约束的冲突后退化为加仓，等价于串行执行
    UNIQUE KEY uk_holding_user_code (user_id, code),
    KEY idx_holding_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '持仓（每只股票一行，加权平均成本法）';

-- ============================================================================
-- 首次部署提示
--
-- 1) 创建库（脚本本身不建库，避免误删既有同名库）：
--      CREATE DATABASE stock_analyst
--        DEFAULT CHARACTER SET utf8mb4
--        DEFAULT COLLATE utf8mb4_0900_ai_ci;
--
-- 2) 建议单独建一个只对本库有权限的应用账号，不要用 root：
--      CREATE USER 'stockapp'@'%' IDENTIFIED BY '<强密码>';
--      GRANT SELECT, INSERT, UPDATE, DELETE ON stock_analyst.* TO 'stockapp'@'%';
--      FLUSH PRIVILEGES;
--    （DDL 权限没有授予——建表由本脚本手工执行，应用只做 DML。）
--
-- 3) 应用侧配置（环境变量，不要写进配置文件）：
--      SPRING_PROFILES_ACTIVE=mysql
--      DB_URL=jdbc:mysql://localhost:3306/stock_analyst?useUnicode=true&characterEncoding=utf8mb4&serverTimezone=Asia/Shanghai
--      DB_USERNAME=stockapp
--      DB_PASSWORD=<强密码>
--      APP_JWT_SECRET=<至少 32 字符的随机串>
-- ============================================================================
