-- ---------------------------------------------------------------- r41 修复：意见乐观锁列
--
-- 问题（门禁 D2-1/D2-2/D2-3/D3-1/D3-2/D4-3 全红的根因）：PdrReviewService.toTask 的回写是
-- 「CAS + 乐观锁」一条语句：
--
--   UPDATE pdr_review_opinion
--      SET status = ?, task_id = ?, updated_by = ?, version = version + 1
--    WHERE tenant_id = ? AND id = ? AND task_id IS NULL
--
-- 但 V19__pdr_review_opinion.sql 建表时漏了 version 列（pdr_review 有，意见表没有），于是
-- MySQL 报 `Unknown column 'version' in 'field list'`，接口 500：
--   评审人点「转为任务」永远失败 → 意见既不 TASKED 也没有 task_id，任务表反而多出一行孤儿。
--
-- 选择补列而不是删掉代码里的 version：`task_id IS NULL` 只挡住「同一意见重复转任务」，
-- version 是留给意见状态 OPEN → TASKED → RESOLVED 后续并发回写的乐观锁；删掉写入等于把
-- 这一层保护也一起删了（意见表与 pdr_review 表按同一手法建模，不该一半有一半无）。
--
-- 幂等：MySQL 8/9 没有 `ADD COLUMN IF NOT EXISTS`（那是 MariaDB 语法），而 Flyway 一条失败
-- 的迁移会以 success = 0 留在 flyway_schema_history 里、让之后每次启动都卡在 validate，
-- 所以这里先查 information_schema 再决定是否执行 DDL（与 V8/V10 的索引守卫同手法）。
--
-- 类型 INT 与 pdr_review.version（V18）、report_definition.version（V10）一致。

SET @has_version := (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'pdr_review_opinion'
       AND COLUMN_NAME = 'version');

SET @ddl := IF(@has_version = 0,
    'ALTER TABLE `pdr_review_opinion` ADD COLUMN `version` INT NOT NULL DEFAULT 0 AFTER `task_id`',
    'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
