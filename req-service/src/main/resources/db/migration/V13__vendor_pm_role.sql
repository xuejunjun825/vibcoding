-- ---------------------------------------------------------------- r24：第 11 个内置角色「厂商项目经理」
--
-- 本迁移**只建角色行，不写授权行**。VENDOR_PM 的 168 格（60 FUNCTION + 36 MENU + 72 ACTION）
-- 是「角色 × 功能码」网格与注册表的函数，真相在 Java 侧（PermissionMatrix / MenuRegistry /
-- ActionRegistry + RbacSeeder.ACTION_DENY_OVERRIDES）。若在 SQL 里内联一份 168 行，就等于
-- 制造第二份预设：动作覆盖只写在 RbacSeeder，SQL 版本必然先漂移，而漂移的表现是「升级后
-- 某个格子的档位与预设不一致」，只在重置后的行数上才看得出来。
--
-- 补齐路径：本迁移保证每个租户都有 VENDOR_PM 这一行，同一次启动的 RbacBootstrap
-- （ApplicationRunner）遍历全部租户调 RbacSeeder.seedTenant，把缺失的 168 行 INSERT IGNORE 补上。
-- 因此**存量租户升级后立即是 11 角色 / 1848 行**，不需要等下一次 seed 或手工跑脚本。
--
-- 幂等：与 V3__role_catalog_prd.sql 同手法（NOT EXISTS 按 tenant_id + code 判重）。
-- Flyway 每个版本只执行一次，但本地库被手工回滚后再次启动不会炸。
--
-- 为什么 builtin = 1：内置角色不可删、不可停（RoleAdminService 的 BUILTIN_ROLE_IMMUTABLE），
-- 也是 demo-reset 的保留集合——重置只清 builtin = 0 的自定义角色，再按 preset 重写全部授权行。

INSERT INTO sys_role (tenant_id, code, name, builtin)
SELECT t.id, 'VENDOR_PM', '厂商项目经理', 1
FROM sys_tenant t
WHERE NOT EXISTS (SELECT 1 FROM sys_role s
                  WHERE s.tenant_id = t.id AND s.code = 'VENDOR_PM');
