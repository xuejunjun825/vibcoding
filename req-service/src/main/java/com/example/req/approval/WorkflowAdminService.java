package com.example.req.approval;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 流程定义管理（管理员/PMO）：草稿、编辑、发布、停用、默认流程。
 *
 * <p>三条不变量：
 * <ol>
 *   <li>发布前必须过 {@link WorkflowDefinitionValidator}——审批人解析不出要在<b>发布时</b>暴露，
 *       而不是让真实单据卡在某个节点（AC-6）。</li>
 *   <li>已发布的行不可编辑：任何修改都新建 {@code version+1} 的 DRAFT（{@code 409 WORKFLOW_PUBLISHED_IMMUTABLE}），
 *       所以在途实例读到的定义永远和它启动时一致（AC-8）。</li>
 *   <li>默认流程每个 {@code bizType} 至多一条 {@code is_default=1}。</li>
 * </ol>
 */
@Service
public class WorkflowAdminService {

    private final WorkflowVersionRepository repository;
    private final WorkflowDefinitionValidator validator;
    private final WorkflowJson json;
    private final ApprovalActorProvider actors;
    private final Clock clock;

    public WorkflowAdminService(WorkflowVersionRepository repository,
                                WorkflowDefinitionValidator validator,
                                WorkflowJson json,
                                ApprovalActorProvider actors,
                                Clock clock) {
        this.repository = repository;
        this.validator = validator;
        this.json = json;
        this.actors = actors;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WorkflowView> list(String bizType) {
        ApprovalActor actor = actors.current();
        requireAdmin(actor);
        List<WorkflowVersion> rows = bizType == null || bizType.isBlank()
                ? repository.findByTenantIdOrderByBizTypeAscVersionDesc(actor.tenantId())
                : repository.findByTenantIdAndBizTypeOrderByVersionDesc(actor.tenantId(), bizType);
        List<WorkflowView> views = new ArrayList<>(rows.size());
        for (WorkflowVersion row : rows) {
            views.add(WorkflowView.of(row, json.readMap(row.getDefinitionJson())));
        }
        return views;
    }

    /** 单个定义（含 definition JSON）：编辑页回填用，避免前端拿列表再按 id 猜。 */
    @Transactional(readOnly = true)
    public WorkflowView get(long id) {
        ApprovalActor actor = actors.current();
        requireAdmin(actor);
        WorkflowVersion row = requireRow(actor, id);
        return WorkflowView.of(row, json.readMap(row.getDefinitionJson()));
    }

    @Transactional
    public WorkflowView create(CreateWorkflow cmd) {
        ApprovalActor actor = actors.current();
        requireAdmin(actor);
        requireBizType(cmd.bizType());
        if (cmd.definition() == null) {
            throw ApprovalException.badRequest(ApprovalException.WORKFLOW_INVALID, "流程定义不能为空");
        }
        WorkflowDefinition definition = cmd.definition();
        validator.validate(definition, actor.tenantId(), actor.userId(), List.of(), false);

        String reqType = cmd.reqType() == null || cmd.reqType().isBlank()
                ? WorkflowVersion.ANY_REQ_TYPE : cmd.reqType();
        long domainId = cmd.domainId() == null ? WorkflowVersion.ANY_DOMAIN : cmd.domainId();
        int version = nextVersion(actor.tenantId(), cmd.bizType(), reqType, domainId);

        LocalDateTime now = LocalDateTime.now(clock);
        WorkflowVersion row = new WorkflowVersion();
        row.setTenantId(actor.tenantId());
        row.setName(cmd.name());
        row.setBizType(cmd.bizType());
        row.setReqType(reqType);
        row.setDomainId(domainId);
        row.setVersion(version);
        row.setDefinitionJson(json.write(definition));
        row.setStatus(WorkflowVersion.STATUS_DRAFT);
        row.setIsDefault(Boolean.TRUE.equals(cmd.isDefault()) ? 1 : 0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setCreatedBy(actor.userId());
        row.setUpdatedBy(actor.userId());
        row = repository.save(row);
        return WorkflowView.of(row, json.readMap(row.getDefinitionJson()));
    }

    @Transactional
    public WorkflowView update(long id, UpdateWorkflow cmd) {
        ApprovalActor actor = actors.current();
        requireAdmin(actor);
        WorkflowVersion row = requireRow(actor, id);
        if (WorkflowVersion.STATUS_PUBLISHED.equals(row.getStatus())) {
            // 已发布 = 固化。改定义只能新建版本，否则在途实例会读到被改过的节点（AC-8 的根因）。
            throw ApprovalException.conflict(ApprovalException.WORKFLOW_PUBLISHED_IMMUTABLE,
                    "已发布的流程不可编辑，请新建版本", "definitionId", id);
        }
        if (cmd.definition() != null) {
            validator.validate(cmd.definition(), actor.tenantId(), actor.userId(), List.of(), false);
            row.setDefinitionJson(json.write(cmd.definition()));
        }
        if (cmd.name() != null && !cmd.name().isBlank()) {
            row.setName(cmd.name());
        }
        if (cmd.isDefault() != null) {
            row.setIsDefault(cmd.isDefault() ? 1 : 0);
        }
        row.setUpdatedAt(LocalDateTime.now(clock));
        row.setUpdatedBy(actor.userId());
        row = repository.save(row);
        return WorkflowView.of(row, json.readMap(row.getDefinitionJson()));
    }

    @Transactional
    public WorkflowView publish(long id) {
        ApprovalActor actor = actors.current();
        requireAdmin(actor);
        WorkflowVersion row = requireRow(actor, id);
        if (WorkflowVersion.STATUS_PUBLISHED.equals(row.getStatus())) {
            throw ApprovalException.conflict(ApprovalException.WORKFLOW_PUBLISHED_IMMUTABLE,
                    "该版本已发布，不能重复发布", "definitionId", id);
        }
        if (WorkflowVersion.STATUS_DISABLED.equals(row.getStatus())) {
            throw ApprovalException.conflict(ApprovalException.ILLEGAL_TRANSITION,
                    "已停用的版本不能发布，请新建版本", "definitionId", id);
        }
        WorkflowDefinition definition = json.parse(row.getDefinitionJson());
        validator.validate(definition, actor.tenantId(), actor.userId(), List.of());

        LocalDateTime now = LocalDateTime.now(clock);
        row.setStatus(WorkflowVersion.STATUS_PUBLISHED);
        row.setPublishedAt(now);
        row.setPublishedBy(actor.userId());
        row.setUpdatedAt(now);
        row.setUpdatedBy(actor.userId());
        row = repository.save(row);

        if (row.isDefault()) {
            clearOtherDefaults(actor.tenantId(), row.getBizType(), row.getId(), actor.userId());
        }
        return WorkflowView.of(row, json.readMap(row.getDefinitionJson()));
    }

    @Transactional
    public WorkflowView disable(long id) {
        ApprovalActor actor = actors.current();
        requireAdmin(actor);
        WorkflowVersion row = requireRow(actor, id);
        row.setStatus(WorkflowVersion.STATUS_DISABLED);
        row.setUpdatedAt(LocalDateTime.now(clock));
        row.setUpdatedBy(actor.userId());
        row = repository.save(row);
        return WorkflowView.of(row, json.readMap(row.getDefinitionJson()));
    }

    // ------------------------------------------------------------------ 内部

    private void clearOtherDefaults(long tenantId, String bizType, Long keepId, long operatorId) {
        for (WorkflowVersion other : repository.findByTenantIdAndBizTypeAndIsDefault(tenantId, bizType, 1)) {
            if (keepId != null && keepId.equals(other.getId())) {
                continue;
            }
            other.setIsDefault(0);
            other.setUpdatedAt(LocalDateTime.now(clock));
            other.setUpdatedBy(operatorId);
            repository.save(other);
        }
    }

    private int nextVersion(long tenantId, String bizType, String reqType, long domainId) {
        return repository
                .findFirstByTenantIdAndBizTypeAndReqTypeAndDomainIdOrderByVersionDesc(
                        tenantId, bizType, reqType, domainId)
                .map(last -> (last.getVersion() == null ? 0 : last.getVersion()) + 1)
                .orElse(1);
    }

    private WorkflowVersion requireRow(ApprovalActor actor, long id) {
        WorkflowVersion row = repository.findById(id)
                .orElseThrow(() -> ApprovalException.notFound("流程定义不存在：" + id));
        if (row.getTenantId() == null || row.getTenantId() != actor.tenantId()) {
            throw new ApprovalException(403, ApprovalException.TENANT_MISMATCH,
                    "流程定义不属于当前租户", Map.of("definitionId", id));
        }
        return row;
    }

    private void requireAdmin(ApprovalActor actor) {
        if (!actors.isAdmin(actor.tenantId(), actor.userId())) {
            throw ApprovalException.forbidden("只有管理员或 PMO 可以维护流程定义");
        }
    }

    /**
     * 只接受四类单据键（AC-6）：写错 bizType 的流程能创建成功但永远匹配不到单据，
     * 属于「静默死流程」，必须在创建期就 400 拦下（2026-11-09 T11 实测脚本传 REQUIREMENT）。
     */
    private void requireBizType(String bizType) {
        String value = bizType == null ? "" : bizType.trim();
        if (!BUSINESS_TYPES.contains(value)) {
            throw ApprovalException.badRequest(ApprovalException.WORKFLOW_INVALID,
                    "bizType 必须是 REQ_SUBMIT/REQ_REVIEW/RELEASE/REQ_DEPLOY：" + value,
                    "bizType", value);
        }
    }

    private static final java.util.Set<String> BUSINESS_TYPES = java.util.Set.of(
            WorkflowVersion.BIZ_REQ_SUBMIT, WorkflowVersion.BIZ_REQ_REVIEW,
            WorkflowVersion.BIZ_RELEASE, WorkflowVersion.BIZ_REQ_DEPLOY);

    /** 新建流程（草稿）。 */
    public record CreateWorkflow(String name,
                                 String bizType,
                                 String reqType,
                                 Long domainId,
                                 Boolean isDefault,
                                 WorkflowDefinition definition) {
    }

    /** 编辑草稿：只允许改定义/名称/默认标记。 */
    public record UpdateWorkflow(String name, Boolean isDefault, WorkflowDefinition definition) {
    }

    /** 管理端视图：定义以 Map 返回，前端不需要再解一次字符串。 */
    public record WorkflowView(long id,
                               long tenantId,
                               String name,
                               String bizType,
                               String reqType,
                               long domainId,
                               int version,
                               String status,
                               boolean isDefault,
                               LocalDateTime publishedAt,
                               Long publishedBy,
                               LocalDateTime createdAt,
                               Map<String, Object> definition) {

        static WorkflowView of(WorkflowVersion row, Map<String, Object> definition) {
            return new WorkflowView(
                    row.getId() == null ? 0L : row.getId(),
                    row.getTenantId() == null ? 0L : row.getTenantId(),
                    row.getName(),
                    row.getBizType(),
                    row.getReqType(),
                    row.getDomainId() == null ? 0L : row.getDomainId(),
                    row.getVersion() == null ? 0 : row.getVersion(),
                    row.getStatus(),
                    row.isDefault(),
                    row.getPublishedAt(),
                    row.getPublishedBy(),
                    row.getCreatedAt(),
                    definition);
        }
    }
}
