-- ---------------------------------------------------------------- r39：自动化规则引擎
--
-- 四张表，严格按 PRD 3.1 的列，一列不多（AC-1 要逐列核对：日志页要的规则名用
-- LEFT JOIN automation_rule，结束时间由 created_at + duration_ms 推导，因此这里
-- 不加 rule_name / finished_at 这类副本）。
--
-- 为什么幂等：空库首启与已有库升级跑同一份文件；重复执行不报错是 AC-1 的原文要求，
-- 也是 r30/r33 事故（半途失败后重跑改不动）的教训。建表一律 CREATE TABLE IF NOT EXISTS。
--
-- 为什么不用外键：本仓库全库无 FK（r1 起），隔离靠 tenant_id + ScopeSql，删除顺序靠
-- DemoFixture.DELETE_ORDER 与业务侧事务；引入 FK 会让既有 demo-reset 的 delete 顺序变成雷区。
--
-- 为什么 JSON 列不做 CHECK：condition_json / actions_json 的合法性只在 Java 侧封闭
-- schema 校验（AutomationSpecs）。MySQL 的 JSON CHECK 在各版本行为不一致，写进迁移
-- 会变成「能存不能查」。
--
-- 三处唯一键的语义（与代码注释一致，改这里必须同步 AutomationRunWriter / AutomationEventStore）：
--   * uk_event_dedupe 是「至多一条」而非「恰好一条」：审计回调重放走 INSERT IGNORE。
--   * uk_run_dedupe 含 NULL 列，MySQL 把 NULL 视为互不相等，所以除 SCHEDULE 截断行
--     （requirement_id IS NULL，写前 INSERT ... WHERE NOT EXISTS）之外，任何 run 都必须
--     带真实 requirement_id。
--   * uk_req_tag 是 ADD_TAG 动作的幂等位：重复即 SUCCESS + exists，不报错。

CREATE TABLE IF NOT EXISTS `automation_rule` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT       NOT NULL,
  `name`           VARCHAR(100) NOT NULL COMMENT '去首尾空格后唯一',
  `enabled`        TINYINT(1)   NOT NULL DEFAULT 1,
  `trigger_type`   VARCHAR(32)  NOT NULL COMMENT 'REQUIREMENT_CREATED/STATUS_CHANGE/FIELD_CHANGE/SCHEDULE',
  `condition_json` JSON         NOT NULL COMMENT '封闭 schema，见 TECHNICAL §4.1',
  `actions_json`   JSON         NOT NULL COMMENT '白名单数组，≤10 条，见 §5.1',
  `scope_type`     VARCHAR(16)  NOT NULL COMMENT 'DOMAIN/VENDOR',
  `scope_id`       BIGINT       NOT NULL COMMENT 'biz_domain.id 或 vendor.id',
  `priority`       INT          NOT NULL DEFAULT 100 COMMENT '数值小者先执行',
  `version`        INT          NOT NULL DEFAULT 1 COMMENT '乐观锁 + 执行日志回溯',
  `created_by`     BIGINT       NOT NULL,
  `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `updated_by`     BIGINT       NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_name` (`tenant_id`, `name`),
  KEY `idx_rule_dispatch` (`tenant_id`, `enabled`, `trigger_type`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动化规则（r39）';

CREATE TABLE IF NOT EXISTS `automation_event` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT       NOT NULL,
  `event_id`       VARCHAR(120) NOT NULL COMMENT '服务端生成，见 §3.2',
  `event_type`     VARCHAR(32)  NOT NULL,
  `requirement_id` BIGINT       NULL COMMENT 'SCHEDULE 允许为空',
  `origin`         VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'USER/SYSTEM/RULE',
  `chain_depth`    INT          NOT NULL DEFAULT 1 COMMENT '≥4 一律 DROPPED/CHAIN_DEPTH',
  `payload_json`   JSON         NOT NULL COMMENT '事件快照，条件只在这个快照上求值',
  `status`         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/DONE/DROPPED',
  `drop_reason`    VARCHAR(32)  NULL COMMENT 'RULE_ORIGIN/CHAIN_DEPTH/MALFORMED',
  `attempts`       INT          NOT NULL DEFAULT 0,
  `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `processed_at`   DATETIME(3)  NULL,
  `locked_at`      DATETIME(3)  NULL COMMENT 'PROCESSING 超过 60s 可被重新领取',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_dedupe` (`tenant_id`, `event_id`),
  KEY `idx_event_pending` (`tenant_id`, `status`, `id`),
  KEY `idx_event_requirement` (`tenant_id`, `requirement_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动化事件 outbox（r39）';

CREATE TABLE IF NOT EXISTS `automation_run` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT       NOT NULL,
  `rule_id`        BIGINT       NOT NULL,
  `rule_version`   INT          NOT NULL,
  `requirement_id` BIGINT       NULL,
  `event_id`       VARCHAR(120) NOT NULL,
  `event_type`     VARCHAR(32)  NOT NULL,
  `status`         VARCHAR(16)  NOT NULL COMMENT 'RUNNING/SUCCESS/PARTIAL/FAILED/SKIPPED',
  `skip_reason`    VARCHAR(32)  NULL COMMENT '见 §6.4；事件级原因不占本列',
  `actions_json`   JSON         NULL COMMENT '逐动作 {type,status,message,target}',
  `duration_ms`    BIGINT       NOT NULL DEFAULT 0,
  `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_dedupe` (`tenant_id`, `rule_id`, `requirement_id`, `event_id`),
  KEY `idx_run_rule_time` (`tenant_id`, `rule_id`, `created_at`),
  KEY `idx_run_req` (`tenant_id`, `requirement_id`, `created_at`),
  KEY `idx_run_status` (`tenant_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则执行日志（r39）';

CREATE TABLE IF NOT EXISTS `requirement_tag` (
  `id`             BIGINT      NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT      NOT NULL,
  `requirement_id` BIGINT      NOT NULL,
  `tag`            VARCHAR(32) NOT NULL,
  `source`         VARCHAR(16) NOT NULL COMMENT 'RULE/MANUAL',
  `created_by`     BIGINT      NOT NULL,
  `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_req_tag` (`tenant_id`, `requirement_id`, `tag`),
  KEY `idx_tag_req` (`tenant_id`, `requirement_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求标签（r39）';
