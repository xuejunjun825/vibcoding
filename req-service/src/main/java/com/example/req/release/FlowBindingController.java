package com.example.req.release;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分级审批链绑定页的后端入口（r30 / AC-6）。
 *
 * <p>控制器只做参数搬运：租户取自 {@link TenantContext}，管理员判定在
 * {@link FlowBindingService#save}/{@link FlowBindingService#reset} 内（与「维护流程定义」同一口径，
 * 不新增权限码）。三个路由都返回<b>整条槽</b>，前端保存后直接替换那一条卡片，
 * 不必重新拉 6 条再找自己——槽的结构（定义名/版本/节点链/是否默认）由服务端一次算全。</p>
 *
 * <p>恢复默认是 {@code DELETE}：它不是「删掉一个配置对象」，而是「让这个槽回到默认链」，
 * 所以返回 200 + 槽（未绑定态），不是 204。</p>
 */
@RestController
@RequestMapping("/admin/flow-bindings")
public class FlowBindingController {

    private final FlowBindingService bindings;

    public FlowBindingController(FlowBindingService bindings) {
        this.bindings = bindings;
    }

    @GetMapping
    public List<FlowChainViews.BindingSlot> list() {
        return bindings.slots(TenantContext.require().tenantId());
    }

    @PutMapping("/{bizType}/{level}")
    public FlowChainViews.BindingSlot save(@PathVariable String bizType,
                                           @PathVariable String level,
                                           @RequestBody SaveCmd request) {
        DataScope scope = TenantContext.require();
        if (request == null || request.definitionId() == null) {
            throw new com.example.req.approval.ApprovalException(400, "BINDING_INVALID",
                    "definitionId 必填", java.util.Map.of("field", "definitionId"));
        }
        return bindings.save(scope.tenantId(), bizType, level, request.definitionId());
    }

    @DeleteMapping("/{bizType}/{level}")
    public ResponseEntity<FlowChainViews.BindingSlot> reset(@PathVariable String bizType,
                                                            @PathVariable String level) {
        DataScope scope = TenantContext.require();
        return ResponseEntity.ok(bindings.reset(scope.tenantId(), bizType, level));
    }

    public record SaveCmd(Long definitionId) {
    }
}
