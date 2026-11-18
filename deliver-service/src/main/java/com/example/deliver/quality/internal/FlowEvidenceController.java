package com.example.deliver.quality.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * r30 / AC-8：{@code req-service} 读取版本发版链路前三段的<b>唯一</b>内部端点。
 *
 * <p>为什么是一条批量路由而不是"计划/报告/门"三条：版本详情是首屏，四段链路每次都要全拿；
 * 三次往返意味着三个超时、三种失败语义，前端还要自己拼"缺哪段"。一次往返把可用性收成一个开关：
 * 取到 → 三段事实；取不到 → 客户端把三段都标 MISSING，详情页仍 200。</p>
 *
 * <p>租户从 {@code X-Tenant-Id} 解析（缺省回落到 release 行），建 {@link TenantContext} 后
 * 在 {@code finally} 清理：内部请求没有 JWT，绝不能从 body 里取租户，否则可跨租户读门。</p>
 */
@RestController
public class FlowEvidenceController {

    private final FlowEvidenceService evidence;
    private final InternalTenantResolver tenantResolver;

    public FlowEvidenceController(FlowEvidenceService evidence, InternalTenantResolver tenantResolver) {
        this.evidence = evidence;
        this.tenantResolver = tenantResolver;
    }

    /**
     * @param requirementIds 逗号分隔的需求 id（可缺省）；只用于把计划按覆盖关系求交
     */
    @GetMapping("/internal/releases/{releaseId}/flow-evidence")
    public Map<String, Object> flowEvidence(@PathVariable long releaseId,
                                            @RequestParam(value = "requirementIds", required = false)
                                            String requirementIds,
                                            @RequestHeader(value = "X-Tenant-Id", required = false)
                                            String tenantHeader) {
        long tenantId = tenantResolver.of(tenantHeader, releaseId);
        TenantContext.set(DataScope.system(tenantId));
        try {
            return evidence.evidence(releaseId, parseIds(requirementIds));
        } finally {
            TenantContext.clear();
        }
    }

    private static List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(trimmed);
                if (id > 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // 非数字段直接跳过：这是筛选条件，不是身份凭据，不值得为它 400 掉整次读。
            }
        }
        return ids;
    }
}
