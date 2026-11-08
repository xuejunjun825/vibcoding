-- D-07：角色目录对齐 PRD §6.1 的 10 个角色。
--
-- 旧版种子自造了 TEAM_LEADER / VENDOR_ADMIN / VENDOR_DEV / VIEWER / AUDITOR 五个角色，
-- PRD 要求的是 业务部门 / 二级企业业务 / 二级企业负责人 / 产品 / 项目·域(PMO) / 研发 /
-- 测试 / 运维 / 管理层 / 平台管理员。厂商隔离改由账号的 vendor_id 表达，不再是独立角色。
--
-- 迁移只做三件事，且可重复执行（幂等）：
--   1. 为每个租户补齐缺失的新角色；
--   2. 把旧角色上的用户关联迁到语义等价的新角色（TEAM_LEADER/VENDOR_ADMIN→PMO、
--      VENDOR_DEV→DEV、VIEWER/AUDITOR→MGMT）；
--   3. 删掉旧的用户关联与旧角色本身。
-- 已存在的角色与关联不动；新库上租户表为空时这里是 no-op。

INSERT INTO sys_role (tenant_id, code, name, builtin)
SELECT t.id, r.code, r.name, 1
FROM sys_tenant t
         JOIN (SELECT 'BUSINESS' AS code, '业务部门' AS name
               UNION ALL SELECT 'SUB_BUSINESS', '二级企业业务'
               UNION ALL SELECT 'SUB_BUSINESS_LEADER', '二级企业负责人'
               UNION ALL SELECT 'PRODUCT', '产品经理'
               UNION ALL SELECT 'PMO', '项目·域(PMO)'
               UNION ALL SELECT 'DEV', '研发工程师'
               UNION ALL SELECT 'QA', '测试工程师'
               UNION ALL SELECT 'OPS', '运维工程师'
               UNION ALL SELECT 'MGMT', '管理层'
               UNION ALL SELECT 'PLATFORM_ADMIN', '平台管理员') r
WHERE NOT EXISTS (SELECT 1 FROM sys_role s WHERE s.tenant_id = t.id AND s.code = r.code);

INSERT IGNORE INTO sys_user_role (tenant_id, user_id, role_id)
SELECT ur.tenant_id, ur.user_id, newr.id
FROM sys_user_role ur
         JOIN sys_role oldr ON oldr.id = ur.role_id
         JOIN sys_role newr ON newr.tenant_id = oldr.tenant_id
    AND newr.code = CASE oldr.code
                        WHEN 'TEAM_LEADER' THEN 'PMO'
                        WHEN 'VENDOR_ADMIN' THEN 'PMO'
                        WHEN 'VENDOR_DEV' THEN 'DEV'
                        WHEN 'VIEWER' THEN 'MGMT'
                        WHEN 'AUDITOR' THEN 'MGMT'
        END
WHERE oldr.code IN ('TEAM_LEADER', 'VENDOR_ADMIN', 'VENDOR_DEV', 'VIEWER', 'AUDITOR');

DELETE ur
FROM sys_user_role ur
         JOIN sys_role r ON r.id = ur.role_id
WHERE r.code IN ('TEAM_LEADER', 'VENDOR_ADMIN', 'VENDOR_DEV', 'VIEWER', 'AUDITOR');

DELETE FROM sys_role
WHERE code IN ('TEAM_LEADER', 'VENDOR_ADMIN', 'VENDOR_DEV', 'VIEWER', 'AUDITOR');
