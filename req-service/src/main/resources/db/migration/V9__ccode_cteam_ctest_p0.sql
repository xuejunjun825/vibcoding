-- =====================================================================
--  DevOps platform · schema V9 — M8 协作 / 代码 / 测试治理三项 P0
--
--  单文件、顺序固定：① 11 张新表 → ② 判空加列 → ③ 索引 → ④ 历史回填 → ⑤ 种子 → ⑥ 校验。
--  顺序不能乱：回填要读 audit_log 的新索引（③ 之前建好才不扫全表），种子要引用 ① 的新表。
--
--  幂等约束（T1 验收「空库 / 已有库各迁移一次，行数一致」）：
--    * 建表一律 CREATE TABLE IF NOT EXISTS；
--    * 加列 / 加索引先查 information_schema，再用 SET @ddl + PREPARE 动态执行
--      （不用存储过程 → 不需要 DELIMITER，Flyway 与 mysql CLI 都能直接跑）；
--    * 回填只填 NULL（重复执行不会覆盖已写入的实际期）；
--    * 种子全部 INSERT ... SELECT ... WHERE NOT EXISTS（按租户种，天然幂等）。
--
--  类型约定（沿用 V1/V5，见 V5 头注释的硬教训）：
--    * 0/1 标志列与计数列用 INT，不用 TINYINT —— Hibernate 7 ddl-auto=validate 逐列比较
--      JDBC 类型码，实体声明 Integer 而列是 TINYINT 会在启动时直接失败（V2 的教训）；
--    * 枚举样列一律 VARCHAR 码，不用 MySQL ENUM（ENUM 在 validate 里与 String 类型码不同）；
--    * JSON 载荷用 JSON 列（与 V8 mr_sync.payload_json 的 columnDefinition 写法一致，
--      实体侧用 @Column(columnDefinition = "JSON")）；
--    * 保留字 `release` 加反引号；
--    * 外键一律不建（与 V1 一致，跨域删除由服务层与幂等任务处理）。
--
--  与 TECHNICAL §1.3 的两处事实对齐（不是偏差，文档按 V1/V5 现状修订）：
--    * test_plan.revision 已由 V5 第 320 行加过 → 本文件仍声明它，判空 ALTER 会自动跳过，
--      这样「只跑 V9 一次」的判空逻辑与「跑过 V5」的库得到同一形状；
--    * test_execution.executor_id 在 V1 建表时已存在 → 本文件不 ALTER，s0 只断言列存在。
-- =====================================================================

SET SESSION group_concat_max_len = 16384;

-- ---------------------------------------------------------------- ① 11 张新表

-- Wiki 空间：可见范围是本轮唯一的三态（INHERIT 走 ScopeSql 标准谓词 / DOMAIN 本域可见 /
-- VENDOR 仅本厂商可见）。domain_id NOT NULL 是硬约束：没有域的空间无法计算可见性。
CREATE TABLE IF NOT EXISTS wiki_space (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT          NOT NULL,
    domain_id      BIGINT          NOT NULL,
    vendor_id      BIGINT          NULL,
    name           VARCHAR(100)    NOT NULL,
    visibility     VARCHAR(16)     NOT NULL DEFAULT 'INHERIT',
    editor_policy  VARCHAR(24)     NOT NULL DEFAULT 'DOMAIN_EDITORS',
    owner_id       BIGINT          NULL,
    status         VARCHAR(12)     NOT NULL DEFAULT 'ACTIVE',
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by     BIGINT          NULL,
    updated_by     BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_space_name (tenant_id, domain_id, name),
    KEY idx_space_scope (tenant_id, domain_id, vendor_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Wiki 页面（树 + 版本指针）。depth 1..4 由服务层校验（超过 4 层 400，PRD AC-8）。
-- deleted_root_id 记录「软删时的树根」：回收站恢复按根恢复整棵子树，不必逐层回溯 parent。
-- uk_page_sibling 的 parent_id 可为 NULL，MySQL 唯一键对 NULL 不去重 → 根级同名由服务层校验。
-- ft_wiki 用 ngram 分词器：中文没有空格，默认分词器按整句切，检索等于子串匹配（AC-13）。
CREATE TABLE IF NOT EXISTS wiki_page (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    space_id        BIGINT          NOT NULL,
    parent_id       BIGINT          NULL,
    title           VARCHAR(200)    NOT NULL,
    content_md      LONGTEXT        NULL,
    status          VARCHAR(12)     NOT NULL DEFAULT 'DRAFT',
    depth           INT             NOT NULL DEFAULT 1,
    sort_no         INT             NOT NULL DEFAULT 0,
    current_version INT             NOT NULL DEFAULT 1,
    editor_id       BIGINT          NULL,
    deleted_at      DATETIME(3)     NULL,
    deleted_root_id BIGINT          NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_page_sibling (space_id, parent_id, title),
    KEY idx_tree (tenant_id, space_id, parent_id, sort_no),
    KEY idx_trash (tenant_id, status, deleted_at),
    FULLTEXT KEY ft_wiki (title, content_md) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 页面版本：append-only。没有任何 UPDATE/DELETE 代码路径（只有回收站清理级联删），
-- content_hash 让「回滚后历史 hash 不变」可断言（AC-11）。
CREATE TABLE IF NOT EXISTS wiki_page_version (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT          NOT NULL,
    page_id      BIGINT          NOT NULL,
    version_no   INT             NOT NULL,
    title        VARCHAR(200)    NOT NULL,
    content_md   LONGTEXT        NULL,
    editor_id    BIGINT          NULL,
    content_hash VARCHAR(64)     NOT NULL,
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_page_ver (page_id, version_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS wiki_comment (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT          NOT NULL,
    page_id    BIGINT          NOT NULL,
    user_id    BIGINT          NOT NULL,
    content    VARCHAR(2000)   NOT NULL,
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_cmt_page (tenant_id, page_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 页面 ↔ 业务对象双向关联（需求/缺陷/版本）。唯一键保证同一对只连一次。
CREATE TABLE IF NOT EXISTS wiki_page_link (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT          NOT NULL,
    page_id    BIGINT          NOT NULL,
    biz_type   VARCHAR(16)     NOT NULL,
    biz_id     BIGINT          NOT NULL,
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_link (page_id, biz_type, biz_id),
    KEY idx_link_biz (tenant_id, biz_type, biz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 价值流阶段定义：7 阶段可配（mapping_json 是唯一的口径来源，指标只读它）。
CREATE TABLE IF NOT EXISTS stage_definition (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT          NOT NULL,
    code         VARCHAR(24)     NOT NULL,
    name         VARCHAR(50)     NOT NULL,
    order_no     INT             NOT NULL,
    mapping_json JSON            NULL,
    req_types    VARCHAR(64)     NULL,
    enabled      INT             NOT NULL DEFAULT 1,
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by   BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stage (tenant_id, code),
    KEY idx_stage_order (tenant_id, order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 分支策略登记：本地是「期望」，GitLab 是「实际」，核验表存两者差异（三态）。
CREATE TABLE IF NOT EXISTS branch_policy (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT          NOT NULL,
    domain_id         BIGINT          NOT NULL,
    gitlab_project    VARCHAR(200)    NOT NULL,
    branch_pattern    VARCHAR(200)    NOT NULL,
    pattern_type      VARCHAR(8)      NOT NULL DEFAULT 'REGEX',
    protected         INT             NOT NULL DEFAULT 1,
    merge_method      VARCHAR(12)     NOT NULL DEFAULT 'MERGE',
    approvals_required INT            NOT NULL DEFAULT 0,
    push_roles_json   JSON            NULL,
    merge_roles_json  JSON            NULL,
    enabled           INT             NOT NULL DEFAULT 1,
    version           INT             NOT NULL DEFAULT 0,
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by        BIGINT          NULL,
    updated_by        BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_policy (tenant_id, gitlab_project, branch_pattern),
    KEY idx_policy_domain (tenant_id, domain_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 核验记录：只 INSERT（历史不可改），页面状态取最新一条；没有记录 = UNVERIFIED。
CREATE TABLE IF NOT EXISTS branch_policy_check (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT          NOT NULL,
    policy_id    BIGINT          NOT NULL,
    result       VARCHAR(12)     NOT NULL,
    reason       VARCHAR(48)     NULL,
    diff_json    JSON            NULL,
    evidence_json JSON           NULL,
    checked_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    checked_by   BIGINT          NULL,
    PRIMARY KEY (id),
    KEY idx_check_policy (tenant_id, policy_id, checked_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- MR 行级评论（随需求可见性）。uk_note 让 webhook 重复投递同一 note 不产生重复行。
CREATE TABLE IF NOT EXISTS mr_comment (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    mr_sync_id      BIGINT          NOT NULL,
    gitlab_note_id  BIGINT          NOT NULL,
    author          VARCHAR(100)    NULL,
    body            VARCHAR(2000)   NOT NULL,
    file_path       VARCHAR(500)    NULL,
    line_no         INT             NULL,
    resolved        INT             NOT NULL DEFAULT 0,
    note_created_at DATETIME(3)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_note (tenant_id, mr_sync_id, gitlab_note_id),
    KEY idx_mrc (mr_sync_id, note_created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 用例评审：钉住 case_version，用例升版即失效（旧评审不得继续有效）。
-- 「同一用例同一版本只允许一条有效评审」MySQL 无法用条件唯一表达（部分唯一索引不存在），
-- 由服务层 SELECT ... FOR UPDATE + 事务内校验（TECH §3.7）。
CREATE TABLE IF NOT EXISTS test_case_review (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT          NOT NULL,
    case_id       BIGINT          NOT NULL,
    case_version  INT             NOT NULL,
    conclusion    VARCHAR(16)     NULL,
    comment       VARCHAR(2000)   NULL,
    reviewer_id   BIGINT          NULL,
    status        VARCHAR(12)     NOT NULL DEFAULT 'SUBMITTED',
    invalid_reason VARCHAR(32)    NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    decided_at    DATETIME(3)     NULL,
    decided_by    BIGINT          NULL,
    PRIMARY KEY (id),
    KEY idx_review_case (tenant_id, case_id, case_version, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 测试执行器台账：健康是派生值（ExecutorHealth.of），表里只存事实（enabled / last_report_at）。
CREATE TABLE IF NOT EXISTS test_executor (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT          NOT NULL,
    domain_id      BIGINT          NULL,
    code           VARCHAR(32)     NOT NULL,
    name           VARCHAR(100)    NOT NULL,
    base_url       VARCHAR(200)    NULL,
    api_client_id  BIGINT          NULL,
    enabled        INT             NOT NULL DEFAULT 1,
    last_report_at DATETIME(3)     NULL,
    report_count   INT             NOT NULL DEFAULT 0,
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_exec_code (tenant_id, code),
    KEY idx_exec_client (tenant_id, api_client_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- ② 判空加列

-- MR 评审信息：评审人 / 评审态 / 变更文件数 / 评论数，追加在 V8 的 12 列之后。
SET @ddl := (SELECT COALESCE(CONCAT('ALTER TABLE mr_sync ',
    GROUP_CONCAT(CONCAT('ADD COLUMN ', x.col_name, ' ', x.col_def) ORDER BY x.seq SEPARATOR ', ')),
    'DO 0')
    FROM (SELECT 1 AS seq, 'reviewers_json' AS col_name, 'JSON NULL' AS col_def
          UNION ALL SELECT 2, 'review_state', 'VARCHAR(24) NULL'
          UNION ALL SELECT 3, 'changed_files', 'INT NULL'
          UNION ALL SELECT 4, 'comments_count', 'INT NULL') x
    WHERE NOT EXISTS (SELECT 1
                      FROM information_schema.COLUMNS c
                      WHERE c.TABLE_SCHEMA = DATABASE()
                        AND c.TABLE_NAME = 'mr_sync'
                        AND c.COLUMN_NAME = x.col_name));
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 四主体实际期（甘特「浅色实际条」的唯一数据来源）。计划期是承诺，实际期是事实，两者分开存。
SET @ddl := (SELECT COALESCE(CONCAT('ALTER TABLE requirement ',
    GROUP_CONCAT(CONCAT('ADD COLUMN ', x.col_name, ' ', x.col_def) ORDER BY x.seq SEPARATOR ', ')),
    'DO 0')
    FROM (SELECT 1 AS seq, 'actual_start_date' AS col_name, 'DATE NULL' AS col_def
          UNION ALL SELECT 2, 'actual_end_date', 'DATE NULL') x
    WHERE NOT EXISTS (SELECT 1
                      FROM information_schema.COLUMNS c
                      WHERE c.TABLE_SCHEMA = DATABASE()
                        AND c.TABLE_NAME = 'requirement'
                        AND c.COLUMN_NAME = x.col_name));
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @ddl := (SELECT COALESCE(CONCAT('ALTER TABLE `release` ',
    GROUP_CONCAT(CONCAT('ADD COLUMN ', x.col_name, ' ', x.col_def) ORDER BY x.seq SEPARATOR ', ')),
    'DO 0')
    FROM (SELECT 1 AS seq, 'actual_start_date' AS col_name, 'DATE NULL' AS col_def
          UNION ALL SELECT 2, 'actual_end_date', 'DATE NULL') x
    WHERE NOT EXISTS (SELECT 1
                      FROM information_schema.COLUMNS c
                      WHERE c.TABLE_SCHEMA = DATABASE()
                        AND c.TABLE_NAME = 'release'
                        AND c.COLUMN_NAME = x.col_name));
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @ddl := (SELECT COALESCE(CONCAT('ALTER TABLE sprint ',
    GROUP_CONCAT(CONCAT('ADD COLUMN ', x.col_name, ' ', x.col_def) ORDER BY x.seq SEPARATOR ', ')),
    'DO 0')
    FROM (SELECT 1 AS seq, 'actual_start_date' AS col_name, 'DATE NULL' AS col_def
          UNION ALL SELECT 2, 'actual_end_date', 'DATE NULL') x
    WHERE NOT EXISTS (SELECT 1
                      FROM information_schema.COLUMNS c
                      WHERE c.TABLE_SCHEMA = DATABASE()
                        AND c.TABLE_NAME = 'sprint'
                        AND c.COLUMN_NAME = x.col_name));
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @ddl := (SELECT COALESCE(CONCAT('ALTER TABLE task ',
    GROUP_CONCAT(CONCAT('ADD COLUMN ', x.col_name, ' ', x.col_def) ORDER BY x.seq SEPARATOR ', ')),
    'DO 0')
    FROM (SELECT 1 AS seq, 'actual_start_date' AS col_name, 'DATE NULL' AS col_def
          UNION ALL SELECT 2, 'actual_end_date', 'DATE NULL') x
    WHERE NOT EXISTS (SELECT 1
                      FROM information_schema.COLUMNS c
                      WHERE c.TABLE_SCHEMA = DATABASE()
                        AND c.TABLE_NAME = 'task'
                        AND c.COLUMN_NAME = x.col_name));
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 测试数据说明 + 并发版本。revision 已由 V5 加过（第 320 行），这里保留声明以便
-- 「没跑过 V5 的库」也得到同一形状，判空逻辑会让它变成 DO 0。
SET @ddl := (SELECT COALESCE(CONCAT('ALTER TABLE test_plan ',
    GROUP_CONCAT(CONCAT('ADD COLUMN ', x.col_name, ' ', x.col_def) ORDER BY x.seq SEPARATOR ', ')),
    'DO 0')
    FROM (SELECT 1 AS seq, 'data_note' AS col_name, 'TEXT NULL' AS col_def
          UNION ALL SELECT 2, 'revision', 'INT NOT NULL DEFAULT 0') x
    WHERE NOT EXISTS (SELECT 1
                      FROM information_schema.COLUMNS c
                      WHERE c.TABLE_SCHEMA = DATABASE()
                        AND c.TABLE_NAME = 'test_plan'
                        AND c.COLUMN_NAME = x.col_name));
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- ---------------------------------------------------------------- ③ 索引

-- ngram 全文索引的兜底：表在早先跑过一半的库上可能没有（CREATE TABLE IF NOT EXISTS 不会补索引）。
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'CREATE FULLTEXT INDEX ft_wiki ON wiki_page (title, content_md) WITH PARSER ngram', 'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wiki_page' AND INDEX_NAME = 'ft_wiki');
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 价值流要在 audit_log 上按 (biz_type, biz_id) 取一条需求的全生命周期区间：
-- 100 万行下没有这条索引就是全表扫（AC-20 P95 预算）。
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_audit_req ON audit_log (tenant_id, biz_type, biz_id, created_at)', 'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log' AND INDEX_NAME = 'idx_audit_req');
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- ---------------------------------------------------------------- ④ 历史回填（只填 NULL）
-- 口径与 platform-common ActualPeriodRecorder 完全一致（同一张映射表两处实现，s0 用
-- 「回填后重算 = 0 差异」校验）：
--   REQUIREMENT 起 IN_DEV            止 DELIVERED / DONE / ACCEPTED_CLOSED / CLOSED
--   SPRINT      起 ACTIVE            止 CLOSED
--   TASK        起 IN_PROGRESS       止 DONE / CANCELLED
--   RELEASE     起 IN_PROGRESS|PUBLISHED  止 RELEASED / CLOSED
-- 取 MIN(created_at) = 首次进入该状态的时刻；DATE() 截到天（甘特按天画条）。

UPDATE requirement r
    JOIN (SELECT biz_id, tenant_id, MIN(created_at) AS t
          FROM audit_log
          WHERE action = 'STATE_CHANGE'
            AND biz_type = 'REQUIREMENT'
            AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.to')) = 'IN_DEV'
          GROUP BY tenant_id, biz_id) a
    ON a.biz_id = r.id AND a.tenant_id = r.tenant_id
SET r.actual_start_date = DATE(a.t)
WHERE r.actual_start_date IS NULL;

UPDATE requirement r
    JOIN (SELECT biz_id, tenant_id, MIN(created_at) AS t
          FROM audit_log
          WHERE action = 'STATE_CHANGE'
            AND biz_type = 'REQUIREMENT'
            AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.to'))
                IN ('DELIVERED', 'DONE', 'ACCEPTED_CLOSED', 'CLOSED')
          GROUP BY tenant_id, biz_id) a
    ON a.biz_id = r.id AND a.tenant_id = r.tenant_id
SET r.actual_end_date = DATE(a.t)
WHERE r.actual_end_date IS NULL;

UPDATE sprint s
    JOIN (SELECT biz_id, tenant_id, MIN(created_at) AS t
          FROM audit_log
          WHERE action = 'STATE_CHANGE'
            AND biz_type = 'SPRINT'
            AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.to')) = 'ACTIVE'
          GROUP BY tenant_id, biz_id) a
    ON a.biz_id = s.id AND a.tenant_id = s.tenant_id
SET s.actual_start_date = DATE(a.t)
WHERE s.actual_start_date IS NULL;

UPDATE sprint s
    JOIN (SELECT biz_id, tenant_id, MIN(created_at) AS t
          FROM audit_log
          WHERE action = 'STATE_CHANGE'
            AND biz_type = 'SPRINT'
            AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.to')) = 'CLOSED'
          GROUP BY tenant_id, biz_id) a
    ON a.biz_id = s.id AND a.tenant_id = s.tenant_id
SET s.actual_end_date = DATE(a.t)
WHERE s.actual_end_date IS NULL;

UPDATE task k
    JOIN (SELECT biz_id, tenant_id, MIN(created_at) AS t
          FROM audit_log
          WHERE action = 'STATE_CHANGE'
            AND biz_type = 'TASK'
            AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.to')) = 'IN_PROGRESS'
          GROUP BY tenant_id, biz_id) a
    ON a.biz_id = k.id AND a.tenant_id = k.tenant_id
SET k.actual_start_date = DATE(a.t)
WHERE k.actual_start_date IS NULL;

UPDATE task k
    JOIN (SELECT biz_id, tenant_id, MIN(created_at) AS t
          FROM audit_log
          WHERE action = 'STATE_CHANGE'
            AND biz_type = 'TASK'
            AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.to')) IN ('DONE', 'CANCELLED')
          GROUP BY tenant_id, biz_id) a
    ON a.biz_id = k.id AND a.tenant_id = k.tenant_id
SET k.actual_end_date = DATE(a.t)
WHERE k.actual_end_date IS NULL;

UPDATE `release` p
    JOIN (SELECT biz_id, tenant_id, MIN(created_at) AS t
          FROM audit_log
          WHERE action = 'STATE_CHANGE'
            AND biz_type = 'RELEASE'
            AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.to')) IN ('IN_PROGRESS', 'PUBLISHED')
          GROUP BY tenant_id, biz_id) a
    ON a.biz_id = p.id AND a.tenant_id = p.tenant_id
SET p.actual_start_date = DATE(a.t)
WHERE p.actual_start_date IS NULL;

UPDATE `release` p
    JOIN (SELECT biz_id, tenant_id, MIN(created_at) AS t
          FROM audit_log
          WHERE action = 'STATE_CHANGE'
            AND biz_type = 'RELEASE'
            AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.to')) IN ('RELEASED', 'CLOSED')
          GROUP BY tenant_id, biz_id) a
    ON a.biz_id = p.id AND a.tenant_id = p.tenant_id
SET p.actual_end_date = DATE(a.t)
WHERE p.actual_end_date IS NULL;

-- ---------------------------------------------------------------- ⑤ 种子（按租户，幂等）
-- 权限码不在这里：PermissionMatrix 是唯一来源，管理页只是它的投影（TECH §5.1）。

-- 价值流 7 阶段。mapping_json 是唯一口径：FlowMetricFormulas 只按它把 audit_log 的
-- 状态/事件映射到阶段，页面不参与任何映射推断（AC-6）。
INSERT INTO stage_definition (tenant_id, code, name, order_no, mapping_json, enabled, created_at, updated_at)
SELECT t.id, m.code, m.name, m.order_no, CAST(m.mapping AS JSON), 1, NOW(3), NOW(3)
FROM sys_tenant t
         JOIN (SELECT 'REPORTED' AS code, '提报' AS name, 1 AS order_no,
                      '{"enterEvents":["REQUIREMENT_CREATED"]}' AS mapping
               UNION ALL
               SELECT 'REVIEWING', '评审', 2,
                      '{"enterStatuses":["APPROVING","PENDING_REVIEW"],"leaveStatuses":["POOLED"]}'
               UNION ALL
               SELECT 'SCHEDULING', '排期', 3,
                      '{"enterStatuses":["POOLED","SCHEDULED"],"enterEvents":["RELEASE_ATTACHED"]}'
               UNION ALL
               SELECT 'DEVELOPING', '开发', 4, '{"enterStatuses":["IN_DEV"]}'
               UNION ALL
               SELECT 'TESTING', '测试', 5, '{"enterStatuses":["PENDING_TEST"]}'
               UNION ALL
               SELECT 'RELEASING', '发布', 6, '{"enterStatuses":["TEST_PASSED","MERGED","DELIVERED"]}'
               UNION ALL
               SELECT 'DELIVERED', '交付', 7,
                      '{"enterStatuses":["DONE","ACCEPTED_CLOSED","CLOSED"]}') m
WHERE NOT EXISTS (SELECT 1
                  FROM stage_definition d
                  WHERE d.tenant_id = t.id AND d.code = m.code);

-- 价值流 5 指标进指标字典：字典页预览必须与看板差 0（MetricQuery 委托 FlowMetricService）。
-- formula 存 PRD §7 的文本口径，datasource 指向 audit_log 派生（不是第二套计算）。
INSERT INTO metric_definition (tenant_id, code, name, topic, formula, datasource, caliber_note,
                               owner_id, version, effective_at, created_at, updated_at)
SELECT t.id,
       m.code,
       m.name,
       'flow',
       m.formula,
       'req.audit',
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
         JOIN (SELECT 'FLOW_WIP' AS code, '在制品数' AS name,
                      'count(requirement where stage not in (DELIVERED))' AS formula,
                      '窗口内处于各阶段（未交付）的需求数；按阶段分组，0 显示 0 而不是 —' AS note
               UNION ALL
               SELECT 'FLOW_DWELL_AVG', '平均停留时长',
                      'avg(exit_at - enter_at) over stage_interval',
                      '阶段区间由 audit_log 状态变更派生；样本 0 返回 null（显示为 —），不补 0'
               UNION ALL
               SELECT 'FLOW_DWELL_P90', 'P90 停留时长',
                      'p90(exit_at - enter_at) nearest-rank',
                      '最近秩法 P90，与 FlowMetricFormulas.p90Dwell 同一实现；样本 0 返回 null'
               UNION ALL
               SELECT 'FLOW_EFFICIENCY', '流动效率',
                      '(devSeconds + testSeconds) / (reportedAt -> deliveredAt)',
                      '开发+测试时长 / 提报→交付时长；分母 0 返回 null，不补 0'
               UNION ALL
               SELECT 'FLOW_BOTTLENECK', '瓶颈阶段',
                      'argmax(avgDwell by stage) where samples >= 3',
                      '样本少于 3 的阶段不参与瓶颈判定（避免一条需求的偶发长停留夺榜）') m
WHERE NOT EXISTS (SELECT 1
                  FROM metric_definition d
                  WHERE d.tenant_id = t.id AND d.code = m.code AND d.version = 1);

-- Wiki 种子：1 个 DOMAIN 空间 + 3 页（2 级树）+ 4 行版本（首页 v1/v2 内容不同，s2 造 5 版继续用）。
INSERT INTO wiki_space (tenant_id, domain_id, vendor_id, name, visibility, editor_policy,
                        owner_id, status, created_at, updated_at, created_by, updated_by)
SELECT t.id,
       (SELECT d.id FROM biz_domain d WHERE d.tenant_id = t.id ORDER BY d.id LIMIT 1),
       NULL,
       '研发协作 Wiki',
       'DOMAIN',
       'DOMAIN_EDITORS',
       COALESCE(
           (SELECT u.id FROM sys_user u
                     JOIN sys_user_role ur ON ur.user_id = u.id AND ur.tenant_id = u.tenant_id
                     JOIN sys_role r ON r.id = ur.role_id AND r.code = 'PMO'
            WHERE u.tenant_id = t.id ORDER BY u.id LIMIT 1),
           (SELECT u.id FROM sys_user u WHERE u.tenant_id = t.id ORDER BY u.id LIMIT 1)),
       'ACTIVE',
       NOW(3),
       NOW(3),
       NULL,
       NULL
FROM sys_tenant t
WHERE EXISTS (SELECT 1 FROM biz_domain d WHERE d.tenant_id = t.id)
  AND NOT EXISTS (SELECT 1
                  FROM wiki_space s
                  WHERE s.tenant_id = t.id AND s.name = '研发协作 Wiki');

-- 根页 1：研发协作手册（首页，2 个版本）
INSERT INTO wiki_page (tenant_id, space_id, parent_id, title, content_md, status, depth, sort_no,
                       current_version, editor_id, created_at, updated_at)
SELECT s.tenant_id, s.id, NULL, '研发协作手册',
       '# 研发协作手册\n\n本页是团队协作的唯一入口。\n\n- 需求交付流程见「需求交付流程」子页\n- 排期与甘特口径见「甘特图使用说明」\n',
       'PUBLISHED', 1, 10, 2, NULL, NOW(3), NOW(3)
FROM wiki_space s
WHERE s.name = '研发协作 Wiki'
  AND NOT EXISTS (SELECT 1 FROM wiki_page p
                  WHERE p.space_id = s.id AND p.parent_id IS NULL AND p.title = '研发协作手册');

-- 子页 2：需求交付流程（第 2 层，验证树深度与移动）
INSERT INTO wiki_page (tenant_id, space_id, parent_id, title, content_md, status, depth, sort_no,
                       current_version, editor_id, created_at, updated_at)
SELECT p.tenant_id, p.space_id, p.id, '需求交付流程',
       '# 需求交付流程\n\n提报 → 评审 → 排期 → 开发 → 测试 → 发布 → 交付。\n\n每个阶段的实际进入时间由 audit_log 派生，甘特的实际条与价值流共用同一份数据。\n',
       'PUBLISHED', 2, 10, 1, NULL, NOW(3), NOW(3)
FROM wiki_page p
WHERE p.title = '研发协作手册'
  AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM wiki_page c
                  WHERE c.space_id = p.space_id AND c.parent_id = p.id AND c.title = '需求交付流程');

-- 根页 3：甘特图使用说明
INSERT INTO wiki_page (tenant_id, space_id, parent_id, title, content_md, status, depth, sort_no,
                       current_version, editor_id, created_at, updated_at)
SELECT s.tenant_id, s.id, NULL, '甘特图使用说明',
       '# 甘特图使用说明\n\n实心条是计划（承诺），浅色带是实际（事实）。\n\n拖拽改期会先落审计再改期；超出父版本窗口需要二次确认。\n',
       'PUBLISHED', 1, 20, 1, NULL, NOW(3), NOW(3)
FROM wiki_space s
WHERE s.name = '研发协作 Wiki'
  AND NOT EXISTS (SELECT 1 FROM wiki_page p
                  WHERE p.space_id = s.id AND p.parent_id IS NULL AND p.title = '甘特图使用说明');

-- 版本行：内容 hash 由 SHA2 计算，保证「同一内容同一 hash」可断言（回滚后历史不变）。
INSERT INTO wiki_page_version (tenant_id, page_id, version_no, title, content_md, editor_id,
                               content_hash, created_at)
SELECT p.tenant_id, p.id, 1, p.title,
       '# 研发协作手册\n\n本页是团队协作入口。\n',
       NULL, SHA2('# 研发协作手册\n\n本页是团队协作入口。\n', 256), NOW(3)
FROM wiki_page p
WHERE p.title = '研发协作手册' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM wiki_page_version v WHERE v.page_id = p.id AND v.version_no = 1);

INSERT INTO wiki_page_version (tenant_id, page_id, version_no, title, content_md, editor_id,
                               content_hash, created_at)
SELECT p.tenant_id, p.id, 2, p.title, p.content_md,
       NULL, SHA2(p.content_md, 256), NOW(3)
FROM wiki_page p
WHERE p.title = '研发协作手册' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM wiki_page_version v WHERE v.page_id = p.id AND v.version_no = 2);

INSERT INTO wiki_page_version (tenant_id, page_id, version_no, title, content_md, editor_id,
                               content_hash, created_at)
SELECT p.tenant_id, p.id, 1, p.title, p.content_md,
       NULL, SHA2(p.content_md, 256), NOW(3)
FROM wiki_page p
WHERE p.title IN ('需求交付流程', '甘特图使用说明')
  AND NOT EXISTS (SELECT 1 FROM wiki_page_version v WHERE v.page_id = p.id AND v.version_no = 1);

-- 分支策略 1 行：不种核验记录 → 页面派生状态是 UNVERIFIED（「无核验记录即未核验」，AC-14）。
INSERT INTO branch_policy (tenant_id, domain_id, gitlab_project, branch_pattern, pattern_type,
                           protected, merge_method, approvals_required, push_roles_json,
                           merge_roles_json, enabled, version, created_at, updated_at,
                           created_by, updated_by)
SELECT t.id,
       (SELECT d.id FROM biz_domain d WHERE d.tenant_id = t.id ORDER BY d.id LIMIT 1),
       'demo/supply-chain-platform', '^release/[0-9]+\\.[0-9]+$', 'REGEX',
       1, 'MERGE', 2, CAST('["Maintainer"]' AS JSON), CAST('["Maintainer"]' AS JSON),
       1, 0, NOW(3), NOW(3), NULL, NULL
FROM sys_tenant t
WHERE EXISTS (SELECT 1 FROM biz_domain d WHERE d.tenant_id = t.id)
  AND NOT EXISTS (SELECT 1
                  FROM branch_policy p
                  WHERE p.tenant_id = t.id
                    AND p.gitlab_project = 'demo/supply-chain-platform');

-- 执行器 2 行：一行启用但从未上报（→ NEVER），一行停用（→ DISABLED）。健康派生靠这两条事实。
INSERT INTO test_executor (tenant_id, domain_id, code, name, base_url, api_client_id, enabled,
                           last_report_at, report_count, created_at, updated_at)
SELECT t.id, NULL, m.code, m.name, m.base_url, NULL, m.enabled, NULL, 0, NOW(3), NOW(3)
FROM sys_tenant t
         JOIN (SELECT 'STUB-LOCAL' AS code, '本地桩执行器' AS name,
                      'http://127.0.0.1:19091' AS base_url, 1 AS enabled
               UNION ALL
               SELECT 'LEGACY-OFF', '已停用执行器', 'http://127.0.0.1:19092', 0) m
WHERE NOT EXISTS (SELECT 1
                  FROM test_executor e
                  WHERE e.tenant_id = t.id AND e.code = m.code);

-- ---------------------------------------------------------------- ⑥ 校验
-- 期望全部 = 0：有状态审计但实际期仍为空。不为 0 说明回填口径与 ActualPeriodRecorder 不一致，
-- 必须在本迁移内修好（不能留到甘特页面去补）。s0 会断言这四列都是 0。
SELECT
    (SELECT COUNT(*)
     FROM requirement r
     WHERE r.actual_start_date IS NULL
       AND EXISTS (SELECT 1 FROM audit_log a
                   WHERE a.tenant_id = r.tenant_id AND a.biz_type = 'REQUIREMENT'
                     AND a.biz_id = r.id AND a.action = 'STATE_CHANGE'
                     AND JSON_UNQUOTE(JSON_EXTRACT(a.detail_json, '$.to')) = 'IN_DEV'))
        AS requirement_unfilled_start,
    (SELECT COUNT(*)
     FROM requirement r
     WHERE r.actual_end_date IS NULL
       AND EXISTS (SELECT 1 FROM audit_log a
                   WHERE a.tenant_id = r.tenant_id AND a.biz_type = 'REQUIREMENT'
                     AND a.biz_id = r.id AND a.action = 'STATE_CHANGE'
                     AND JSON_UNQUOTE(JSON_EXTRACT(a.detail_json, '$.to'))
                         IN ('DELIVERED', 'DONE', 'ACCEPTED_CLOSED', 'CLOSED')))
        AS requirement_unfilled_end,
    (SELECT COUNT(*)
     FROM metric_definition m
     WHERE m.code LIKE 'FLOW\_%' AND m.version = 1 AND m.datasource <> 'req.audit')
        AS flow_metrics_with_foreign_datasource,
    (SELECT COUNT(*)
     FROM wiki_page p
     WHERE p.status <> 'DELETED'
       AND p.current_version >
           (SELECT COALESCE(MAX(v.version_no), 0) FROM wiki_page_version v WHERE v.page_id = p.id))
        AS pages_without_their_current_version;
