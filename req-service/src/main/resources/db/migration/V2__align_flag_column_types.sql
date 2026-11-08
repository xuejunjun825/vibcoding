-- V2 — 对齐 JPA 实体与 DDL 的整数列类型。
--
-- 为什么需要这一版：V1 把若干 0/1 标志列与层级列定义成 TINYINT，但实体
-- （WorkflowVersion.isDefault / ApprovalInstance.activeFlag / TestModuleEntity.level /
-- TestCaseEntity.automated）声明为 Integer。Hibernate 7 的 `ddl-auto=validate`
-- 会逐列比较 JDBC 类型码（TINYINT vs INTEGER）并在启动时直接失败，这是
-- 2026-11-09 T11 自检实测到的启动阻断项（req-service 无法建 SessionFactory）。
--
-- 修法选择「迁移改列」而不是「改实体为 Byte/Boolean」：DDL 是唯一 schema 来源，
-- 迁移只增不改（V1 已应用，改 V1 会破 flyway checksum），实体语义（1/0 整数比较、
-- 与 ScopedJdbc 读出的 Number 对齐）保持不变，改动面最小。
--
-- 幂等性：Flyway 保证每个版本只执行一次；列类型变更对已有数据无损（0/1 → 0/1）。

ALTER TABLE wf_definition MODIFY COLUMN is_default INT NOT NULL DEFAULT 0;
ALTER TABLE wf_instance   MODIFY COLUMN active_flag INT NULL;
ALTER TABLE test_module   MODIFY COLUMN level INT NOT NULL DEFAULT 1;
ALTER TABLE test_case     MODIFY COLUMN automated INT NOT NULL DEFAULT 0;
