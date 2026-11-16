package com.example.req.sys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.example.platform.common.error.ApiException;
import com.example.req.seed.RbacSeeder;
import org.junit.jupiter.api.Test;

/** Provisioning validates its input before it writes anything: a bad code must not leave a tenant. */
class TenantProvisionerTest {

    private final TenantProvisioner provisioner =
            new TenantProvisioner(null, new PasswordService(), new RbacSeeder(null));

    @Test
    void rejectsInvalidCode() {
        ApiException ex = assertThrows(ApiException.class,
                () -> provisioner.provision("Bad Code!", "坏租户", "admin", "Admin@12345"));
        assertEquals(400, ex.status());
        assertEquals("INVALID_TENANT_CODE", ex.code());
    }

    @Test
    void rejectsTooShortCode() {
        ApiException ex = assertThrows(ApiException.class,
                () -> provisioner.provision("x", "坏租户", "admin", "Admin@12345"));
        assertEquals(400, ex.status());
    }

    /**
     * r24: 新租户预置的角色数来自 {@link RoleCatalog#ROLES}，所以「第 11 个内置角色」这件事在
     * 建租户路径上就是这一处的长度——断言它，等于同时守住「新租户建出来就有 VENDOR_PM」。
     */
    @Test
    void catalogCarriesElevenBuiltinRoles() {
        assertEquals(11, RoleCatalog.ROLES.size(), "新租户预置 11 个内置角色");
        assertTrue(RoleCatalog.ROLES.stream()
                .anyMatch(role -> role.code().equals(RoleCatalog.VENDOR_PM)), "VENDOR_PM 必须在目录里");
        assertEquals("厂商项目经理", RoleCatalog.roleName(RoleCatalog.VENDOR_PM));
        assertEquals(RoleCatalog.VENDOR_PM, RoleCatalog.ROLES.get(10).code(),
                "追加在末尾：既有 10 个角色的顺序与名称不变，升级不改既有行");
    }

    /** 「哪个角色必须绑厂商」只认角色码，且只认 VENDOR_PM 一个。 */
    @Test
    void requiresVendorOnlyForTheVendorPmRole() {
        assertTrue(RoleCatalog.requiresVendor(List.of(RoleCatalog.VENDOR_PM)));
        assertTrue(RoleCatalog.requiresVendor(List.of("DEV", RoleCatalog.VENDOR_PM)),
                "多角色取并集：只要含 VENDOR_PM 就必须绑厂商");
        assertFalse(RoleCatalog.requiresVendor(List.of("PMO", "DEV")));
        assertFalse(RoleCatalog.requiresVendor(List.of()), "空角色集不得要求厂商");
        assertFalse(RoleCatalog.requiresVendor(null), "未传角色不得 NPE，也不得默认要求厂商");
    }
}
