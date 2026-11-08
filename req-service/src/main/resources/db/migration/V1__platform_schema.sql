-- =====================================================================
--  DevOps platform · schema V1 (39 tables)
--
--  Single migration authority: only req-service runs Flyway. deliver-service
--  starts with ddl-auto=validate, so a column drift between this file and the
--  entities fails the boot instead of silently writing to a wrong shape.
--
--  Conventions (TECHNICAL §3.1):
--    * id BIGINT UNSIGNED AUTO_INCREMENT (or composite PK for relation tables)
--    * every business table carries tenant_id and every unique key starts with it
--      (api_client.app_key is the single global exception: it is looked up before
--      the tenant is known)
--    * audit columns DATETIME(3) with defaults; version INT for optimistic locking
--    * enum-like columns are VARCHAR codes, never MySQL ENUM (no table locks)
--    * reserved words are back-quoted here (`release`), and Hibernate is configured
--      with hibernate.auto_quote_keyword=true so its generated SQL agrees.
-- =====================================================================

-- ---------------------------------------------------------------- 组织与账号

CREATE TABLE sys_tenant (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code           VARCHAR(32)     NOT NULL,
    name           VARCHAR(200)    NOT NULL,
    isolation_mode VARCHAR(16)     NOT NULL DEFAULT 'ROW',
    datasource_key VARCHAR(64)     NULL,
    status         VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by     BIGINT          NULL,
    updated_by     BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE sys_org (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT          NOT NULL,
    parent_id  BIGINT          NULL,
    type       VARCHAR(16)     NOT NULL DEFAULT 'DEPT',
    code       VARCHAR(32)     NULL,
    name       VARCHAR(200)    NOT NULL,
    path       VARCHAR(512)    NOT NULL,
    sort_no    INT             NOT NULL DEFAULT 0,
    status     VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT          NULL,
    updated_by BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_org_path (tenant_id, path),
    KEY idx_org_parent (tenant_id, parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE sys_vendor (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT          NOT NULL,
    code       VARCHAR(32)     NOT NULL,
    name       VARCHAR(200)    NOT NULL,
    contact    VARCHAR(100)    NULL,
    status     VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT          NULL,
    updated_by BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vendor_code (tenant_id, code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE biz_domain (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT          NOT NULL,
    code          VARCHAR(32)     NOT NULL,
    name          VARCHAR(200)    NOT NULL,
    owner_user_id BIGINT          NULL,
    enabled       TINYINT         NOT NULL DEFAULT 1,
    sort_no       INT             NOT NULL DEFAULT 0,
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by    BIGINT          NULL,
    updated_by    BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_domain_code (tenant_id, code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE sys_user (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT          NOT NULL,
    username      VARCHAR(64)     NOT NULL,
    name          VARCHAR(100)    NOT NULL,
    password_hash VARCHAR(200)    NOT NULL,
    org_id        BIGINT          NULL,
    vendor_id     BIGINT          NULL,
    status        VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    last_login_at DATETIME(3)     NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by    BIGINT          NULL,
    updated_by    BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user (tenant_id, username),
    KEY idx_user_vendor (tenant_id, vendor_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE sys_role (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT          NOT NULL,
    code       VARCHAR(32)     NOT NULL,
    name       VARCHAR(100)    NOT NULL,
    builtin    TINYINT         NOT NULL DEFAULT 1,
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT          NULL,
    updated_by BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role (tenant_id, code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE sys_user_role (
    tenant_id BIGINT NOT NULL,
    user_id   BIGINT NOT NULL,
    role_id   BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY idx_ur_role (tenant_id, role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE sys_user_domain (
    tenant_id BIGINT NOT NULL,
    user_id   BIGINT NOT NULL,
    domain_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, domain_id),
    KEY idx_ud_domain (tenant_id, domain_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE sys_dict (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT          NOT NULL,
    type       VARCHAR(32)     NOT NULL,
    code       VARCHAR(64)     NOT NULL,
    label      VARCHAR(200)    NOT NULL,
    sort_no    INT             NOT NULL DEFAULT 0,
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT          NULL,
    updated_by BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict (tenant_id, type, code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE code_sequence (
    tenant_id BIGINT      NOT NULL,
    code_type VARCHAR(16) NOT NULL,
    next_val  BIGINT      NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, code_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 需求、版本、发布

CREATE TABLE requirement (
    id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id                BIGINT          NOT NULL,
    code                     VARCHAR(24)     NOT NULL,
    type                     VARCHAR(8)      NOT NULL,
    parent_id                BIGINT          NULL,
    domain_id                BIGINT          NOT NULL,
    origin_org_id            BIGINT          NOT NULL,
    reporter_id              BIGINT          NOT NULL,
    owner_user_id            BIGINT          NULL,
    owner_vendor_id          BIGINT          NULL,
    assignee_vendor_id       BIGINT          NULL,
    title                    VARCHAR(200)    NOT NULL,
    description              TEXT            NULL,
    priority                 VARCHAR(4)      NOT NULL,
    status                   VARCHAR(24)     NOT NULL,
    estimate_days            DECIMAL(6, 1)   NULL,
    actual_days              DECIMAL(6, 1)   NULL,
    mr_url                   VARCHAR(500)    NULL,
    release_id               BIGINT          NULL,
    expected_delivery_date   DATE            NULL,
    plan_start_date          DATE            NULL,
    plan_end_date            DATE            NULL,
    closed_at                DATETIME(3)     NULL,
    test_passed_execution_id BIGINT          NULL,
    created_at               DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by               BIGINT          NULL,
    updated_by               BIGINT          NULL,
    version                  INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_req_code (tenant_id, code),
    KEY idx_req_list (tenant_id, type, status, domain_id, updated_at),
    KEY idx_req_parent (tenant_id, parent_id),
    KEY idx_req_vendor (tenant_id, assignee_vendor_id, status),
    KEY idx_req_release (tenant_id, release_id),
    KEY idx_req_title (tenant_id, title(64))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE requirement_domain_rel (
    tenant_id      BIGINT NOT NULL,
    requirement_id BIGINT NOT NULL,
    domain_id      BIGINT NOT NULL,
    PRIMARY KEY (requirement_id, domain_id),
    KEY idx_rdr_domain (tenant_id, domain_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE `release` (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id            BIGINT          NOT NULL,
    code                 VARCHAR(24)     NOT NULL,
    name                 VARCHAR(200)    NOT NULL,
    domain_id            BIGINT          NOT NULL,
    status               VARCHAR(16)     NOT NULL DEFAULT 'PLAN',
    start_date           DATE            NOT NULL,
    end_date             DATE            NOT NULL,
    planned_release_date DATE            NOT NULL,
    gate_mode            VARCHAR(8)      NOT NULL DEFAULT 'STRICT',
    locked_at            DATETIME(3)     NULL,
    released_at          DATETIME(3)     NULL,
    created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by           BIGINT          NULL,
    updated_by           BIGINT          NULL,
    version              INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_release_code (tenant_id, code),
    KEY idx_release_domain (tenant_id, domain_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE milestone (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT          NOT NULL,
    release_id   BIGINT          NOT NULL,
    name         VARCHAR(200)    NOT NULL,
    planned_date DATE            NOT NULL,
    status       VARCHAR(16)     NOT NULL DEFAULT 'PLANNED',
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by   BIGINT          NULL,
    updated_by   BIGINT          NULL,
    PRIMARY KEY (id),
    KEY idx_ms_release (tenant_id, release_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE release_requirement (
    tenant_id      BIGINT      NOT NULL,
    release_id     BIGINT      NOT NULL,
    requirement_id BIGINT      NOT NULL,
    added_by       BIGINT      NULL,
    added_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (release_id, requirement_id),
    KEY idx_rr_req (tenant_id, requirement_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE attachment (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT          NOT NULL,
    biz_type     VARCHAR(24)     NOT NULL,
    biz_id       BIGINT          NOT NULL,
    file_name    VARCHAR(200)    NOT NULL,
    path         VARCHAR(500)    NOT NULL,
    size         BIGINT          NOT NULL DEFAULT 0,
    content_type VARCHAR(100)    NULL,
    sha256       CHAR(64)        NULL,
    uploader_id  BIGINT          NULL,
    domain_id    BIGINT          NULL,
    vendor_id    BIGINT          NULL,
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by   BIGINT          NULL,
    updated_by   BIGINT          NULL,
    PRIMARY KEY (id),
    KEY idx_att_biz (tenant_id, biz_type, biz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 审批

CREATE TABLE wf_definition (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    name            VARCHAR(128)    NOT NULL,
    biz_type        VARCHAR(32)     NOT NULL,
    req_type        VARCHAR(32)     NOT NULL DEFAULT 'ANY',
    domain_id       BIGINT          NOT NULL DEFAULT 0,
    version         INT             NOT NULL DEFAULT 1,
    definition_json JSON            NOT NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'DRAFT',
    is_default      TINYINT         NOT NULL DEFAULT 0,
    published_at    DATETIME(3)     NULL,
    published_by    BIGINT          NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NULL,
    created_by      BIGINT          NULL,
    updated_by      BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wf_def (tenant_id, biz_type, req_type, domain_id, version),
    KEY idx_wf_lookup (tenant_id, biz_type, status, is_default)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE wf_instance (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT          NOT NULL,
    definition_id      BIGINT          NOT NULL,
    definition_version INT             NOT NULL,
    biz_type           VARCHAR(32)     NOT NULL,
    biz_id             BIGINT          NOT NULL,
    initiator_id       BIGINT          NOT NULL,
    status             VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    current_node       VARCHAR(64)     NULL,
    active_flag        TINYINT         NULL,
    started_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ended_at           DATETIME(3)     NULL,
    created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by         BIGINT          NULL,
    updated_by         BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wf_instance_active (tenant_id, biz_type, biz_id, active_flag),
    KEY idx_wf_i_todo (tenant_id, status, initiator_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE wf_task (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT          NOT NULL,
    instance_id    BIGINT          NOT NULL,
    node_key       VARCHAR(64)     NOT NULL,
    node_name      VARCHAR(128)    NULL,
    approver_id    BIGINT          NOT NULL,
    mode           VARCHAR(8)      NOT NULL DEFAULT 'ANY',
    status         VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    result         VARCHAR(16)     NULL,
    comment        VARCHAR(512)    NULL,
    origin         VARCHAR(16)     NOT NULL DEFAULT 'INITIAL',
    add_sign_type  VARCHAR(8)      NULL,
    parent_task_id BIGINT          NULL,
    sign_no        INT             NOT NULL DEFAULT 1,
    sort_no        INT             NOT NULL DEFAULT 0,
    handled_at     DATETIME(3)     NULL,
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by     BIGINT          NULL,
    updated_by     BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wf_task (instance_id, node_key, approver_id, sign_no),
    KEY idx_wf_task_todo (tenant_id, approver_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE wf_history (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT          NOT NULL,
    instance_id BIGINT          NOT NULL,
    task_id     BIGINT          NULL,
    action      VARCHAR(32)     NOT NULL,
    operator_id BIGINT          NULL,
    comment     VARCHAR(512)    NULL,
    detail_json JSON            NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_wf_h (tenant_id, instance_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE wf_cc (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT          NOT NULL,
    instance_id BIGINT          NOT NULL,
    node_key    VARCHAR(64)     NOT NULL,
    receiver_id BIGINT          NOT NULL,
    read_at     DATETIME(3)     NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_wf_cc (tenant_id, receiver_id, read_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 发布单（准入与执行）

CREATE TABLE deploy_order (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id             BIGINT          NOT NULL,
    code                  VARCHAR(24)     NOT NULL,
    release_id            BIGINT          NOT NULL,
    env                   VARCHAR(16)     NOT NULL,
    window_start          DATETIME(3)     NULL,
    window_end            DATETIME(3)     NULL,
    artifact_version      VARCHAR(64)     NULL,
    steps                 TEXT            NULL,
    rollback_plan         TEXT            NULL,
    impact                TEXT            NULL,
    owner_id              BIGINT          NULL,
    status                VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    admission_result_json JSON            NULL,
    forced_release        BIT(1)          NOT NULL DEFAULT b'0',
    force_release_reason  VARCHAR(500)    NULL,
    force_by              BIGINT          NULL,
    force_at              DATETIME(3)     NULL,
    execution_result      VARCHAR(16)     NULL,
    execution_note        VARCHAR(1000)   NULL,
    defect_id             BIGINT          NULL,
    started_at            DATETIME(3)     NULL,
    ended_at              DATETIME(3)     NULL,
    duration_minutes      INT             NULL,
    created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by            BIGINT          NULL,
    updated_by            BIGINT          NULL,
    version               INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_deploy_code (tenant_id, code),
    KEY idx_deploy_release (tenant_id, release_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE deploy_order_requirement (
    tenant_id      BIGINT NOT NULL,
    deploy_order_id BIGINT NOT NULL,
    requirement_id BIGINT NOT NULL,
    PRIMARY KEY (deploy_order_id, requirement_id),
    KEY idx_dor_req (tenant_id, requirement_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 缺陷、任务、迭代

CREATE TABLE defect (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT          NOT NULL,
    code                VARCHAR(24)     NOT NULL,
    requirement_id      BIGINT          NULL,
    release_id          BIGINT          NULL,
    test_case_id        BIGINT          NULL,
    test_case_version   INT             NULL,
    test_execution_id   BIGINT          NULL,
    title               VARCHAR(200)    NOT NULL,
    description         TEXT            NULL,
    repro_steps         TEXT            NULL,
    severity            VARCHAR(4)      NOT NULL,
    priority            VARCHAR(4)      NOT NULL,
    status              VARCHAR(16)     NOT NULL DEFAULT 'NEW',
    reporter_id         BIGINT          NOT NULL,
    handler_id          BIGINT          NULL,
    domain_id           BIGINT          NOT NULL,
    vendor_id           BIGINT          NULL,
    reopen_count        INT             NOT NULL DEFAULT 0,
    expected_fix_date   DATE            NULL,
    found_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    closed_at           DATETIME(3)     NULL,
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by          BIGINT          NULL,
    updated_by          BIGINT          NULL,
    version             INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_defect_code (tenant_id, code),
    KEY idx_defect_list (tenant_id, domain_id, vendor_id, status, severity),
    KEY idx_defect_req (tenant_id, requirement_id),
    KEY idx_defect_closed (tenant_id, closed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE defect_history (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT          NOT NULL,
    defect_id   BIGINT          NOT NULL,
    action      VARCHAR(24)     NOT NULL,
    from_status VARCHAR(16)     NULL,
    to_status   VARCHAR(16)     NULL,
    operator_id BIGINT          NULL,
    comment     VARCHAR(512)    NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_dh (tenant_id, defect_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE sprint (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT          NOT NULL,
    release_id    BIGINT          NULL,
    name          VARCHAR(200)    NOT NULL,
    goal          VARCHAR(500)    NULL,
    start_date    DATE            NOT NULL,
    end_date      DATE            NOT NULL,
    capacity_days DECIMAL(6, 1)   NULL,
    status        VARCHAR(16)     NOT NULL DEFAULT 'PLANNING',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by    BIGINT          NULL,
    updated_by    BIGINT          NULL,
    PRIMARY KEY (id),
    KEY idx_sprint (tenant_id, release_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE task (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT          NOT NULL,
    requirement_id BIGINT          NOT NULL,
    sprint_id      BIGINT          NULL,
    title          VARCHAR(200)    NOT NULL,
    assignee_id    BIGINT          NULL,
    estimate_hours DECIMAL(6, 1)   NULL,
    start_date     DATE            NULL,
    due_date       DATE            NULL,
    status         VARCHAR(8)      NOT NULL DEFAULT 'TODO',
    sort_no        INT             NOT NULL DEFAULT 0,
    domain_id      BIGINT          NOT NULL,
    vendor_id      BIGINT          NULL,
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by     BIGINT          NULL,
    updated_by     BIGINT          NULL,
    version        INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_task_req (tenant_id, requirement_id, status),
    KEY idx_task_board (tenant_id, sprint_id, status, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 测试

CREATE TABLE test_module (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT          NOT NULL,
    parent_id  BIGINT          NULL,
    name       VARCHAR(200)    NOT NULL,
    path       VARCHAR(512)    NOT NULL,
    level      TINYINT         NOT NULL DEFAULT 1,
    domain_id  BIGINT          NULL,
    vendor_id  BIGINT          NULL,
    sort_no    INT             NOT NULL DEFAULT 0,
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT          NULL,
    updated_by BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_module_path (tenant_id, path),
    KEY idx_module_parent (tenant_id, parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE test_case (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT          NOT NULL,
    code           VARCHAR(24)     NOT NULL,
    module_id      BIGINT          NOT NULL,
    title          VARCHAR(200)    NOT NULL,
    type           VARCHAR(8)      NOT NULL,
    priority       VARCHAR(4)      NOT NULL,
    precondition   TEXT            NULL,
    tags           VARCHAR(200)    NULL,
    automated      TINYINT         NOT NULL DEFAULT 0,
    script_ref     VARCHAR(300)    NULL,
    status         VARCHAR(12)     NOT NULL DEFAULT 'ACTIVE',
    current_version INT            NOT NULL DEFAULT 1,
    requirement_id BIGINT          NULL,
    domain_id      BIGINT          NULL,
    vendor_id      BIGINT          NULL,
    deleted_at     DATETIME(3)     NULL,
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by     BIGINT          NULL,
    updated_by     BIGINT          NULL,
    version        INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_code (tenant_id, code),
    KEY idx_case_module (tenant_id, module_id, deleted_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE test_case_step (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT       NOT NULL,
    case_id    BIGINT       NOT NULL,
    version_no INT          NOT NULL,
    step_no    INT          NOT NULL,
    action     VARCHAR(500) NOT NULL,
    expected   VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_step (case_id, version_no, step_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE test_case_version (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    case_id       BIGINT      NOT NULL,
    version_no    INT         NOT NULL,
    snapshot_json JSON        NOT NULL,
    editor_id     BIGINT      NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_ver (case_id, version_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE test_plan (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT          NOT NULL,
    release_id   BIGINT          NOT NULL,
    name         VARCHAR(200)    NOT NULL,
    window_start DATETIME(3)     NULL,
    window_end   DATETIME(3)     NULL,
    owner_id     BIGINT          NULL,
    status       VARCHAR(12)     NOT NULL DEFAULT 'DRAFT',
    gate_mode    VARCHAR(8)      NULL,
    domain_id    BIGINT          NULL,
    vendor_id    BIGINT          NULL,
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by   BIGINT          NULL,
    updated_by   BIGINT          NULL,
    PRIMARY KEY (id),
    KEY idx_plan (tenant_id, release_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE test_plan_case (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT        NOT NULL,
    plan_id          BIGINT        NOT NULL,
    case_id          BIGINT        NOT NULL,
    case_version     INT           NOT NULL,
    requirement_id   BIGINT        NULL,
    executor_id      BIGINT        NULL,
    result           VARCHAR(12)   NOT NULL DEFAULT 'NOT_RUN',
    execution_id     BIGINT        NULL,
    last_executed_at DATETIME(3)   NULL,
    note             VARCHAR(500)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_case (plan_id, case_id),
    KEY idx_plan_case_req (tenant_id, requirement_id, result)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE test_execution (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT        NOT NULL,
    plan_id        BIGINT        NULL,
    case_id        BIGINT        NULL,
    case_version   INT           NULL,
    requirement_id BIGINT        NULL,
    source         VARCHAR(8)    NOT NULL,
    run_id         VARCHAR(64)   NULL,
    result         VARCHAR(12)   NOT NULL,
    duration_ms    INT           NULL,
    log_url        VARCHAR(500)  NULL,
    evidence_file  VARCHAR(300)  NULL,
    executor_id    BIGINT        NULL,
    executed_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    client_id      BIGINT        NULL,
    ip             VARCHAR(45)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_exec_auto (tenant_id, client_id, run_id, case_id),
    KEY idx_exec_case (tenant_id, case_id, executed_at),
    KEY idx_exec_plan (tenant_id, plan_id, executed_at),
    CONSTRAINT ck_exec_auto CHECK (source = 'MANUAL'
        OR (client_id IS NOT NULL AND run_id IS NOT NULL AND case_id IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 指标、开放 API、审计、通知

CREATE TABLE metric_definition (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT          NOT NULL,
    code         VARCHAR(32)     NOT NULL,
    name         VARCHAR(100)    NOT NULL,
    topic        VARCHAR(32)     NOT NULL,
    formula      VARCHAR(500)    NOT NULL,
    datasource   VARCHAR(200)    NULL,
    caliber_note VARCHAR(500)    NULL,
    owner_id     BIGINT          NULL,
    version      INT             NOT NULL DEFAULT 1,
    effective_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by   BIGINT          NULL,
    updated_by   BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_metric (tenant_id, code, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE api_client (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT          NOT NULL,
    name           VARCHAR(100)    NOT NULL,
    app_key        VARCHAR(64)     NOT NULL,
    secret_hash    VARCHAR(200)    NOT NULL,
    secret_enc     VARBINARY(512)  NULL,
    scopes         VARCHAR(300)    NULL,
    ip_whitelist   VARCHAR(500)    NULL,
    rate_limit_qps INT             NOT NULL DEFAULT 100,
    status         VARCHAR(12)     NOT NULL DEFAULT 'ACTIVE',
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by     BIGINT          NULL,
    updated_by     BIGINT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_key (app_key),
    KEY idx_client_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE api_call_log (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT          NOT NULL,
    client_id   BIGINT          NULL,
    path        VARCHAR(200)    NOT NULL,
    method      VARCHAR(8)      NOT NULL,
    status_code INT             NOT NULL,
    result_code VARCHAR(48)     NULL,
    duration_ms INT             NULL,
    ip          VARCHAR(45)     NULL,
    request_id  VARCHAR(64)     NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_acl (tenant_id, client_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE audit_log (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT          NOT NULL,
    user_id     BIGINT          NULL,
    action      VARCHAR(48)     NOT NULL,
    biz_type    VARCHAR(24)     NULL,
    biz_id      BIGINT          NULL,
    detail_json JSON            NULL,
    ip          VARCHAR(45)     NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_audit_biz (tenant_id, biz_type, biz_id, created_at),
    KEY idx_audit_user (tenant_id, user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE notification (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT           NOT NULL,
    user_id    BIGINT           NOT NULL,
    type       VARCHAR(32)      NOT NULL,
    title      VARCHAR(200)     NOT NULL,
    content    VARCHAR(1000)    NULL,
    biz_type   VARCHAR(24)      NULL,
    biz_id     BIGINT           NULL,
    read_flag  TINYINT          NOT NULL DEFAULT 0,
    created_at DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_notify (tenant_id, user_id, read_flag, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
