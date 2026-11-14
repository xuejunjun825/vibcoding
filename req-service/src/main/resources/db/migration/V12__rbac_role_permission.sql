-- ---------------------------------------------------------------- 三层授权数据面（20261113-r19，T1）
--
-- 背景：r18 把「谁能做什么」的判定与路由骨架落进了代码（PermissionService 三序判定、
-- PermissionRouteTable 161 条写映射、角色管理接口、前端 pageRegistry），但数据面还缺
-- 三张表：页面注册表快照、动作注册表快照、租户级授权行。没有它们，判定只能退化成
-- 「内存里的 PermissionMatrix」，管理员改不了档位，新页面也无处登记。
--
-- 本迁移**只做 DDL，不插数据**。1680 行/租户不是常量文本，而是注册表与矩阵的函数：
--   FUNCTION = PermissionMatrix.accessFor(role, function)                 10 × 60 = 600
--   MENU     = max(页面绑定的功能码)，空集合 ⇒ ALLOW                       10 × 36 = 360
--   ACTION   = max(动作依赖的功能码)，空集合 ⇒ ALLOW                       10 × 72 = 720
-- 写成 INSERT 会让「新增一个页面」必须先改历史迁移，而 Flyway 的 checksum 不允许；
-- 所以行数据由 RbacSeeder 派生，三个写入口（启动 RbacBootstrap / 建租户 TenantProvisioner /
-- 演示重置 DemoResetService）共用同一个纯函数。s0 的失败提示也指向这两个类。
--
-- 为什么 sys_menu / sys_action 不带 tenant_id：它们是产品常量（代码里 MenuRegistry /
-- ActionRegistry 就是唯一真相），复制到每个租户只会漂移出 N 份互不一致的快照；
-- 只有 sys_role_permission 是租户级数据。
--
-- 为什么 sys_role_permission 不加自增 id：主键职责交给 uk_rp 四列。
--   (a) 授权行没有业务外的身份，没人按 id 引用它；
--   (b) 唯一键本身就要求这四列唯一，再加 id 等于多养一个索引；
--   (c) s0 逐列断言 7 列，加 id 会立刻变红。
-- InnoDB 会用 uk_rp 做聚簇索引，按 (tenant_id, role_id) 前缀的范围读也能走它。
--
-- 幂等：Flyway 每个版本只执行一次；但本迁移仍按「可重放」写——加列前查
-- information_schema.COLUMNS，再 SET @ddl + PREPARE/EXECUTE（与 V5/V8/V10 同手法，
-- 不用存储过程），建表一律 CREATE TABLE IF NOT EXISTS。本地库被手工回滚后再次启动不会炸。

-- ---------------------------------------------------------------- 1. sys_role 增列
--
-- description：角色列表页的「说明」列，新建/编辑角色时可填。
-- status：停用即时失权的开关。PermissionGrantsRepository 的取数 SQL 里 JOIN 了
--         r.status = 'ACTIVE'，所以停用一个角色不需要删它的授权行——把开关拨回去，
--         档位原样恢复（这也是 RoleAdminService.setStatus 只 invalidate 不写授权的原因）。
-- 内置角色建租户时按默认值插入，历史行由 DEFAULT 'ACTIVE' 补齐，不产生 UPDATE。

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE sys_role ADD COLUMN description VARCHAR(200) NULL AFTER name',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role'
      AND COLUMN_NAME = 'description');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE sys_role ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''ACTIVE'' AFTER builtin',
    'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role'
      AND COLUMN_NAME = 'status');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------- 2. sys_menu 页面注册表快照
--
-- 一行 = 一个可授权页面，主键就是 MenuRegistry.MenuPage.code（<module>.<page>）。
-- function_codes 是这个页面在旧判据下绑定的功能码（逗号分隔，空串 = 不绑码）：
-- MENU 层种子 = 这些码上的 FUNCTION 行取 max，空集合 ⇒ ALLOW。
-- 存成字符串而不是子表，是为了让 s2 能用一段递归序列在 SQL 里展开比对，
-- 也避免为 36 行页面再养一张关联表。

CREATE TABLE IF NOT EXISTS sys_menu (
    code           VARCHAR(64)  NOT NULL,
    module         VARCHAR(32)  NOT NULL,
    name           VARCHAR(64)  NOT NULL,
    route_name     VARCHAR(64)  NOT NULL,
    function_codes VARCHAR(512) NOT NULL DEFAULT '',
    sort_no        INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '页面注册表快照（MenuRegistry 36 行）';

-- ---------------------------------------------------------------- 3. sys_action 动作注册表快照
--
-- 一行 = 一个可授权动作，menu_code 指向所属页面（三序判定的第一依据：页面 DENY 时
-- 其下动作即使授了也不生效）。high_risk = 1 的动作在授权页需要二次确认（AC-12），
-- 取值只由 ActionRegistry.HIGH_RISK 推导——库里多一个 1 会让普通授权无故被拦。
-- idx_action_menu 让「某页面的动作表」与 s1 的归属校验免全表扫。

CREATE TABLE IF NOT EXISTS sys_action (
    code           VARCHAR(64)  NOT NULL,
    menu_code      VARCHAR(64)  NOT NULL,
    name           VARCHAR(64)  NOT NULL,
    function_codes VARCHAR(512) NOT NULL DEFAULT '',
    high_risk      TINYINT      NOT NULL DEFAULT 0,
    sort_no        INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (code),
    KEY idx_action_menu (menu_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '动作注册表快照（ActionRegistry 72 行）';

-- ---------------------------------------------------------------- 4. sys_role_permission 租户级授权
--
-- 三层共用一张表（subject_type ∈ FUNCTION|MENU|ACTION）：三层的读写路径完全一样
-- （按角色整体覆盖、按租户整体加载），拆成三张表只会把「一次保存」变成三段事务。
-- access ∈ ALLOW|RESTRICTED|DENY，**缺行等价 DENY 但不允许靠缺行表达 DENY**：
-- s2 的规范化哈希与 s3 的并集判定都要求 DENY 也是一行，否则「管理员显式拒绝」
-- 与「这版软件还没见过这个码」无法区分。
-- uk_rp 是 INSERT IGNORE 幂等的前提（AC-4）：第二次种子/重置不会写出重复行，
-- 也不会覆盖管理员改过的档位。

CREATE TABLE IF NOT EXISTS sys_role_permission (
    tenant_id    BIGINT      NOT NULL,
    role_id      BIGINT      NOT NULL,
    subject_type VARCHAR(16) NOT NULL COMMENT 'FUNCTION|MENU|ACTION',
    subject_code VARCHAR(64) NOT NULL,
    access       VARCHAR(16) NOT NULL COMMENT 'ALLOW|RESTRICTED|DENY',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rp (tenant_id, role_id, subject_type, subject_code),
    KEY idx_rp_role (tenant_id, role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '三层授权：行存在即已配置，DENY 也是行';
