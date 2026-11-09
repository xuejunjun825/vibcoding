package com.example.req.integration.admission;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.integration.store.AdmissionItemConfigEntity;
import com.example.req.integration.store.AdmissionItemConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 准入项开关（TECHNICAL §7.5）：读 = 恒 9 条（未落库的行按默认开启渲染），写 = 逐项 upsert。
 *
 * <p>两条纪律只在这里实现一次：</p>
 * <ol>
 *   <li><b>锁定项拒绝关闭</b>：①②③⑥ 的 {@code enabled=false} 直接 409 {@code ITEM_LOCKED}，
 *       而不是"存下来但运行时忽略"——脏配置留在库里迟早会被别的读取方当真。</li>
 *   <li><b>只有开关能关项</b>：这里只回答"哪些 code 被显式关掉了"，判定用的四态由
 *       {@code AdmissionPolicy} 统一产出；锁定项即使库里有 {@code enabled=0} 的脏行，
 *       {@link #closedCodes(long)} 也不会把它算进去（代码锁死优先于数据）。</li>
 * </ol>
 */
@Service
public class AdmissionItemConfigService {

    private final AdmissionItemConfigRepository repository;
    private final AuditService auditService;

    public AdmissionItemConfigService(AdmissionItemConfigRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    /**
     * 当前租户被显式关闭的项（不含锁定项，也不含未知 code 的历史脏行）。
     *
     * <p>判定侧只读这个方法：CLOSED 的来源因此只有"开关关闭"一处，不会出现两套关闭语义。</p>
     */
    @Transactional(readOnly = true)
    public Set<String> closedCodes(long tenantId) {
        Set<String> closed = new LinkedHashSet<>();
        for (AdmissionItemConfigEntity entity : repository.findByTenantIdAndEnabled(tenantId, 0)) {
            String code = entity.getItemCode();
            if (AdmissionItemPolicy.known(code) && !AdmissionItemPolicy.locked(code)) {
                closed.add(code);
            }
        }
        return closed;
    }

    /** 管理端清单：恒 9 条，按固定顺序，未落库的行按"开启"返回。 */
    @Transactional(readOnly = true)
    public List<AdmissionViews.ItemView> list() {
        long tenantId = TenantContext.require().tenantId();
        return views(tenantId);
    }

    /**
     * 逐项开关。整批先校验再落库：一项非法则整批不生效，避免"改了一半"的中间态。
     */
    @Transactional
    public List<AdmissionViews.ItemView> update(AdmissionViews.ToggleCmd cmd) {
        var scope = TenantContext.require();
        long tenantId = scope.tenantId();
        List<AdmissionViews.Toggle> toggles = cmd == null || cmd.items() == null ? List.of() : cmd.items();
        if (toggles.isEmpty()) {
            throw new ApiException(400, "VALIDATION_FAILED", "items 不能为空：至少给出一个 {code, enabled}",
                    Map.of("field", "items"));
        }
        Map<String, Boolean> requested = new LinkedHashMap<>();
        for (AdmissionViews.Toggle toggle : toggles) {
            String code = toggle == null ? null : toggle.code();
            if (!AdmissionItemPolicy.known(code)) {
                throw new ApiException(400, "VALIDATION_FAILED", "未知的准入项代码：" + code,
                        Map.of("field", "code", "value", code == null ? "" : code,
                                "allowed", AdmissionItemPolicy.order()));
            }
            if (toggle.enabled() == null) {
                throw new ApiException(400, "VALIDATION_FAILED", "准入项 " + code + " 缺少 enabled",
                        Map.of("field", "enabled", "code", code));
            }
            if (!toggle.enabled() && AdmissionItemPolicy.locked(code)) {
                throw new ApiException(409, "ITEM_LOCKED",
                        "准入项「" + AdmissionItemPolicy.nameOf(code) + "」由系统锁定，不能关闭",
                        Map.of("itemCode", code, "locked", true));
            }
            requested.put(code, toggle.enabled());
        }

        Map<String, AdmissionItemConfigEntity> existing = new HashMap<>();
        for (AdmissionItemConfigEntity entity : repository.findByTenantIdOrderByItemCodeAsc(tenantId)) {
            existing.put(entity.getItemCode(), entity);
        }
        List<Map<String, Object>> changed = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, Boolean> entry : requested.entrySet()) {
            AdmissionItemConfigEntity entity = existing.get(entry.getKey());
            if (entity == null) {
                entity = new AdmissionItemConfigEntity();
                entity.setTenantId(tenantId);
                entity.setItemCode(entry.getKey());
            }
            int enabled = Boolean.TRUE.equals(entry.getValue()) ? 1 : 0;
            if (entity.getEnabled() == null || entity.getEnabled() != enabled) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("code", entry.getKey());
                row.put("enabled", enabled == 1);
                changed.add(row);
            }
            entity.setEnabled(enabled);
            entity.setUpdatedBy(scope.userId());
            entity.setUpdatedAt(now);
            repository.save(entity);
        }
        auditService.record("ADMISSION_ITEM_TOGGLE", "ADMISSION_ITEM", null, Map.of("changed", changed));
        return views(tenantId);
    }

    private List<AdmissionViews.ItemView> views(long tenantId) {
        Map<String, AdmissionItemConfigEntity> rows = new HashMap<>();
        for (AdmissionItemConfigEntity entity : repository.findByTenantIdOrderByItemCodeAsc(tenantId)) {
            rows.put(entity.getItemCode(), entity);
        }
        List<AdmissionViews.ItemView> views = new ArrayList<>(AdmissionItemPolicy.order().size());
        for (String code : AdmissionItemPolicy.order()) {
            AdmissionItemConfigEntity row = rows.get(code);
            boolean enabled = row == null || row.getEnabled() == null || row.getEnabled() == 1;
            boolean locked = AdmissionItemPolicy.locked(code);
            views.add(new AdmissionViews.ItemView(code, AdmissionItemPolicy.nameOf(code),
                    AdmissionItemPolicy.orderOf(code), locked, locked || enabled,
                    AdmissionItemPolicy.descriptionOf(code)));
        }
        return views;
    }
}
