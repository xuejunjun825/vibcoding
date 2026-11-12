package com.example.req.sys;

import java.util.List;

/**
 * What a freshly created tenant gets, in one place.
 *
 * <p>The role codes are exactly the rows of {@code PermissionMatrix} — a tenant that were
 * provisioned with different codes would authenticate fine and then be denied everything,
 * which is why the list is derived from the contract rather than typed again here.</p>
 */
public final class RoleCatalog {

    public record RoleDef(String code, String name) {
    }

    public record DomainDef(String code, String name) {
    }

    public record DictDef(String type, String code, String label, int sort) {
    }

    public static final List<RoleDef> ROLES = List.of(
            new RoleDef("BUSINESS", "业务部门"),
            new RoleDef("SUB_BUSINESS", "二级企业业务"),
            new RoleDef("SUB_BUSINESS_LEADER", "二级企业负责人"),
            new RoleDef("PRODUCT", "产品经理"),
            new RoleDef("PMO", "项目·域(PMO)"),
            new RoleDef("DEV", "研发工程师"),
            new RoleDef("QA", "测试工程师"),
            new RoleDef("OPS", "运维工程师"),
            new RoleDef("MGMT", "管理层"),
            new RoleDef("PLATFORM_ADMIN", "平台管理员"));

    public static final List<DomainDef> DOMAINS = List.of(
            new DomainDef("PUR", "采购域"),
            new DomainDef("INV", "库存域"),
            new DomainDef("ORD", "订单域"),
            new DomainDef("LOG", "物流域"),
            new DomainDef("SET", "结算域"),
            new DomainDef("SUP", "供应商域"),
            new DomainDef("MDM", "主数据域"));

    public static final List<DictDef> DICTS = List.of(
            new DictDef("priority", "P0", "紧急", 0),
            new DictDef("priority", "P1", "高", 1),
            new DictDef("priority", "P2", "中", 2),
            new DictDef("priority", "P3", "低", 3),
            new DictDef("severity", "P0", "致命", 0),
            new DictDef("severity", "P1", "严重", 1),
            new DictDef("severity", "P2", "一般", 2),
            new DictDef("severity", "P3", "轻微", 3),
            new DictDef("req_type", "BUS", "业务需求", 0),
            new DictDef("req_type", "PDR", "产品需求", 1),
            new DictDef("req_type", "RD", "研发需求", 2),
            new DictDef("req_status", "POOLED", "已入池", 0),
            new DictDef("req_status", "IN_DEV", "开发中", 1),
            new DictDef("req_status", "PENDING_TEST", "待测试", 2),
            new DictDef("req_status", "TEST_PASSED", "测试通过", 3),
            new DictDef("req_status", "DELIVERED", "已交付", 4),
            new DictDef("case_priority", "P0", "冒烟", 0),
            new DictDef("case_priority", "P1", "核心", 1),
            new DictDef("case_priority", "P2", "常规", 2),
            new DictDef("case_priority", "P3", "边缘", 3));

    public static final List<String> DEFAULT_DOMAIN_CODES = DOMAINS.stream()
            .map(DomainDef::code).toList();

    private RoleCatalog() {
    }

    public static String roleName(String code) {
        return ROLES.stream().filter(role -> role.code().equals(code))
                .map(RoleDef::name).findFirst().orElse(code);
    }
}
