-- ---------------------------------------------------------------- r47：重复需求合并
--
-- 只加两列 + 一个索引，不建历史表：合并是一次性动作，留痕在 audit_log
-- （action = REQUIREMENT_MERGED，detail 带 targetId/reason/movedFollows/movedChildren），
-- 再建一张 requirement_merge 表只会让「谁被合进了谁」出现两份真值。
--
--   * merged_into_id：被合并需求的指向目标；NULL = 没被合并过。它同时是合并操作的
--     幂等/#409 ALREADY_MERGED 的判据（非 NULL 即已合并，不再允许二次合并）。
--   * close_reason：需求关闭的原因，取值 MERGED / REJECTED / DONE / NULL。状态列不扩枚举
--     （契约 §8 明确不新增状态），关闭仍然是 status = CLOSED，只是多一个原因。
--   * idx_req_merged：反向查询「合进了某个目标的都有谁」（列表过滤 + 合并前置校验）。
--
-- 幂等：MySQL 8/9 没有 `ADD COLUMN IF NOT EXISTS`（MariaDB 语法），而 Flyway 一条失败的
-- 迁移会以 success = 0 留在 flyway_schema_history 里、让之后每次启动都卡在 validate，
-- 所以先查 information_schema 再决定是否执行 DDL（与 V8/V10/V20 同手法）。

SET @has_merged_into := (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'requirement'
       AND COLUMN_NAME = 'merged_into_id');

SET @ddl := IF(@has_merged_into = 0,
    'ALTER TABLE `requirement` ADD COLUMN `merged_into_id` BIGINT NULL AFTER `parent_id`',
    'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_close_reason := (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'requirement'
       AND COLUMN_NAME = 'close_reason');

SET @ddl := IF(@has_close_reason = 0,
    'ALTER TABLE `requirement` ADD COLUMN `close_reason` VARCHAR(32) NULL AFTER `status`',
    'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_merged_index := (
    SELECT COUNT(*)
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'requirement'
       AND INDEX_NAME = 'idx_req_merged');

SET @ddl := IF(@has_merged_index = 0,
    'ALTER TABLE `requirement` ADD KEY `idx_req_merged` (`tenant_id`, `merged_into_id`)',
    'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
