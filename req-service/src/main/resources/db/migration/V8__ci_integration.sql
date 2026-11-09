-- =====================================================================
-- V8 · M7 集成对接（Jenkins / GitLab / Sonar / Nexus）
--
-- 幂等约定（与 V5 一致，全部可重复执行）：
--   * 建表一律 CREATE TABLE IF NOT EXISTS；
--   * 加列/加索引先查 information_schema，再用 PREPARE/EXECUTE 执行；
--   * MODIFY COLUMN 放宽 NOT NULL 本身可重复执行。
-- req-service 是 Flyway 的唯一 owner（所有模块共享同一库），Schema 只在此处变更。
-- =====================================================================

-- ---------------------------------------------------------------- 1. 集成配置
CREATE TABLE IF NOT EXISTS integration_config (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT          NOT NULL,
    `system`          VARCHAR(16)     NOT NULL COMMENT 'JENKINS/GITLAB/SONAR/NEXUS',
    name              VARCHAR(64)     NOT NULL,
    base_url          VARCHAR(500)    NOT NULL,
    auth_type         VARCHAR(16)     NOT NULL DEFAULT 'TOKEN' COMMENT 'NONE/BASIC/TOKEN/PRIVATE_TOKEN',
    username          VARCHAR(128)    NULL,
    credential_enc    VARBINARY(1024) NULL COMMENT 'AES-GCM 密文，只写不读',
    credential_last4  VARCHAR(8)      NULL,
    secret_enc        VARBINARY(1024) NULL COMMENT 'webhook secret 密文',
    secret_last4      VARCHAR(8)      NULL,
    extra_json        TEXT            NULL,
    enabled           INT             NOT NULL DEFAULT 0,
    active_key        VARCHAR(8)      NULL COMMENT '启用时=ENABLED，停用时 NULL，唯一键保证同系统仅一条启用',
    timeout_ms        INT             NOT NULL DEFAULT 5000,
    last_test_at      DATETIME(3)     NULL,
    last_test_ok      INT             NULL,
    last_test_message VARCHAR(300)    NULL,
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by        BIGINT          NULL,
    updated_by        BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cfg_active (tenant_id, `system`, active_key),
    KEY idx_cfg_tenant (tenant_id, `system`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 2. 同步日志
CREATE TABLE IF NOT EXISTS integration_sync_log (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT          NOT NULL,
    correlation_id   VARCHAR(40)     NOT NULL,
    config_id        BIGINT          NULL,
    `system`         VARCHAR(16)     NOT NULL,
    direction        VARCHAR(4)      NOT NULL COMMENT 'OUT/IN',
    operation        VARCHAR(24)     NOT NULL,
    method           VARCHAR(8)      NULL,
    target_url       VARCHAR(500)    NULL,
    request_summary  VARCHAR(1000)   NULL,
    http_status      INT             NULL,
    response_status  INT             NULL,
    response_summary VARCHAR(1000)   NULL,
    result           VARCHAR(16)     NOT NULL COMMENT 'SUCCESS/DEGRADED/FAILED/RETRY_EXHAUSTED/IGNORED',
    error_code       VARCHAR(40)     NULL,
    retry_count      INT             NOT NULL DEFAULT 0,
    duration_ms      INT             NOT NULL DEFAULT 0,
    biz_type         VARCHAR(24)     NULL,
    biz_id           BIGINT          NULL,
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_log_corr (tenant_id, correlation_id),
    KEY idx_log_time (tenant_id, created_at),
    KEY idx_log_cfg (config_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 3. 构建记录
CREATE TABLE IF NOT EXISTS build_record (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    deploy_order_id BIGINT          NOT NULL,
    requirement_id  BIGINT          NULL,
    config_id       BIGINT          NOT NULL,
    job_name        VARCHAR(200)    NOT NULL,
    build_number    BIGINT          NULL,
    queue_id        BIGINT          NULL,
    status          VARCHAR(12)     NOT NULL COMMENT 'QUEUED/RUNNING/SUCCESS/FAILURE/ABORTED',
    active_key      VARCHAR(24)     NULL COMMENT '活动构建=deploy_order_id，终态置 NULL；唯一键挡住双活动构建',
    stages_json     TEXT            NULL,
    console_url     VARCHAR(500)    NULL,
    started_at      DATETIME(3)     NULL,
    finished_at     DATETIME(3)     NULL,
    duration_ms     BIGINT          NULL,
    timeout_at      DATETIME(3)     NOT NULL,
    poll_fail_count INT             NOT NULL DEFAULT 0,
    correlation_id  VARCHAR(40)     NULL,
    trigger_by      BIGINT          NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_build_active (tenant_id, active_key),
    KEY idx_build_order (tenant_id, deploy_order_id, id DESC),
    KEY idx_build_status (status, timeout_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 4. MR 同步
CREATE TABLE IF NOT EXISTS mr_sync (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    requirement_id  BIGINT          NOT NULL,
    config_id       BIGINT          NOT NULL,
    gitlab_project  VARCHAR(200)    NOT NULL,
    mr_iid          BIGINT          NOT NULL,
    mr_url          VARCHAR(500)    NOT NULL,
    title           VARCHAR(300)    NULL,
    state           VARCHAR(16)     NOT NULL COMMENT 'OPENED/MERGED/CLOSED/LOCKED',
    source_branch   VARCHAR(200)    NULL,
    target_branch   VARCHAR(200)    NULL,
    approvals_count INT             NOT NULL DEFAULT 0,
    additions       INT             NOT NULL DEFAULT 0,
    deletions       INT             NOT NULL DEFAULT 0,
    last_event_at   DATETIME(3)     NOT NULL,
    merged_at       DATETIME(3)     NULL,
    payload_json    MEDIUMTEXT      NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_mr (tenant_id, gitlab_project, mr_iid),
    KEY idx_mr_req (tenant_id, requirement_id, last_event_at DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 5. 制品记录
CREATE TABLE IF NOT EXISTS artifact_record (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT          NOT NULL,
    config_id        BIGINT          NULL,
    repo             VARCHAR(64)     NOT NULL,
    group_id         VARCHAR(128)    NOT NULL,
    artifact_name    VARCHAR(128)    NOT NULL,
    version          VARCHAR(64)     NOT NULL,
    packaging        VARCHAR(16)     NOT NULL DEFAULT 'jar',
    classifier       VARCHAR(32)     NULL,
    sha256           CHAR(64)        NOT NULL,
    download_url     VARCHAR(500)    NULL,
    size_bytes       BIGINT          NULL,
    source           VARCHAR(8)      NOT NULL COMMENT 'NEXUS/MANUAL',
    status           VARCHAR(12)     NOT NULL DEFAULT 'REGISTERED',
    promoted_from_id BIGINT          NULL,
    promoted_at      DATETIME(3)     NULL,
    promoted_by      BIGINT          NULL,
    requirement_id   BIGINT          NULL,
    deploy_order_id  BIGINT          NULL,
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by       BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_artifact (tenant_id, repo, group_id, artifact_name, version, packaging, sha256),
    UNIQUE KEY uk_promoted (promoted_from_id),
    KEY idx_artifact_order (tenant_id, deploy_order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 6. 准入项开关
CREATE TABLE IF NOT EXISTS admission_item_config (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT          NOT NULL,
    item_code  VARCHAR(40)     NOT NULL,
    enabled    INT             NOT NULL DEFAULT 1,
    updated_by BIGINT          NULL,
    updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_item (tenant_id, item_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 7. quality_gate_snapshot 扩为双证据源
-- source='SONAR' 的行 gate_id/deploy_order_id 为 NULL，M6 所有「按 gate_id / deploy_order_id」
-- 的查询因此结构上排除 Sonar 快照，第 6 项语义零改动。
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE quality_gate_snapshot ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT ''QUALITY_GATE'' AFTER tenant_id',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quality_gate_snapshot' AND COLUMN_NAME = 'source');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE quality_gate_snapshot ADD COLUMN requirement_id BIGINT NULL AFTER report_version_id',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quality_gate_snapshot' AND COLUMN_NAME = 'requirement_id');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE quality_gate_snapshot ADD COLUMN project_key VARCHAR(200) NULL AFTER requirement_id',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quality_gate_snapshot' AND COLUMN_NAME = 'project_key');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE quality_gate_snapshot ADD COLUMN metrics_json MEDIUMTEXT NULL AFTER project_key',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quality_gate_snapshot' AND COLUMN_NAME = 'metrics_json');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE quality_gate_snapshot ADD COLUMN metric_status VARCHAR(16) NULL AFTER metrics_json',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quality_gate_snapshot' AND COLUMN_NAME = 'metric_status');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Sonar 快照没有质量门与发布单，两列必须可空（MODIFY 天然幂等）。
ALTER TABLE quality_gate_snapshot MODIFY COLUMN gate_id BIGINT NULL;
ALTER TABLE quality_gate_snapshot MODIFY COLUMN deploy_order_id BIGINT NULL;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE quality_gate_snapshot ADD KEY idx_snap_source (tenant_id, source, requirement_id, id)',
    'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quality_gate_snapshot' AND INDEX_NAME = 'idx_snap_source');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 历史行补默认证据源（ADD COLUMN ... DEFAULT 已回填，这里只兜住 MySQL 非严格模式下的空值）。
UPDATE quality_gate_snapshot SET source = 'QUALITY_GATE' WHERE source IS NULL OR source = '';

-- ---------------------------------------------------------------- 8. 既有实体表加列
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE requirement ADD COLUMN gitlab_project VARCHAR(200) NULL', 'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'requirement' AND COLUMN_NAME = 'gitlab_project');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE requirement ADD COLUMN mr_iid BIGINT NULL', 'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'requirement' AND COLUMN_NAME = 'mr_iid');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE requirement ADD COLUMN sonar_project_key VARCHAR(200) NULL', 'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'requirement' AND COLUMN_NAME = 'sonar_project_key');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `release` ADD COLUMN mr_auto_advance INT NOT NULL DEFAULT 0', 'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'release' AND COLUMN_NAME = 'mr_auto_advance');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `release` ADD COLUMN sonar_project_key VARCHAR(200) NULL', 'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'release' AND COLUMN_NAME = 'sonar_project_key');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE deploy_order ADD COLUMN build_id BIGINT NULL', 'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deploy_order' AND COLUMN_NAME = 'build_id');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE deploy_order ADD COLUMN artifact_id BIGINT NULL', 'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deploy_order' AND COLUMN_NAME = 'artifact_id');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
