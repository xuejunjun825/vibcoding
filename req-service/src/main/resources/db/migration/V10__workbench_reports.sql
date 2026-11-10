-- ---------------------------------------------------------------- 岗位工作台 / 自助报表（20261110-r9，T1）
--
-- 本迁移只建「管理者覆盖」与「报表定义」两组持久化，卡片模板/待办来源/快捷动作仍在
-- deliver-service 的 PortalTemplates 常量里（8 套模板 × 4 张卡放进库只会多一次迁移，
-- 而覆盖只有显隐一列）——见 TECHNICAL §2.1/§2.2。
--
-- 幂等：建表用 CREATE TABLE IF NOT EXISTS；加索引先查 information_schema.STATISTICS
-- 再 PREPARE/EXECUTE（与 V8 同手法，不用存储过程）。Flyway 只跑一次，但本地库
-- 反复 make platform-up/down 时这张表可能已存在，重复执行必须不报错。
--
-- 类型偏差（有意为之，勿按 TECHNICAL §2.1 的 TINYINT 回改）：active_flag 用 INT 而非
-- TINYINT。V1 建表用 TINYINT，V2__align_flag_column_types.sql:16 把 wf_instance 的
-- active_flag 改成 INT，因为 Hibernate 7 ddl-auto=validate 按列类型码逐列比对，MySQL
-- TINYINT 映射到 Byte 而非 Integer；而 ReportDefinitionRepository 的
-- existsByTenantIdAndOwnerIdAndNameAndActiveFlag(long,long,String,Integer) 与
-- ReportDefinitionEntity 的 Integer activeFlag 需要同一类型。生成列类型跟随表达式结果
-- （IF 返回 1/NULL ⇒ BIGINT 也可），显式写 INT 才能与实体对齐。

-- ---------------------------------------------------------------- 1. report_definition
--
-- 软删（status='TOMBSTONE'）后仍保留行，以便「已删除但我点错了」可诊断；因此「同名报表
-- 只能有一个」不能直接建在 (tenant_id, owner_id, name) 上——删一个再建同名会撞唯一键。
-- 生成列 active_flag 只在 ACTIVE 时为 1、其余为 NULL，唯一键因此只约束活动行
-- （MySQL 唯一索引不判 NULL 重复），与既有 wf_instance.uk_wf_instance_active 同手法。
CREATE TABLE IF NOT EXISTS report_definition (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    name            VARCHAR(60)     NOT NULL,
    dataset         VARCHAR(16)     NOT NULL,
    definition_json JSON            NOT NULL,
    chart_type      VARCHAR(8)      NOT NULL DEFAULT 'TABLE',
    owner_id        BIGINT          NOT NULL,
    owner_role      VARCHAR(32)     NULL,
    scope           VARCHAR(8)      NOT NULL DEFAULT 'PRIVATE',
    scope_ref       BIGINT          NULL,
    status          VARCHAR(8)      NOT NULL DEFAULT 'ACTIVE',
    active_flag     INT             GENERATED ALWAYS AS (IF(status = 'ACTIVE', 1, NULL)) STORED,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by      BIGINT          NULL,
    updated_by      BIGINT          NULL,
    version         INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_name (tenant_id, owner_id, name, active_flag),
    KEY idx_report_owner (tenant_id, owner_id, status, updated_at),
    KEY idx_report_scope (tenant_id, scope, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 2. portal_card_config
--
-- 管理员对 8 套模板的四张卡做显隐（只有显隐，不能新增卡）。user_id=0 是「角色级」哨兵：
-- 唯一键 (tenant_id, role_code, user_id) 若允许 NULL，MySQL 不判 NULL 重复，同角色会写出
-- 多行互相覆盖；NOT NULL DEFAULT 0 让「每个角色的配置恰好一行」。个人级覆盖（同一角色不同
-- 人不同卡）用真实 user_id，行数不再受角色数约束，所以不带唯一性以外的额外索引。
CREATE TABLE IF NOT EXISTS portal_card_config (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT          NOT NULL,
    role_code   VARCHAR(32)     NOT NULL,
    user_id     BIGINT          NOT NULL DEFAULT 0,
    card_keys   VARCHAR(200)    NOT NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_portal_card (tenant_id, role_code, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 3. requirement 时间索引
--
-- /analytics 的每一次查询都按 created_at 收口（看板趋势/周期分段/报表交叉表的行维度），
-- 而 requirement 现有索引都以 updated_at 或 vendor/release 打头，时间范围扫描只能回表全扫。
-- 复合顺序 tenant_id → created_at → status：前两列做范围裁剪，第三列让「区间内完成态计数」
-- 免回表取 status（覆盖索引）。
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE requirement ADD KEY idx_req_created (tenant_id, created_at, status)',
    'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'requirement' AND INDEX_NAME = 'idx_req_created');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 这两张表**不**登记 ScopeSql：报表定义与卡片配置按 tenant_id + owner_id/role_code 手工收口，
-- 登记进作用域表会让 ScopedJdbc 的 /*scope:*/ 标记把它们当成「有 domain_id/vendor_id」的业务表
-- （它们没有这两列），查询直接抛错。AC-13 与 report-verify r0 都断言 scopedTables() 不含二者。
