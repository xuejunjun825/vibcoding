package com.example.req.requirement;

import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提报入口的业务域校验：必填 + 存在性 + 越权。
 *
 * <p>只挂在 {@code POST /requirements}（用户自选业务域的唯一写入口）。拆解复用
 * {@code RequirementService.create}，其空域语义是「继承父域」，因此必填不能放进 create，
 * 否则会把拆解一并拦死。将来新增直建入口时必须复用 {@link #requireForCreate}。</p>
 *
 * <p>存在性查询走 {@link RequirementService#knownDomainIds}：L9（读路径单点）要求所有数据库
 * 读经 ScopedJdbc，直连 JDBC 只在该白名单里的类放行；而这里的读必须按 tenant 全量、不能按
 * 调用者的域过滤（否则越权域会被误报成「不存在」400）。</p>
 */
@Component
public class RequirementDomainValidator {

    private final RequirementService requirementService;

    public RequirementDomainValidator(RequirementService requirementService) {
        this.requirementService = requirementService;
    }

    /** 提报入口：400（缺失/非法/不存在）与 403（越权域）；成功返回去重保序的 domainIds。 */
    public List<Long> requireForCreate(DataScope scope, List<Long> raw) {
        RequirementRules.Violation violation = RequirementRules.checkDomainIds(raw);
        if (violation != null) {
            throw RequirementErrors.rule(violation);
        }
        List<Long> ids = RequirementRules.distinctDomainIds(raw);
        List<Long> unseen = unseen(ids, requirementService.knownDomainIds(scope.tenantId(), ids));
        if (!unseen.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("field", "domainIds");
            detail.put("invalidIds", unseen);
            throw RequirementErrors.validation("业务域不存在或不属于当前租户", detail);
        }
        List<Long> outOfScope = outOfScope(ids, scope);
        if (!outOfScope.isEmpty()) {
            throw RequirementErrors.domainOutOfScope(outOfScope);
        }
        return ids;
    }

    /** 纯函数：raw 中不在 known 里的 id（AC-10 #6 invalidIds）。 */
    public static List<Long> unseen(List<Long> raw, Collection<Long> known) {
        List<Long> missing = new ArrayList<>();
        if (raw == null) {
            return missing;
        }
        Collection<Long> existing = known == null ? List.of() : known;
        for (Long id : raw) {
            if (!existing.contains(id)) {
                missing.add(id);
            }
        }
        return missing;
    }

    /**
     * 纯函数：越权 id。TENANT 档或 domainIds 空集 => 不限制。
     *
     * <p>这里刻意不调 {@code DataScope.canSeeDomain}（其实现是空集=不可见），而是按
     * DataScope 的注释契约「空集表示该维度不限制」判定；本轮不动 platform-contract。</p>
     */
    public static List<Long> outOfScope(List<Long> raw, DataScope scope) {
        List<Long> denied = new ArrayList<>();
        if (scope == null || raw == null || scope.tier() == ScopeTier.TENANT
                || scope.domainIds() == null || scope.domainIds().isEmpty()) {
            return denied;
        }
        for (Long id : raw) {
            if (!scope.domainIds().contains(id)) {
                denied.add(id);
            }
        }
        return denied;
    }
}
