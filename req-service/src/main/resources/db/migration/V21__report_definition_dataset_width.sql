-- ---------------------------------------------------------------- r41 修复：报表数据集列宽
--
-- 问题（门禁 C3-2 红的根因）：分析域新增的数据集 `pdr_review_opinion`（评审意见交叉表）有
-- 18 个字符，而 report_definition.dataset 是 VARCHAR(16)（V10__workbench_reports.sql:29）。
-- INSERT 触发 MySQL 1406 (22001) Data truncation，被 ReportService.save 的
-- `catch (DataIntegrityViolationException)` 一律包装成 409 REPORT_NAME_DUPLICATED ——
-- 建报表的人看到「名称重复」，而真正的原因是字段超长，前端无从修复。
--
-- 修复分两半（缺一不可）：
--   1) 本迁移把 dataset 放宽到 VARCHAR(64)：现有 13 个数据集码最长 18，留出余量；
--   2) ReportService.save 只在唯一键冲突（DuplicateKeyException）时报重名，其余数据完整性
--      错误报 400 REPORT_SAVE_REJECTED 并带 reason，不再冒充重名。
--
-- 幂等：MODIFY COLUMN 把同一列改成同一类型是空操作，重复执行无副作用；不需要
-- information_schema 守卫（新表结构也不会因为列已存在而报错）。
--
-- 只放宽长度、不动 NULL/DEFAULT/字符集：唯一键 uk_report_name 建在 (tenant_id, owner_id,
-- name, active_flag) 上，与 dataset 无关；生成列 active_flag 也不受影响。

ALTER TABLE `report_definition`
    MODIFY COLUMN `dataset` VARCHAR(64) NOT NULL;
