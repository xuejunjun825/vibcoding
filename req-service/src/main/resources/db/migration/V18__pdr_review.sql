-- ---------------------------------------------------------------- r41：PDR 评审单
--
-- 一张 PDR 评审单是「一个产品需求的一次评审会话」：谁发起、评谁（评审人快照）、用什么模式
-- （MODE_ALL 会签 / MODE_ANY 或签）、什么时候截止、结论是什么、什么时候关闭。意见不在本表，
-- 在 V19 的 pdr_review_opinion（一人可多条，设计 §4.5）。
--
-- 为什么评审人存快照 JSON（reviewer_ids）而不建 pdr_review_reviewer 表：评审人一旦选定就是
-- 契约的一部分，项目组后续增删人不得改变「这张单应该由谁评」；而本单只需读快照、不需要按评审人
-- 反查列表（stats 的评审人维度读的是意见表的 reviewer_id）。用一列 JSON 换掉第 5 张表，代价是
-- 「我待评审的单」只能按状态 + 项目组现算——那是一行 EXISTS，比多一张需要同步的表便宜。
--
-- reviewer_source 记录评审人从哪来：PROJECT=项目组名册，FALLBACK=名册为空回退「产品经理 + 域负责人」
-- （设计 §6 R2 / 验收 B5 的 fallback:true）。
--
-- 为什么 in_review_req 是生成列：设计 R5 要求「同一需求最多 1 张在评评审单」，而 MySQL 的唯一
-- 索引不认 WHERE。生成列把「在评」折叠成 requirement_id、其余折叠成 NULL（NULL 不参与唯一），
-- 于是一条 uk 就表达了「在评唯一」，且不需要应用层加锁。并发双开由 409 兜底（DuplicateKey）。
--
-- 幂等：建表 CREATE TABLE IF NOT EXISTS（V18 与 V17 同手法，理由见 V17 头注释）。

CREATE TABLE IF NOT EXISTS `pdr_review` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`       BIGINT          NOT NULL,
  `requirement_id`  BIGINT UNSIGNED NOT NULL,
  `project_id`      BIGINT UNSIGNED NULL,
  `instance_id`     BIGINT UNSIGNED NULL,
  `mode`            VARCHAR(12)     NOT NULL DEFAULT 'MODE_ALL',
  `status`          VARCHAR(16)     NOT NULL,
  `conclusion`      VARCHAR(16)     NULL,
  `initiator_id`    BIGINT UNSIGNED NOT NULL,
  `reviewer_ids`    JSON            NOT NULL,
  `reviewer_source` VARCHAR(12)     NOT NULL DEFAULT 'PROJECT',
  `note`            VARCHAR(500)    NULL,
  `due_at`          DATETIME(3)     NULL,
  `closed_at`       DATETIME(3)     NULL,
  `in_review_req`   BIGINT UNSIGNED GENERATED ALWAYS AS
                    (CASE WHEN `status` = 'IN_REVIEW' THEN `requirement_id` ELSE NULL END) STORED,
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `created_by`      BIGINT          NULL,
  `updated_by`      BIGINT          NULL,
  `version`         INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pdr_in_review` (`tenant_id`, `in_review_req`),
  KEY `idx_pdr_req` (`tenant_id`, `requirement_id`, `id`),
  KEY `idx_pdr_status` (`tenant_id`, `status`, `created_at`),
  KEY `idx_pdr_project` (`tenant_id`, `project_id`),
  KEY `idx_pdr_initiator` (`tenant_id`, `initiator_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
