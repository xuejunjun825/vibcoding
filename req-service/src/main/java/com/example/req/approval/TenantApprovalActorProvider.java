package com.example.req.approval;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 生产环境操作人来源：{@code TenantContext.require()}（platform-common 唯一入口）。
 *
 * <p>这是本包唯一 import {@code TenantContext}/{@code DataScope} 的文件——平台层的线程上下文形状
 * 变更只在这里改，引擎与控制器不受影响。
 */
@Component
public class TenantApprovalActorProvider implements ApprovalActorProvider {

    /** 可代他人减签/转办/催办的角色（PRD：流程定义仅 PMO/管理员可编辑）。 */
    static final List<String> ADMIN_ROLE_CODES = List.of("PLATFORM_ADMIN", "MGMT");

    private final ApproverDirectory directory;

    public TenantApprovalActorProvider(ApproverDirectory directory) {
        this.directory = directory;
    }

    @Override
    public ApprovalActor current() {
        DataScope scope = TenantContext.require();
        return new ApprovalActor(scope.tenantId(), scope.userId());
    }

    /**
     * 管理员判定。租户里还没有任何管理员账号时放行（bootstrap）——否则新租户的第一个流程
     * 永远发布不出去；T1 种子补齐管理员后该兜底自然失效。
     */
    @Override
    public boolean isAdmin(long tenantId, long userId) {
        if (directory.hasAnyRole(tenantId, userId, ADMIN_ROLE_CODES)) {
            return true;
        }
        for (String roleCode : ADMIN_ROLE_CODES) {
            if (!directory.activeUsersByRole(tenantId, roleCode).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
