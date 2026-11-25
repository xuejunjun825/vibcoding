-- ---------------------------------------------------------------- r47：「我也需要」跟单
--
-- 一张表：谁在等哪个需求（follow）。运营视角的「热度」= 该需求当前跟单人数，
-- 不进 requirement 表的冗余列——跟单是高频写、需求是低频写，混在一张表里会让
-- 跟单把需求行的 version 撞出乐观锁冲突（PDR 评审/变更单都在抢这一行）。
--
-- 为什么唯一键 (tenant_id, requirement_id, user_id)：跟单接口的幂等由数据库兜底，
-- 而不是「先查再插」。两个人同时点「我也需要」只会有一行落库，另一条 INSERT 撞
-- 唯一键后由服务层识别为「已跟单」，返回 200 + 当前 followCount（契约 §3.2）。
--
-- 为什么 note 限 255：跟单备注是「一句话诉求」，不是讨论区；详情页最多展示两行。
--
-- 为什么没有 FK：本仓库全库无 FK（r1 起），隔离靠 tenant_id + ScopeSql，清理顺序靠
-- DemoFixture.DELETE_ORDER（requirement_follow 必须排在 requirement 之前）。
--
-- 索引语义：
--   * uk_follow：幂等约束 + 按 (tenant, requirement, user) 点查（followedByMe）。
--   * idx_follow_req：详情/列表批量统计 followCount 与 followers 分页（(tenant, req) 前缀）。
--   * idx_follow_user：「我参与的」类查询（(tenant, user) 前缀）。

CREATE TABLE IF NOT EXISTS `requirement_follow` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT       NOT NULL,
  `requirement_id` BIGINT       NOT NULL,
  `user_id`        BIGINT       NOT NULL,
  `org_id`         BIGINT       NULL,
  `note`           VARCHAR(255) NULL,
  `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_follow` (`tenant_id`, `requirement_id`, `user_id`),
  KEY `idx_follow_req` (`tenant_id`, `requirement_id`),
  KEY `idx_follow_user` (`tenant_id`, `user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
