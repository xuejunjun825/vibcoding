-- V7__test_env_dictionary.sql
-- 测试环境字典对齐 PRD §4.6 / DESIGN §6.9：类型 DEV/TEST/STAGING/PROD，状态 AVAILABLE/MAINTENANCE/DISABLED。
--
-- 背景：V5 的 test_env 沿用了早期的 IDLE/IN_USE 与 SIT/PRE_PROD/PERF，前端却按 PRD 发送
-- AVAILABLE/MAINTENANCE/PROD，导致新建与维护环境一律 400。V5/V6 已在本机应用过（Flyway 有校验和），
-- 只能追加迁移：先把历史行归一到新字典，再改默认值。CASE 覆盖未知值，迁移可重复执行。

UPDATE test_env
SET env_type = CASE
    WHEN env_type IN ('STAGING', 'PRE_PROD') THEN 'STAGING'
    WHEN env_type = 'PROD' THEN 'PROD'
    WHEN env_type = 'DEV' THEN 'DEV'
    ELSE 'TEST'
END;

UPDATE test_env
SET status = CASE
    WHEN status = 'MAINTENANCE' THEN 'MAINTENANCE'
    WHEN status = 'DISABLED' THEN 'DISABLED'
    ELSE 'AVAILABLE'
END;

ALTER TABLE test_env
    MODIFY COLUMN env_type VARCHAR(16) NOT NULL DEFAULT 'TEST',
    MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE';
