-- ---------------------------------------------------------------- r41：项目组（PDR 评审人来源）
--
-- 两张表：project 是「一次交付的组织单元」，project_member 是它的成员名册（角色分级
-- PO/PM/DEV/QA/SME）。PDR 评审发起时从名册里挑评审人——名册为空才回退「产品经理 + 域负责人」
-- （设计 §4.1 / §6 R2），所以名册是评审人来源的唯一事实，不在 pdr_review 里另存一份成员表。
--
-- 为什么另开 V17（设计 §11 曾建议三条合并成一条 V17）：用户 brief 要求 V17=项目组、
-- V18=pdr_review、V19=pdr_review_opinion 三条可独立回滚——评审表出问题时不牵连项目组。
--
-- 为什么 uk_project_member 是 (project_id, user_id) 而不是含 active：成员「退出再进来」必须是
-- 同一行 active 0→1，否则 uk 让第二次 INSERT 直接失败（重复成员是 409 而不是新行）。名字册的
-- 历史由 audit_log 承担，不由行数承担。
--
-- 为什么 requirement 加 project_id 而不加 FK：本仓库全库无 FK（r1 起）；且历史需求没有项目组，
-- 列必须可空。评审发起时 project_id 为空 → reviewer_source=FALLBACK。
--
-- 为什么 task 加 description：意见转任务要把「来源：PDR 评审 #id · 分类 · 原文」写进任务描述
-- （设计 §4.5）。task 自 V1 起没有 description 列，这一列只由内部建任务端点写入，可空。
--
-- 幂等：建表 CREATE TABLE IF NOT EXISTS；加列/加索引先查 information_schema 再 PREPARE/EXECUTE
-- （与 V9/V10 同手法）。Flyway 只跑一次，但本地库反复 make platform-up/down 时表可能已存在。

CREATE TABLE IF NOT EXISTS `project` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`     BIGINT          NOT NULL,
  `code`          VARCHAR(32)     NOT NULL,
  `name`          VARCHAR(120)    NOT NULL,
  `domain_id`     BIGINT UNSIGNED NULL,
  `owner_user_id` BIGINT UNSIGNED NULL,
  `status`        VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `created_by`    BIGINT          NULL,
  `updated_by`    BIGINT          NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_code` (`tenant_id`, `code`),
  KEY `idx_project_domain` (`tenant_id`, `domain_id`),
  KEY `idx_project_status` (`tenant_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `project_member` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`       BIGINT          NOT NULL,
  `project_id`      BIGINT UNSIGNED NOT NULL,
  `user_id`         BIGINT UNSIGNED NOT NULL,
  `role_in_project` VARCHAR(8)      NOT NULL,
  `active`          INT             NOT NULL DEFAULT 1,
  `joined_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `created_by`      BIGINT          NULL,
  `updated_by`      BIGINT          NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_member` (`project_id`, `user_id`),
  KEY `idx_project_member_user` (`tenant_id`, `user_id`),
  KEY `idx_project_member_active` (`tenant_id`, `project_id`, `active`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- requirement.project_id

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE requirement ADD COLUMN project_id BIGINT UNSIGNED NULL',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'requirement' AND COLUMN_NAME = 'project_id');
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 反查「这个项目下有哪些需求」：评审统计按项目/域聚合时用它，避免全表扫 type。
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE requirement ADD KEY idx_req_project (tenant_id, project_id)',
    'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'requirement' AND INDEX_NAME = 'idx_req_project');
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- ---------------------------------------------------------------- task.description

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE task ADD COLUMN description TEXT NULL',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task' AND COLUMN_NAME = 'description');
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
