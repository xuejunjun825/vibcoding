package com.example.req.release;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.platform.common.audit.AuditService;
import com.example.req.approval.ApprovalActor;
import com.example.req.approval.ApprovalActorProvider;
import com.example.req.approval.ApprovalException;
import com.example.req.approval.WorkflowDefinition;
import com.example.req.approval.WorkflowJson;
import com.example.req.approval.WorkflowVersion;
import com.example.req.approval.WorkflowVersionRepository;
import com.example.req.seed.FlowChainSeedService;

/**
 * 分级审批链的 6 个绑定槽：读（{@link #slots}）、改（{@link #save}）、恢复默认（{@link #reset}）、
 * 解析生效定义（{@link #resolveDefinitionId}）（r30 / AC-1 / AC-4 / AC-6）。
 *
 * <p>本类只负责「哪个槽现在指向哪条链」。把 definitionId 交给引擎是调用方的事
 * （{@code ReleaseService.startApproval} / {@code DeployOrderService.startApproval} 传进
 * 既有的 {@code StartApproval(..., definitionId, ...)} 口子），这样引擎的执行语义一行都不用改。</p>
 *
 * <p><b>解析顺序是 AC-4 老库兼容的唯一开关</b>：
 * ① 槽位绑定行 → ② 该 bizType 的默认定义名（{@link FlowChainSeedService#DEFAULTS}，须 PUBLISHED）
 * → ③ 该 bizType 下任一 PUBLISHED（{@code is_default DESC, version DESC}）→ ④ 全无则 409。
 * 老库升级后即使没跑种子、绑定表为空，也能靠 ③ 解析到旧的单节点定义继续发版；
 * 反过来，种子跑过之后 ② 就把新分级链接管，无需删老定义。</p>
 */
@Service
public class FlowBindingService {

    private final FlowBindingRepository repository;
    private final WorkflowVersionRepository definitions;
    private final WorkflowJson json;
    private final ApprovalActorProvider actors;
    private final AuditService audit;

    public FlowBindingService(FlowBindingRepository repository,
                              WorkflowVersionRepository definitions,
                              WorkflowJson json,
                              ApprovalActorProvider actors,
                              AuditService audit) {
        this.repository = repository;
        this.definitions = definitions;
        this.json = json;
        this.actors = actors;
        this.audit = audit;
    }

    /** 恒 6 条：RELEASE×3 + REQ_DEPLOY×3，未绑定槽的 definitionId=null（前端显示「按默认」）。 */
    @Transactional(readOnly = true)
    public List<FlowChainViews.BindingSlot> slots(long tenantId) {
        // 路由表已把 /admin/flow-bindings 收进 workflow:manage（AC-6），这里再守一道：
        // 绑定决定全租户的审批链，读也不该对非管理员开放；两层任一生效即可（规则漂移也不会越权）。
        requireAdmin(tenantId);
        Map<String, FlowBindingEntity> bound = new HashMap<>();
        for (FlowBindingEntity row : repository.findByTenantIdOrderByBizTypeAscReleaseLevelAsc(tenantId)) {
            bound.put(key(row.getBizType(), row.getReleaseLevel()), row);
        }
        List<WorkflowVersion> allRelease = definitions.findByTenantIdAndBizTypeOrderByVersionDesc(
                tenantId, FlowChainSeedService.BIZ_RELEASE);
        List<WorkflowVersion> allDeploy = definitions.findByTenantIdAndBizTypeOrderByVersionDesc(
                tenantId, FlowChainSeedService.BIZ_REQ_DEPLOY);
        List<FlowChainViews.BindingSlot> slots = new ArrayList<>(6);
        for (String bizType : List.of(FlowChainSeedService.BIZ_RELEASE, FlowChainSeedService.BIZ_REQ_DEPLOY)) {
            List<WorkflowVersion> candidates = FlowChainSeedService.BIZ_RELEASE.equals(bizType)
                    ? allRelease : allDeploy;
            for (String level : ReleaseLevel.ALL) {
                FlowBindingEntity row = bound.get(key(bizType, level));
                WorkflowVersion definition = row == null
                        ? fallbackDefinition(tenantId, bizType, level, candidates)
                        : candidates.stream().filter(d -> d.getId().equals(row.getDefinitionId()))
                                .findFirst().orElse(null);
                slots.add(slot(bizType, level, definition, row));
            }
        }
        return slots;
    }

    /**
     * 保存某槽的绑定（upsert）。校验：bizType 必须是受管的两类、level 三值、定义同租户且 PUBLISHED 且同 bizType。
     *
     * <p>为什么跨 bizType 直接 400 而不是顺手改定义：把一条 REQ_DEPLOY 链绑到版本发布槽上，
     * 引擎会照定义跑起来，但节点语义（谁签）与单据语义（哪个对象）就对不上了——
     * 这种错误只会在审批人拿到一条莫名其妙的待办时才被发现。</p>
     */
    @Transactional
    public FlowChainViews.BindingSlot save(long tenantId, String bizType, String level, long definitionId) {
        requireAdmin(tenantId);
        String normalisedBizType = requireBizType(bizType);
        String normalisedLevel = requireLevel(level);
        WorkflowVersion definition = definitions.findById(definitionId)
                .orElseThrow(() -> new ApprovalException(400, "BINDING_INVALID",
                        "流程定义不存在：" + definitionId, Map.of("field", "definitionId")));
        if (definition.getTenantId() == null || definition.getTenantId() != tenantId) {
            throw new ApprovalException(400, "BINDING_INVALID",
                    "流程定义不属于当前租户：" + definitionId, Map.of("field", "definitionId"));
        }
        if (!normalisedBizType.equals(definition.getBizType())) {
            throw new ApprovalException(400, "BINDING_INVALID",
                    "定义类型不匹配：该定义属于 " + definition.getBizType() + "，槽位是 " + normalisedBizType,
                    Map.of("field", "definitionId", "bizType", definition.getBizType()));
        }
        if (!WorkflowVersion.STATUS_PUBLISHED.equals(definition.getStatus())) {
            throw new ApprovalException(409, "DEFINITION_NOT_PUBLISHED",
                    "只有已发布（PUBLISHED）的流程定义可以绑定，当前状态：" + definition.getStatus(),
                    Map.of("definitionId", definitionId, "status", definition.getStatus()));
        }
        ApprovalActor actor = actors.current();
        LocalDateTime now = LocalDateTime.now();
        FlowBindingEntity row = repository
                .findByTenantIdAndBizTypeAndReleaseLevel(tenantId, normalisedBizType, normalisedLevel)
                .orElseGet(() -> {
                    FlowBindingEntity created = new FlowBindingEntity();
                    created.setTenantId(tenantId);
                    created.setBizType(normalisedBizType);
                    created.setReleaseLevel(normalisedLevel);
                    created.setCreatedAt(now);
                    created.setCreatedBy(actor.userId());
                    return created;
                });
        row.setDefinitionId(definitionId);
        row.setUpdatedAt(now);
        row.setUpdatedBy(actor.userId());
        row = repository.save(row);
        audit.record("FLOW_BINDING_SAVE", "wf_flow_binding", row.getId(), Map.of(
                "bizType", normalisedBizType, "releaseLevel", normalisedLevel,
                "definitionId", definitionId, "definitionVersion",
                definition.getVersion() == null ? 0 : definition.getVersion()));
        return slot(normalisedBizType, normalisedLevel, definition, row);
    }

    /**
     * 恢复默认：<b>删行</b>，返回重新解析后的槽。
     *
     * <p>不抄一份「默认 definition_id」写回去：默认值只在 {@link FlowChainSeedService} 里有一份，
     * 抄进表就变成两份，种子升级后谁对谁错无从判断。删行后解析自然落到默认链，
     * 而「管理员确实希望钉住某条链」的场景由 {@link #save} 覆盖。</p>
     */
    @Transactional
    public FlowChainViews.BindingSlot reset(long tenantId, String bizType, String level) {
        requireAdmin(tenantId);
        String normalisedBizType = requireBizType(bizType);
        String normalisedLevel = requireLevel(level);
        Optional<FlowBindingEntity> existing = repository
                .findByTenantIdAndBizTypeAndReleaseLevel(tenantId, normalisedBizType, normalisedLevel);
        existing.ifPresent(row -> {
            repository.delete(row);
            audit.record("FLOW_BINDING_RESET", "wf_flow_binding", row.getId(),
                    Map.of("bizType", normalisedBizType, "releaseLevel", normalisedLevel,
                            "definitionId", row.getDefinitionId()));
        });
        return slots(tenantId).stream()
                .filter(slot -> slot.bizType().equals(normalisedBizType)
                        && slot.releaseLevel().equals(normalisedLevel))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("槽位丢失：" + normalisedBizType + "/" + normalisedLevel));
    }

    /**
     * 解析某槽当前生效的 definitionId；{@code null} 表示<b>本租户该 bizType 下没有任何可用定义</b>。
     *
     * <p>调用方拿到 null 之后要看上下文：发起审批（有单据）应当 409 {@code WORKFLOW_NOT_CONFIGURED}，
     * 而读详情（只展示）应当容忍 null 并显示「未配置链路」——所以这里返回 null 而不是直接抛。</p>
     */
    @Transactional(readOnly = true)
    public Long resolveDefinitionId(long tenantId, String bizType, String level) {
        String normalisedLevel = ReleaseLevel.normalise(level);
        List<WorkflowVersion> candidates = definitions.findByTenantIdAndBizTypeOrderByVersionDesc(tenantId, bizType);
        Optional<FlowBindingEntity> bound = repository
                .findByTenantIdAndBizTypeAndReleaseLevel(tenantId, bizType, normalisedLevel);
        if (bound.isPresent()) {
            Long boundId = bound.get().getDefinitionId();
            // 绑定的定义被删/被停用时不要把它丢给引擎（引擎会 404 一条没人看得懂的错误）：
            // 退回默认链，且下一次 save 会把这行修正过来。
            boolean usable = candidates.stream().anyMatch(candidate -> candidate.getId().equals(boundId)
                    && WorkflowVersion.STATUS_PUBLISHED.equals(candidate.getStatus()));
            if (usable) {
                return boundId;
            }
        }
        WorkflowVersion fallback = fallbackDefinition(tenantId, bizType, normalisedLevel, candidates);
        return fallback == null ? null : fallback.getId();
    }

    /** 同 {@link #resolveDefinitionId}，但解析不到即 409（发起审批的唯一入口用它）。 */
    @Transactional(readOnly = true)
    public long requireDefinitionId(long tenantId, String bizType, String level) {
        Long definitionId = resolveDefinitionId(tenantId, bizType, level);
        if (definitionId == null) {
            throw new ApprovalException(409, "WORKFLOW_NOT_CONFIGURED",
                    "该单据还没有可用的审批流程定义，请先在「审批链绑定」里绑定或发布一条定义",
                    Map.of("bizType", bizType, "releaseLevel", ReleaseLevel.normalise(level)));
        }
        return definitionId;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 兜底解析：默认定义名（须 PUBLISHED）→ 任一 PUBLISHED（is_default 优先、版本降序）。
     *
     * <p>候选列表由调用方一次读好传进来（slots 要拼 6 个槽，不能每个槽查一次库）。</p>
     */
    private WorkflowVersion fallbackDefinition(long tenantId, String bizType, String level,
                                               List<WorkflowVersion> candidates) {
        String defaultName = FlowChainSeedService.defaultName(bizType, level);
        if (defaultName != null) {
            for (WorkflowVersion candidate : candidates) {
                if (defaultName.equals(candidate.getName())
                        && WorkflowVersion.STATUS_PUBLISHED.equals(candidate.getStatus())) {
                    return candidate;
                }
            }
        }
        return candidates.stream()
                .filter(candidate -> WorkflowVersion.STATUS_PUBLISHED.equals(candidate.getStatus()))
                .sorted(Comparator
                        .comparingInt((WorkflowVersion d) -> d.isDefault() ? 0 : 1)
                        .thenComparing(d -> d.getVersion() == null ? 0 : -d.getVersion()))
                .findFirst()
                .orElse(null);
    }

    private FlowChainViews.BindingSlot slot(String bizType, String level, WorkflowVersion definition,
                                            FlowBindingEntity row) {
        String defaultName = FlowChainSeedService.defaultName(bizType, level);
        boolean isDefault = definition != null && definition.getName() != null
                && definition.getName().equals(defaultName);
        return new FlowChainViews.BindingSlot(
                bizType,
                level,
                ReleaseLevel.label(level),
                definition == null ? null : definition.getId(),
                definition == null ? defaultName : definition.getName(),
                definition == null ? null : definition.getVersion(),
                definition == null ? null : definition.getStatus(),
                isDefault,
                definition == null ? List.of() : nodeLines(definition),
                row == null ? null : String.valueOf(row.getUpdatedAt() == null
                        ? row.getCreatedAt() : row.getUpdatedAt()));
    }

    /** 节点链展示行：只取 key/name/mode（绑定页画 chips），解析失败给空列表而不是抛。 */
    private List<FlowChainViews.NodeLine> nodeLines(WorkflowVersion definition) {
        try {
            WorkflowDefinition parsed = json.parse(definition.getDefinitionJson());
            return parsed.nodes().stream()
                    .map(node -> new FlowChainViews.NodeLine(node.key(), node.displayName(),
                            node.mode() == null ? WorkflowDefinition.MODE_ALL : node.mode()))
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /**
     * 改绑定是管理员动作（AC-6：非管理员 403）。判定复用既有 {@link ApprovalActorProvider#isAdmin}
     * 的口径（与「维护流程定义」同一批人），<b>不新增权限码</b>——新增码会让既有
     * rbac 计数与角色默认授权同时变，属于本需求明确排除的涟漪。
     */
    private void requireAdmin(long tenantId) {
        ApprovalActor actor = actors.current();
        if (!actors.isAdmin(tenantId, actor.userId())) {
            throw ApprovalException.forbidden("只有管理员或 PMO 可以维护分级审批绑定");
        }
    }

    private static String requireBizType(String bizType) {
        if (!FlowChainSeedService.supportsBizType(bizType)) {
            throw new ApprovalException(400, "BINDING_INVALID",
                    "不支持的单据类型：" + bizType + "（只支持 RELEASE / REQ_DEPLOY）",
                    Map.of("field", "bizType", "value", bizType == null ? "" : bizType));
        }
        return bizType;
    }

    private static String requireLevel(String level) {
        String normalised = ReleaseLevel.normalise(level);
        if (!ReleaseLevel.isValid(normalised)) {
            throw new ApprovalException(400, "BINDING_INVALID",
                    "非法发布等级：" + level + "（只支持 NORMAL / EMERGENCY / ROLLBACK）",
                    Map.of("field", "releaseLevel", "value", level == null ? "" : level));
        }
        return normalised;
    }

    private static String key(String bizType, String level) {
        return bizType + "|" + level;
    }
}
