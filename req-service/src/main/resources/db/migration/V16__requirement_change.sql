-- ---------------------------------------------------------------- r40：需求变更单
--
-- 两张表：req_change 是「留痕单据」本身（before/after/impact 三份 JSON + 审批实例 +
-- apply 元数据），req_change_item 是 apply 成功后的逐字段证据（主键 (change_id, field)
-- 同时是幂等约束——同一单同一字段只可能落一行）。
--
-- 为什么幂等：空库首启与已有库升级跑同一份文件；重复执行不报错是 AC-1 的原文要求，
-- 建表一律 CREATE TABLE IF NOT EXISTS。
--
-- 为什么不用外键：本仓库全库无 FK（r1 起），隔离靠 tenant_id + ScopeSql，删除顺序靠
-- DemoFixture.DELETE_ORDER 与业务侧事务。这里连 requirement_id 也不加 FK：变更单要能
-- 在需求被归档/演示数据重置后仍作为审计证据保留，靠 DELETE_ORDER 显式清理。
--
-- 为什么 JSON 不做 CHECK：before/after/impact 的封闭 schema 只在 Java 侧校验
-- （RequirementChangeFields / RequirementChangeImpactAssessor）；MySQL 各版本 JSON CHECK
-- 行为不一致，写进迁移会变成「能存不能查」。
--
-- 为什么不存 reject_reason / approver 副本：驳回原因在 wf_history，审批人在 wf_task；
-- 冗余副本会与审批引擎的真值分叉，详情页一律回查审批实例。
--
-- 索引语义（AC-1 要求至少覆盖这两条）：
--   * idx_req_change_req：需求详情第 10 个 Tab 的列表/分页（倒序读取）。
--   * idx_req_change_status：列表按状态过滤；审批中心摘要也按 (tenant, status) 扫。
--
-- req_change_item 只在 apply 成功时写入；待审批/驳回/撤回 0 行。before_value/after_value
-- 是展示文本（TEXT），不是可比较的强类型列——比较一律在 Java 侧按 before_json 的
-- 归一化值做（RequirementChangeFields.sameValue）。

CREATE TABLE IF NOT EXISTS `req_change` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT       NOT NULL,
  `requirement_id` BIGINT       NOT NULL,
  `change_type`    VARCHAR(16)  NOT NULL,
  `status`         VARCHAR(16)  NOT NULL,
  `reason`         VARCHAR(500) NOT NULL,
  `before_json`    JSON         NOT NULL,
  `after_json`     JSON         NOT NULL,
  `impact_json`    JSON         NOT NULL,
  `instance_id`    BIGINT       NULL,
  `applied_at`     DATETIME(3)  NULL,
  `applied_by`     BIGINT       NULL,
  `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `created_by`     BIGINT       NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_req_change_req` (`tenant_id`, `requirement_id`, `created_at`),
  KEY `idx_req_change_status` (`tenant_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `req_change_item` (
  `change_id`    BIGINT      NOT NULL,
  `field`        VARCHAR(64) NOT NULL,
  `before_value` TEXT        NULL,
  `after_value`  TEXT        NULL,
  PRIMARY KEY (`change_id`, `field`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
