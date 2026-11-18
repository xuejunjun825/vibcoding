-- ---------------------------------------------------------------- r30：发版分级审批链 + 不可变链路快照
--
-- 本迁移只做三件事：给 release 补分级/驳回列、建 6 槽绑定表、建快照表。
-- **不改 V1–V13 任何一条既有语句**：老库升级只走新增路径，回滚式重建（drop 本文件建的表/列）
-- 也不会影响任何既有列的数据。
--
-- 为什么绑定单独一张表而不在 wf_definition 上加 level 列：
-- 定义是「一条链的 JSON 事实」，同一条链可以被多个槽共用（REQ_DEPLOY 三个等级共用
-- 「发布单审批-两级」），把等级写进定义行就会把「链的内容」与「哪个槽用它」绑死，
-- 想给紧急单换链只能复制一份 JSON。表里一行 = 一个槽的指向（tenant + biz_type + level 唯一），
-- **删行即恢复默认**（FlowBindingService.reset），默认值来自种子而不是这里。
--
-- 为什么 deploy_flow_snapshot **没有 updated_at**：
-- 快照是「提交那一刻的事实回执」，可变性从 schema 层就被否掉——没有更新列，仓库接口也
-- 只提供 save 与读（AC-11 的静态审计对象）。轮次连续由 uk_flow_snapshot_round 唯一键兜底：
-- 并发提交时第二个 capture 直接撞键失败，而不是悄悄覆盖第 N 轮。
--
-- 幂等：建表一律 CREATE TABLE IF NOT EXISTS；加列沿用 V12 的 information_schema 判重
-- （Flyway 每版本只跑一次，但本地库手工回滚后再次启动不会炸）。

-- ---------------------------------------------------------------- 1. release 分级与驳回列

-- release_level：三值（NORMAL / EMERGENCY / ROLLBACK）。用 VARCHAR(16) 而不是 ENUM，
-- 是为让 Java 侧 ReleaseLevel 成为唯一语义来源（枚举加值不需要再发一次 DDL）；
-- DEFAULT 'NORMAL' 让存量版本行升级后直接落常规三级链，无需回填脚本。
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `release` ADD COLUMN release_level VARCHAR(16) NOT NULL DEFAULT ''NORMAL'' AFTER gate_mode',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'release'
      AND COLUMN_NAME = 'release_level');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- emergency_reason：EMERGENCY 必填（1–500 字），NORMAL/ROLLBACK 忽略并落 NULL。
-- 长度 500 与 ReleaseLevelValidator 的校验上限同源，避免「校验通过但插库被截断」。
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `release` ADD COLUMN emergency_reason VARCHAR(500) NULL AFTER release_level',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'release'
      AND COLUMN_NAME = 'emergency_reason');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- previous_deploy_order_code：ROLLBACK 必填，指向同租户一张 APPROVED/SUCCESS 的发布单。
-- 存 code 而不是 deploy_order_id：发布单被 demo-reset 清掉后，历史版本的「回滚自哪张单」
-- 仍然可读（code 是单据的业务标识），不会因为外键变成悬空引用。
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `release` ADD COLUMN previous_deploy_order_code VARCHAR(24) NULL AFTER emergency_reason',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'release'
      AND COLUMN_NAME = 'previous_deploy_order_code');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 驳回五列：审批链某一级 REJECT 时由 ReleaseService.onApprovalRejected 回填，
-- 详情页据此展示「被谁在哪一级、因为什么驳回」。发起下一次审批时清空（不是覆盖）。
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `release` ADD COLUMN rejected_node_key VARCHAR(32) NULL',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'release'
      AND COLUMN_NAME = 'rejected_node_key');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `release` ADD COLUMN rejected_node_name VARCHAR(64) NULL',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'release'
      AND COLUMN_NAME = 'rejected_node_name');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `release` ADD COLUMN rejected_by BIGINT NULL',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'release'
      AND COLUMN_NAME = 'rejected_by');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `release` ADD COLUMN rejected_at DATETIME NULL',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'release'
      AND COLUMN_NAME = 'rejected_at');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- reject_reason：驳回理由原文（审批人 comment），上限 512 与 wf_history.comment 对齐。
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `release` ADD COLUMN reject_reason VARCHAR(512) NULL',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'release'
      AND COLUMN_NAME = 'reject_reason');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------- 2. wf_flow_binding 分级绑定槽

-- 6 行 = RELEASE×3 + REQ_DEPLOY×3（FlowBindingService.slots 恒定返回 6 条，未绑定即
-- definition_id 为 NULL 的「虚拟槽」，不落库）。definition_id 不加外键：wf_definition
-- 的删除由 WorkflowAdminService 自己管，绑定行在定义被删后应当解析失败而不是级联删掉
-- 槽位（否则用户会看到「绑定页少了一行」这种次级损坏）。
CREATE TABLE IF NOT EXISTS wf_flow_binding (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL,
    biz_type     VARCHAR(32)  NOT NULL,
    release_level VARCHAR(16) NOT NULL,
    definition_id BIGINT      NOT NULL,
    created_at   DATETIME     NULL,
    created_by   BIGINT       NULL,
    updated_at   DATETIME     NULL,
    updated_by   BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_flow_binding (tenant_id, biz_type, release_level),
    KEY idx_flow_binding_def (definition_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'r30 分级审批链绑定（一槽一行，删行即恢复默认）';

-- ---------------------------------------------------------------- 3. deploy_flow_snapshot 提交快照

-- 一行 = 一张发布单某一轮提交的链路快照。payload_json 存整个 flowChain（四段 + digest），
-- payload_hash 是规范化 JSON 的 sha256（段级 stale 由后端比对 digest，前端不自行判断）。
-- node_keys 存「当轮实际会走的节点链」（逗号分隔，取自绑定的定义），与 payload 分开列，
-- 是为了让「这轮走的是哪条链」能被 SQL 直接看到，不必解 JSON。
CREATE TABLE IF NOT EXISTS deploy_flow_snapshot (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT       NOT NULL,
    deploy_order_id    BIGINT       NOT NULL,
    release_id         BIGINT       NOT NULL,
    round_no           INT          NOT NULL,
    release_level      VARCHAR(16)  NOT NULL,
    definition_id      BIGINT       NOT NULL,
    definition_version INT          NOT NULL,
    node_keys          VARCHAR(512) NOT NULL DEFAULT '',
    payload_json       MEDIUMTEXT   NOT NULL,
    payload_hash       CHAR(64)     NOT NULL,
    captured_at        DATETIME     NOT NULL,
    created_by         BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_flow_snapshot_round (tenant_id, deploy_order_id, round_no),
    KEY idx_flow_snapshot_release (tenant_id, release_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'r30 发布单提交链路快照（只插不改，无 updated_at）';
