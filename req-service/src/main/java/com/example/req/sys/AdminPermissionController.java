package com.example.req.sys;

import java.util.ArrayList;
import java.util.List;

import com.example.platform.contract.PermissionMatrix;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The permission matrix as data (AC-5).
 *
 * <p>It is rendered from {@link PermissionMatrix} — the same class the interceptor enforces — so the
 * screen, the tests and the runtime decision cannot disagree. Nothing is stored in the database:
 * a configurable matrix would drift from the code that checks it.</p>
 */
@RestController
@RequestMapping("/admin/permissions")
public class AdminPermissionController {

    @GetMapping
    public SysViews.PermissionMatrixView matrix() {
        List<SysViews.RoleRow> roles = PermissionMatrix.roles().stream()
                .map(code -> new SysViews.RoleRow(code, RoleCatalog.roleName(code)))
                .toList();
        List<SysViews.PermissionFunction> functions = new ArrayList<>();
        for (String function : PermissionMatrix.functions()) {
            List<String> allow = new ArrayList<>();
            List<String> restricted = new ArrayList<>();
            List<String> deny = new ArrayList<>();
            for (String role : PermissionMatrix.roles()) {
                switch (PermissionMatrix.accessFor(role, function)) {
                    case ALLOW -> allow.add(role);
                    case RESTRICTED -> restricted.add(role);
                    case DENY -> deny.add(role);
                    default -> deny.add(role);
                }
            }
            functions.add(new SysViews.PermissionFunction(function, allow, restricted, deny));
        }
        return new SysViews.PermissionMatrixView(functions, roles);
    }
}
