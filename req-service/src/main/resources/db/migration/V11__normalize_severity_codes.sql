-- ---------------------------------------------------------------- 缺陷等级 S1..S4 → P0..P3（20261112-r14，D4）
--
-- 背景：RoleCatalog 早期把 severity 写成 S1..S4，后来全仓统一到 defect.severity 的 P0..P3，
-- 但两处存量没跟上——(a) demo 夹具 DemoFixture 仍写 S2/S3（已同步改 P1/P2），
-- (b) 旧租户初始化时种下的 sys_dict 仍是 S1..S4，而新租户已是 P0..P3。
-- 结果是质量域按 severity 分组时，S2/S3 落到注册表外，页面显示「未知状态」。
--
-- 映射按语义而非序号硬换：S1=致命→P0、S2=严重→P1、S3=一般→P2、S4=轻微→P3。
-- 幂等：先归一 defect 值，再删掉旧的 S 字典行、只为缺 P 行的租户补齐（NOT EXISTS 防撞
-- uk_dict）；重复执行不会产生第二份 P 行，也不会改动已正确的租户。
-- 注意：Flyway 只跑一次，但本迁移按「可重放」写，便于本地库被手工回滚后再次启动。

-- 1) 存量缺陷：S1..S4 → P0..P3
UPDATE defect
SET severity = CASE severity
                   WHEN 'S1' THEN 'P0'
                   WHEN 'S2' THEN 'P1'
                   WHEN 'S3' THEN 'P2'
                   WHEN 'S4' THEN 'P3'
                   ELSE severity
    END
WHERE severity IN ('S1', 'S2', 'S3', 'S4');

-- 2) 旧字典行：删除 S1..S4（label 语义已由下面的 P 行承接）
DELETE
FROM sys_dict
WHERE type = 'severity'
  AND code IN ('S1', 'S2', 'S3', 'S4');

-- 3) 为每个租户补齐缺失的 P 行；已有 P0..P3 的租户不动
INSERT INTO sys_dict (tenant_id, type, code, label, sort_no, created_by, updated_by)
SELECT t.id, 'severity', x.code, x.label, x.sort_no, 0, 0
FROM sys_tenant t
         CROSS JOIN (SELECT 'P0' AS code, '致命' AS label, 0 AS sort_no
                     UNION ALL
                     SELECT 'P1', '严重', 1
                     UNION ALL
                     SELECT 'P2', '一般', 2
                     UNION ALL
                     SELECT 'P3', '轻微', 3) x
WHERE NOT EXISTS (SELECT 1
                  FROM sys_dict d
                  WHERE d.tenant_id = t.id
                    AND d.type = 'severity'
                    AND d.code = x.code);
