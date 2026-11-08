package com.example.req.sys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.platform.common.error.ApiException;
import org.junit.jupiter.api.Test;

/** Provisioning validates its input before it writes anything: a bad code must not leave a tenant. */
class TenantProvisionerTest {

    private final TenantProvisioner provisioner = new TenantProvisioner(null, new PasswordService());

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
}
