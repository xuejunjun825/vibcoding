-- ---------------------------------------------------------------- r41：PDR 评审意见
--
-- 一行 = 一位评审人在一张评审单上的一条意见。同一个人在同一张单上可以有多条（一次提交多条，
-- 设计 §4.5），所以**没有** (review_id, reviewer_id) 唯一约束——那正是设计文档点名不要的东西：
-- 「范围理解错了」和「验收标准缺失」是两条独立可转任务的待办，压成一行就丢了一条。
--
-- seq 是「同一张单内的展示顺序」：一次提交多条时按请求顺序 1..n 递增（不是全局序号），列表按
-- (reviewer_id, seq) 读。并发提交同一人时 seq 可能重号，但那不会丢数据——列表排序退化成 id。
--
-- decision 与评审单结论是两件事：结论按「评审人」聚合（MODE_ALL 全员 PASS 才算通过），
-- 意见按「行」计数（统计口径见设计 §4.4）；一条 NEED_CHANGE 就让评审单 REJECTED，但同一人
-- 另外两条 PASS 仍然各自是一条意见、各自可转任务（验收 D1 明确「结论不因多条意见放大票数」）。
--
-- category 是九类字典的 code（RANGE/TECH/SCHEDULE/RISK/ACCEPTANCE/UI/DATA/COMPLIANCE/OTHER，
-- sys_dict.type = review_opinion_category），合法性在服务层校验（400 INVALID_CATEGORY +
-- detail.allowed[]），不写 CHECK：字典要能被租户改文案，MySQL CHECK 会把它冻在迁移里。
--
-- status 是意见自身的生命周期 OPEN → TASKED → RESOLVED（设计 §4.5）；task_id 指交付域的 task，
-- 没有 FK（本仓库全库无 FK），跨域一致性由「同一事务内回写」+ 审计 PDR_OPINION_TO_TASK 保证。
--
-- 幂等：建表 CREATE TABLE IF NOT EXISTS（与 V17/V18 同手法）。

CREATE TABLE IF NOT EXISTS `pdr_review_opinion` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT          NOT NULL,
  `review_id`   BIGINT UNSIGNED NOT NULL,
  `reviewer_id` BIGINT UNSIGNED NOT NULL,
  `seq`         INT             NOT NULL DEFAULT 1,
  `decision`    VARCHAR(16)     NOT NULL,
  `category`    VARCHAR(16)     NOT NULL,
  `comment`     TEXT            NULL,
  `status`      VARCHAR(16)     NOT NULL DEFAULT 'OPEN',
  `task_id`     BIGINT UNSIGNED NULL,
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `created_by`  BIGINT          NULL,
  `updated_by`  BIGINT          NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pdr_opinion_review` (`review_id`),
  KEY `idx_pdr_opinion_reviewer` (`review_id`, `reviewer_id`),
  KEY `idx_pdr_opinion_category` (`tenant_id`, `category`),
  KEY `idx_pdr_opinion_task` (`task_id`),
  KEY `idx_pdr_opinion_status` (`tenant_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
