-- V6__quality_status_width.sql
-- 质量域状态列宽度修正。
--
-- 背景：V5 把 test_plan.status 建成 VARCHAR(12)，而计划的七态里有 PENDING_REVIEW（14 字符）。
-- 提交评审时 INSERT/UPDATE 直接 Data truncation: Data too long for column 'status' (500)。
-- V5 已经在本机应用过（Flyway 有校验和），不能在原地改，只能追加一条迁移。
-- 这里把质量域所有状态列统一放宽到 VARCHAR(24)，避免后续新增状态（如 REPORT 的
-- SUPERSEDED / WAIVER 的 PENDING_APPROVAL）再次撞长度；MODIFY 可重复执行。

ALTER TABLE test_plan
    MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'DRAFT';

ALTER TABLE test_plan_round
    MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'PLANNING',
    MODIFY COLUMN conclusion VARCHAR(24) NULL;

ALTER TABLE test_strategy
    MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'DRAFT';

ALTER TABLE test_env
    MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'IDLE';

ALTER TABLE test_report
    MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'DRAFT';

ALTER TABLE test_report_version
    MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'DRAFT';

ALTER TABLE quality_gate_waiver
    MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'PENDING';

ALTER TABLE release_review
    MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'RECORDED';
