-- =====================================================================
--  DevOps platform · schema V5 — M6 质量管理补全（13 新表 + 5 表增列 + 回填 + 5 指标）
--
--  为什么只有这一版、且落在 req-service：Flyway 只有一个 owner（req-service），
--  deliver-service 没有迁移机制但它与 req 同库，所以质量域的表也只能由这里建。
--
--  幂等约束（T2 验收「空库 / 种子库各迁移两遍不报错」）：
--    * 建表一律 CREATE TABLE IF NOT EXISTS；
--    * 加列/加索引/加 CHECK 先查 information_schema，再用 SET @ddl + PREPARE 动态执行
--      （不用存储过程 → 不需要 DELIMITER，Flyway 与 mysql CLI 都能直接跑）；
--    * 回填全部是「条件插入 / WHERE ... IS NULL」，重复执行不会新增行、不会覆盖已有值。
--
--  类型约定（沿用 V1/V2）：
--    * 0/1 标志列用 INT 而不是 TINYINT —— Hibernate 7 的 ddl-auto=validate 逐列比较
--      JDBC 类型码，实体声明 Integer 而列是 TINYINT 会在启动时直接失败（V2 的教训）；
--    * 枚举样列一律 VARCHAR 码，不用 MySQL ENUM；
--    * JSON 载荷用 TEXT/MEDIUMTEXT（与 deploy_order.admission_result_json → String 一致，
--      validate 不比较 JSON 类型），不做数据库侧 JSON 校验；
--    * 保留字 `release` / `comment` 加反引号，Hibernate 侧配置了 auto_quote_keyword。
-- =====================================================================

SET SESSION group_concat_max_len = 16384;

-- ---------------------------------------------------------------- ① 13 张新表

-- 测试策略：一个 Release 多版本，仅一版 ACTIVE（version_no 递增即历史版本）。
CREATE TABLE IF NOT EXISTS test_strategy (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT          NOT NULL,
    release_id     BIGINT          NOT NULL,
    version_no     INT             NOT NULL DEFAULT 1,
    scope_json     TEXT            NULL,
    risk_json      TEXT            NULL,
    env_ids_json   VARCHAR(500)    NULL,
    round_plan_json TEXT           NULL,
    resource_note  VARCHAR(500)    NULL,
    owner_id       BIGINT          NULL,
    status         VARCHAR(12)     NOT NULL DEFAULT 'DRAFT',
    domain_id      BIGINT          NOT NULL,
    vendor_id      BIGINT          NULL,
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by     BIGINT          NULL,
    updated_by     BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_strategy (tenant_id, release_id, version_no),
    KEY idx_strategy (tenant_id, release_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 轮次：active 列做「同计划仅一轮进行中」的唯一约束。
-- MySQL 唯一键对 NULL 不去重（多行 NULL 都允许），所以进行中写 active=1、关闭写 NULL，
-- 同一计划最多只能有一行 active=1 —— 并发开轮由数据库兜住，而不是靠 Java 先查后插。
CREATE TABLE IF NOT EXISTS test_plan_round (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    plan_id         BIGINT          NOT NULL,
    round_no        INT             NOT NULL,
    round_type      VARCHAR(16)     NOT NULL,
    name            VARCHAR(200)    NULL,
    case_scope_json MEDIUMTEXT      NULL,
    executor_ids_json VARCHAR(500)  NULL,
    start_at        DATETIME(3)     NULL,
    end_at          DATETIME(3)     NULL,
    status          VARCHAR(12)     NOT NULL DEFAULT 'PLANNING',
    conclusion      VARCHAR(12)     NULL,
    closed_by       BIGINT          NULL,
    closed_at       DATETIME(3)     NULL,
    defect_ids_json VARCHAR(500)    NULL,
    exec_total      INT             NOT NULL DEFAULT 0,
    exec_passed     INT             NOT NULL DEFAULT 0,
    exec_failed     INT             NOT NULL DEFAULT 0,
    exec_blocked    INT             NOT NULL DEFAULT 0,
    active          INT             NULL,
    note            VARCHAR(1000)   NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by      BIGINT          NULL,
    updated_by      BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_round (plan_id, round_no),
    UNIQUE KEY uk_round_active (plan_id, active),
    KEY idx_round (tenant_id, plan_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 执行分配：计划级（round_id 为 NULL）或轮次级各一行/人/模块。
-- 唯一键里用生成列 round_key/module_key 而不是裸 round_id/module_id：NULL 不参与唯一比较，
-- 直接用裸列会让「同一计划同一人」重复插入成功，合计校验就永远对不上。
CREATE TABLE IF NOT EXISTS plan_assignment (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT          NOT NULL,
    plan_id      BIGINT          NOT NULL,
    round_id     BIGINT          NULL,
    executor_id  BIGINT          NOT NULL,
    module_id    BIGINT          NULL,
    module_key   BIGINT          AS (IFNULL(module_id, 0)) STORED,
    round_key    BIGINT          AS (IFNULL(round_id, 0)) STORED,
    planned_count INT            NOT NULL,
    due_date     DATE            NULL,
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by   BIGINT          NULL,
    updated_by   BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_assign (plan_id, round_key, executor_id, module_key),
    KEY idx_assign_person (tenant_id, executor_id, due_date),
    KEY idx_assign_plan (tenant_id, plan_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 计划变更留痕：APPROVED 之后的 PATCH / 删用例必须带 reason，与 revision 递增同事务写。
CREATE TABLE IF NOT EXISTS plan_change_log (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT          NOT NULL,
    plan_id      BIGINT          NOT NULL,
    revision     INT             NOT NULL,
    change_type  VARCHAR(24)     NOT NULL,
    before_json  MEDIUMTEXT      NULL,
    after_json   MEDIUMTEXT      NULL,
    reason       VARCHAR(500)    NOT NULL,
    approver_id  BIGINT          NULL,
    approved_at  DATETIME(3)     NULL,
    operator_id  BIGINT          NULL,
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_change (tenant_id, plan_id, revision)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS test_env (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT          NOT NULL,
    name         VARCHAR(100)    NOT NULL,
    env_type     VARCHAR(16)     NOT NULL,
    base_url     VARCHAR(300)    NULL,
    owner_id     BIGINT          NULL,
    status       VARCHAR(12)     NOT NULL DEFAULT 'IDLE',
    domain_id    BIGINT          NOT NULL,
    vendor_id    BIGINT          NULL,
    note         VARCHAR(500)    NULL,
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by   BIGINT          NULL,
    updated_by   BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_env (tenant_id, domain_id, name),
    KEY idx_env_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS test_report (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    code            VARCHAR(24)     NOT NULL,
    scope           VARCHAR(8)      NOT NULL,
    plan_id         BIGINT          NULL,
    release_id      BIGINT          NULL,
    status          VARCHAR(12)     NOT NULL DEFAULT 'SIGNING',
    current_version INT             NOT NULL DEFAULT 1,
    data_cutoff     DATETIME(3)     NOT NULL,
    domain_id       BIGINT          NOT NULL,
    vendor_id       BIGINT          NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by      BIGINT          NULL,
    updated_by      BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_code (tenant_id, code),
    KEY idx_report_release (tenant_id, release_id, scope),
    KEY idx_report_plan (tenant_id, plan_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 报告版本：snapshot_json 是全量快照（8 段），导出旧版本只读它，不再回查业务表，
-- 所以历史版本不会随新执行/新缺陷漂移；result_digest 是聚合口径的指纹（唯一实现在 Java）。
CREATE TABLE IF NOT EXISTS test_report_version (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT          NOT NULL,
    report_id     BIGINT          NOT NULL,
    version_no    INT             NOT NULL,
    snapshot_json MEDIUMTEXT      NOT NULL,
    result_digest CHAR(64)        NOT NULL,
    conclusion    VARCHAR(12)     NOT NULL,
    pass_rate     DECIMAL(6,2)    NOT NULL,
    coverage      DECIMAL(6,2)    NOT NULL,
    generated_by  BIGINT          NULL,
    generated_at  DATETIME(3)     NOT NULL,
    signed_at     DATETIME(3)     NULL,
    status        VARCHAR(12)     NOT NULL DEFAULT 'SIGNING',
    hash          CHAR(64)        NOT NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_version (report_id, version_no),
    KEY idx_version_digest (tenant_id, result_digest)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS report_signoff (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT          NOT NULL,
    report_id  BIGINT          NOT NULL,
    version_no INT             NOT NULL,
    sign_role  VARCHAR(16)     NOT NULL,
    signer_id  BIGINT          NOT NULL,
    decision   VARCHAR(12)     NOT NULL,
    `comment`  VARCHAR(1000)   NULL,
    signed_at  DATETIME(3)     NOT NULL,
    order_no   INT             NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_signoff (report_id, version_no, sign_role)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 质量门配置：一个 Release 一份；求值结果不写在这里，写不可变快照表。
CREATE TABLE IF NOT EXISTS quality_gate (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    release_id      BIGINT          NOT NULL,
    report_id       BIGINT          NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'NOT_EVALUATED',
    ruleset_version INT             NOT NULL DEFAULT 1,
    evaluated_at    DATETIME(3)     NULL,
    created_by      BIGINT          NULL,
    domain_id       BIGINT          NOT NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_gate_release (tenant_id, release_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS quality_gate_rule (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT          NOT NULL,
    gate_id        BIGINT          NOT NULL,
    rule_code      VARCHAR(8)      NOT NULL,
    enabled        INT             NOT NULL DEFAULT 1,
    threshold_json VARCHAR(500)    NULL,
    status         VARCHAR(12)     NULL,
    evidence_json  TEXT            NULL,
    evaluated_at   DATETIME(3)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gate_rule (gate_id, rule_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 门禁快照：只 INSERT，不 UPDATE（AC-7 回放的基础）。
CREATE TABLE IF NOT EXISTS quality_gate_snapshot (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT          NOT NULL,
    gate_id           BIGINT          NOT NULL,
    release_id        BIGINT          NOT NULL,
    deploy_order_id   BIGINT          NOT NULL,
    report_version_id BIGINT          NULL,
    result            VARCHAR(20)     NOT NULL,
    snapshot_json     MEDIUMTEXT      NOT NULL,
    hash              CHAR(64)        NOT NULL,
    computed_at       DATETIME(3)     NOT NULL,
    created_by        BIGINT          NULL,
    PRIMARY KEY (id),
    KEY idx_snap_order (tenant_id, deploy_order_id),
    KEY idx_snap_gate (gate_id, computed_at),
    KEY idx_snap_release (tenant_id, release_id, computed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS quality_gate_waiver (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT          NOT NULL,
    gate_id       BIGINT          NOT NULL,
    rule_code     VARCHAR(8)      NOT NULL,
    release_id    BIGINT          NOT NULL,
    reason        VARCHAR(500)    NOT NULL,
    impact_scope  TEXT            NOT NULL,
    expiry_at     DATETIME(3)     NOT NULL,
    applicant_id  BIGINT          NOT NULL,
    approver_id   BIGINT          NULL,
    approved_at   DATETIME(3)     NULL,
    status        VARCHAR(12)     NOT NULL DEFAULT 'PENDING',
    revoked_by    BIGINT          NULL,
    revoked_at    DATETIME(3)     NULL,
    revoke_reason VARCHAR(500)    NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_waiver_rule (gate_id, rule_code, status),
    KEY idx_waiver_expiry (status, expiry_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Go/No-Go 评审：属 req 的发布决策域（与发布单同库），只存门禁/报告的外键 id，不复制其数据。
CREATE TABLE IF NOT EXISTS release_review (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT          NOT NULL,
    release_id        BIGINT          NOT NULL,
    deploy_order_id   BIGINT          NULL,
    gate_snapshot_id  BIGINT          NULL,
    report_id         BIGINT          NULL,
    report_version_no INT             NULL,
    meeting_at        DATETIME(3)     NOT NULL,
    participants_json VARCHAR(1000)   NULL,
    risks_json        TEXT            NULL,
    open_defects_json TEXT            NULL,
    decision          VARCHAR(12)     NOT NULL,
    conditions        VARCHAR(1000)   NULL,
    doc_path          VARCHAR(300)    NULL,
    gate_evaluated    INT             NOT NULL DEFAULT 0,
    created_by        BIGINT          NULL,
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    confirmed_by      BIGINT          NULL,
    confirmed_at      DATETIME(3)     NULL,
    status            VARCHAR(12)     NOT NULL DEFAULT 'RECORDED',
    PRIMARY KEY (id),
    KEY idx_review (tenant_id, release_id, meeting_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- ② 5 张存量表增列
-- 每张表把「缺的列」拼成一条 ALTER：已迁移过 → @ddl = 'DO 0'（no-op），
-- 只补缺的列 → 种子库上第二次执行不会 duplicate column。

-- 计划：策略/环境/准出条件/审批/变更版本号/结项。
SET @ddl := (
    SELECT COALESCE(CONCAT('ALTER TABLE test_plan ', GROUP_CONCAT(
        CONCAT('ADD COLUMN ', x.col_name, ' ', x.col_def) ORDER BY x.seq SEPARATOR ', ')), 'DO 0')
    FROM (
        SELECT 1 AS seq, 'strategy_id' AS col_name, 'BIGINT NULL' AS col_def
        UNION ALL SELECT 2, 'env_id', 'BIGINT NULL'
        UNION ALL SELECT 3, 'entry_criteria_json', 'TEXT NULL'
        UNION ALL SELECT 4, 'exit_criteria_json', 'TEXT NULL'
        UNION ALL SELECT 5, 'approved_by', 'BIGINT NULL'
        UNION ALL SELECT 6, 'approved_at', 'DATETIME(3) NULL'
        UNION ALL SELECT 7, 'revision', 'INT NOT NULL DEFAULT 0'
        UNION ALL SELECT 8, 'closed_at', 'DATETIME(3) NULL'
        UNION ALL SELECT 9, 'close_reason', 'VARCHAR(500) NULL'
        UNION ALL SELECT 10, 'review_comment', 'VARCHAR(1000) NULL'
    ) AS x
    WHERE NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS c
        WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = 'test_plan' AND c.COLUMN_NAME = x.col_name)
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 执行：轮次/环境归属（分轮视图与「本轮范围」都靠 round_id）。
SET @ddl := (
    SELECT COALESCE(CONCAT('ALTER TABLE test_execution ', GROUP_CONCAT(
        CONCAT('ADD COLUMN ', x.col_name, ' ', x.col_def) ORDER BY x.seq SEPARATOR ', ')), 'DO 0')
    FROM (
        SELECT 1 AS seq, 'round_id' AS col_name, 'BIGINT NULL' AS col_def
        UNION ALL SELECT 2, 'env_id', 'BIGINT NULL'
    ) AS x
    WHERE NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS c
        WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = 'test_execution' AND c.COLUMN_NAME = x.col_name)
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 发布单：准入第 6 项要用到「当时那份」门禁快照，仅靠 Release 级 quality_gate_id 定位不到。
SET @ddl := (
    SELECT COALESCE(CONCAT('ALTER TABLE deploy_order ', GROUP_CONCAT(
        CONCAT('ADD COLUMN ', x.col_name, ' ', x.col_def) ORDER BY x.seq SEPARATOR ', ')), 'DO 0')
    FROM (
        SELECT 1 AS seq, 'quality_gate_id' AS col_name, 'BIGINT NULL' AS col_def
        UNION ALL SELECT 2, 'quality_gate_snapshot_id', 'BIGINT NULL'
        UNION ALL SELECT 3, 'report_id', 'BIGINT NULL'
    ) AS x
    WHERE NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS c
        WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = 'deploy_order' AND c.COLUMN_NAME = x.col_name)
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 缺陷：source 区分测试发现/生产发现/手工录入；is_escape 只在 INSERT 时算一次（AC-8.3）。
SET @ddl := (
    SELECT COALESCE(CONCAT('ALTER TABLE defect ', GROUP_CONCAT(
        CONCAT('ADD COLUMN ', x.col_name, ' ', x.col_def) ORDER BY x.seq SEPARATOR ', ')), 'DO 0')
    FROM (
        SELECT 1 AS seq, 'source' AS col_name, 'VARCHAR(8) NOT NULL DEFAULT ''MANUAL''' AS col_def
        UNION ALL SELECT 2, 'root_cause', 'VARCHAR(16) NULL'
        UNION ALL SELECT 3, 'found_phase', 'VARCHAR(16) NULL'
        UNION ALL SELECT 4, 'is_escape', 'INT NOT NULL DEFAULT 0'
        UNION ALL SELECT 5, 'escape_window_end', 'DATETIME(3) NULL'
    ) AS x
    WHERE NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS c
        WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = 'defect' AND c.COLUMN_NAME = x.col_name)
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 版本：观察窗与报告有效期（AC-8 边界、报告过期）。
SET @ddl := (
    SELECT COALESCE(CONCAT('ALTER TABLE `release` ', GROUP_CONCAT(
        CONCAT('ADD COLUMN ', x.col_name, ' ', x.col_def) ORDER BY x.seq SEPARATOR ', ')), 'DO 0')
    FROM (
        SELECT 1 AS seq, 'observation_hours' AS col_name, 'INT NOT NULL DEFAULT 72' AS col_def
        UNION ALL SELECT 2, 'report_validity_days', 'INT NOT NULL DEFAULT 7'
    ) AS x
    WHERE NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS c
        WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = 'release' AND c.COLUMN_NAME = x.col_name)
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 索引（新列上的查询索引；改过的列不能进已有索引，只能单建）。
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_exec_round ON test_execution (tenant_id, plan_id, round_id, case_id)', 'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'test_execution' AND INDEX_NAME = 'idx_exec_round');
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_deploy_gate ON deploy_order (quality_gate_id)', 'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deploy_order' AND INDEX_NAME = 'idx_deploy_gate');
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_defect_escape ON defect (tenant_id, release_id, is_escape, found_at)', 'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'defect' AND INDEX_NAME = 'idx_defect_escape');
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 观察窗/有效期范围约束（PRD：24–168 小时、1–30 天）。CHECK 失败 = 配置越界，必须 400 而不是静默存。
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `release` ADD CONSTRAINT ck_release_obs CHECK (observation_hours BETWEEN 24 AND 168)', 'DO 0')
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'release' AND CONSTRAINT_NAME = 'ck_release_obs');
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `release` ADD CONSTRAINT ck_release_valid CHECK (report_validity_days BETWEEN 1 AND 30)', 'DO 0')
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'release' AND CONSTRAINT_NAME = 'ck_release_valid');
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- ---------------------------------------------------------------- ③ 7 步幂等回填
-- 顺序不能反：先有默认策略，计划才能拿到 strategy_id；先归集 R1，执行才能挂 round_id；
-- 最后才是缺陷来源与指标行（指标回填只读不写业务表）。

-- 步 1：每个「有计划的 Release」补一版 ACTIVE 策略（已存在则跳过，不覆盖人工改过的 scopes）。
INSERT INTO test_strategy (tenant_id, release_id, version_no, scope_json, status, domain_id,
                           created_at, updated_at)
SELECT p.tenant_id, p.release_id, 1, '{"source":"backfill"}', 'ACTIVE', r.domain_id, NOW(3), NOW(3)
FROM (SELECT DISTINCT tenant_id, release_id FROM test_plan) p
         JOIN `release` r ON r.id = p.release_id AND r.tenant_id = p.tenant_id
WHERE NOT EXISTS (SELECT 1
                  FROM test_strategy s
                  WHERE s.tenant_id = p.tenant_id
                    AND s.release_id = p.release_id);

-- 步 2：计划补字段 —— strategy_id 指向同 Release 的 ACTIVE 策略（取版本最高的一版），
-- 清出/准入条件为空时写默认 JSON（与 ExitCriteriaEvaluator 的 key 一一对应）。
UPDATE test_plan
SET strategy_id = (SELECT s.id
                   FROM test_strategy s
                   WHERE s.tenant_id = test_plan.tenant_id
                     AND s.release_id = test_plan.release_id
                     AND s.status = 'ACTIVE'
                   ORDER BY s.version_no DESC
                   LIMIT 1)
WHERE strategy_id IS NULL
  AND EXISTS (SELECT 1
              FROM test_strategy s
              WHERE s.tenant_id = test_plan.tenant_id
                AND s.release_id = test_plan.release_id
                AND s.status = 'ACTIVE');

UPDATE test_plan
SET exit_criteria_json = '{"items":[{"key":"passRate","op":">=","value":95},{"key":"openP0P1","op":"==","value":0},{"key":"roundClosed","op":"==","value":1}]}'
WHERE exit_criteria_json IS NULL;

UPDATE test_plan
SET entry_criteria_json = '{"items":[{"key":"strategyActive","op":"==","value":1}]}'
WHERE entry_criteria_json IS NULL;

-- 步 3：状态映射。存量 r2 用 RUNNING/DONE，新状态机是 IN_PROGRESS/COMPLETED（取代 r2#AC-2）。
-- 存量计划不自动进 PENDING_REVIEW：那会把 r2 已通过的计划重新变成待审批，等于回滚门禁。
UPDATE test_plan SET status = 'IN_PROGRESS' WHERE status = 'RUNNING';
UPDATE test_plan SET status = 'COMPLETED'   WHERE status = 'DONE';

-- 步 4：R1 归集 —— 存量执行没有轮次，全部记进「R1（存量执行归集）」，exec_* 按 plan_case 现况落快照。
-- 只补没有 round_no=1 的计划；重复执行不新增行。
INSERT INTO test_plan_round (tenant_id, plan_id, round_no, round_type, name, status,
                             start_at, closed_at, note, exec_total, exec_passed, exec_failed,
                             exec_blocked, active, created_at, updated_at)
SELECT p.tenant_id,
       p.id,
       1,
       'FEATURE',
       'R1 存量执行归集',
       'CLOSED',
       p.created_at,
       NOW(3),
       '存量执行归集',
       (SELECT COUNT(*) FROM test_plan_case c WHERE c.plan_id = p.id),
       (SELECT COUNT(*) FROM test_plan_case c WHERE c.plan_id = p.id AND c.result = 'PASS'),
       (SELECT COUNT(*) FROM test_plan_case c WHERE c.plan_id = p.id AND c.result = 'FAIL'),
       (SELECT COUNT(*) FROM test_plan_case c WHERE c.plan_id = p.id AND c.result = 'BLOCKED'),
       NULL,
       NOW(3),
       NOW(3)
FROM test_plan p
WHERE NOT EXISTS (SELECT 1 FROM test_plan_round r WHERE r.plan_id = p.id AND r.round_no = 1);

UPDATE test_execution e
    JOIN test_plan_round r
    ON r.tenant_id = e.tenant_id AND r.plan_id = e.plan_id AND r.round_no = 1
SET e.round_id = r.id
WHERE e.round_id IS NULL
  AND e.plan_id IS NOT NULL;

-- 步 5：缺陷来源 —— 由执行记录带出来的缺陷是测试发现（TEST），其余保持手工/生产录入。
-- 只改 MANUAL，避免第二次执行把已明确为 PROD 的来源覆盖掉。
UPDATE defect SET source = 'TEST' WHERE source = 'MANUAL' AND test_execution_id IS NOT NULL;

-- 步 6：旧发布单的 quality_gate_*/report_id 保持 NULL —— 详情页显式「历史单据（质量门未启用）」，
-- 这里刻意不写任何值，也刻意不伪造 PASS。故无语句。

-- 步 7：5 行质量指标（每租户），owner 取 PMO，无则平台管理员，再退到租户第一个账号。
INSERT INTO metric_definition (tenant_id, code, name, topic, formula, datasource, caliber_note,
                               owner_id, version, effective_at, created_at, updated_at)
SELECT t.id,
       m.code,
       m.name,
       'quality',
       m.formula,
       'deliver.quality',
       m.note,
       COALESCE(
           (SELECT u.id FROM sys_user u
                     JOIN sys_user_role ur ON ur.user_id = u.id AND ur.tenant_id = u.tenant_id
                     JOIN sys_role r ON r.id = ur.role_id AND r.code = 'PMO'
            WHERE u.tenant_id = t.id ORDER BY u.id LIMIT 1),
           (SELECT u.id FROM sys_user u
                     JOIN sys_user_role ur ON ur.user_id = u.id AND ur.tenant_id = u.tenant_id
                     JOIN sys_role r ON r.id = ur.role_id AND r.code = 'PLATFORM_ADMIN'
            WHERE u.tenant_id = t.id ORDER BY u.id LIMIT 1),
           (SELECT u.id FROM sys_user u WHERE u.tenant_id = t.id ORDER BY u.id LIMIT 1)),
       1,
       NOW(3),
       NOW(3),
       NOW(3)
FROM sys_tenant t
         JOIN (SELECT 'FPY' AS code, '首次通过率' AS name,
                      'fpy(firstRoundPassed, firstRoundExecuted)' AS formula,
                      'R1 通过数 / R1 已执行数；分母 0 返回 null（显示为 —）' AS note
               UNION ALL
               SELECT 'REGRESSION_PASS_RATE', '回归通过率',
                      'regressionPassRate(passed, executed)',
                      'R2 及以后轮次通过数 / 已执行数；分母 0 返回 null'
               UNION ALL
               SELECT 'DEFECT_ESCAPE_RATE', '缺陷逃逸率',
                      'escapeRate(escaped, total)',
                      'is_escape=1 的缺陷 / 该版本关联缺陷总数；分母 0 返回 null'
               UNION ALL
               SELECT 'POST_RELEASE_DEFECTS_72H', '发布后 72h 缺陷数',
                      'count(defect where found_at in window)',
                      '观察窗内创建的关联缺陷数；窗口起点=发布单执行成功 ended_at'
               UNION ALL
               SELECT 'ROLLBACK_RATE', '回滚率',
                      'rollbackRate(rolledBack, executed)',
                      '回滚发布单数 / 已执行发布单数；分母 0 返回 null') m
WHERE NOT EXISTS (SELECT 1
                  FROM metric_definition d
                  WHERE d.tenant_id = t.id AND d.code = m.code AND d.version = 1);
