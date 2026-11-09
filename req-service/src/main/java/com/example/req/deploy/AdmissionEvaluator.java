package com.example.req.deploy;

import com.example.platform.contract.GateDecision;
import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalInstanceRepository;
import com.example.req.integration.DeliverGateClient;
import com.example.req.integration.DeliverQualityGateClient;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementRepository;
import com.example.req.requirement.RequirementReviewCallback;
import com.example.req.requirement.RequirementSummaryProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 准入取数 + 快照读写（判定规则在 {@link AdmissionPolicy}，这里只负责把事实取齐）。
 *
 * <p>跨服务取数只有三处、都在 integration 包：一次批量门禁 + 一次缺陷计数（{@link DeliverGateClient}）、
 * 一次发布质量门（{@link DeliverQualityGateClient}，M6 追加的第 6 项）。任一不可用都返回 {@code null}
 * 交给策略层判 FAIL（fail-closed，绝不因为超时把门禁放过去）。
 * 时间预算 500ms：门禁 250ms + 缺陷 200ms + 质量门 500ms（质量门与整体同预算，最快路径只打一次）。</p>
 */
@Component
public class AdmissionEvaluator {

    private final RequirementRepository requirementRepository;
    private final DeployOrderRequirementRepository orderRequirementRepository;
    private final ApprovalInstanceRepository instanceRepository;
    private final DeliverGateClient deliverGateClient;
    private final DeliverQualityGateClient qualityGateClient;
    private final AdmissionPolicy policy = new AdmissionPolicy();
    private final ObjectMapper objectMapper;

    public AdmissionEvaluator(RequirementRepository requirementRepository,
                              DeployOrderRequirementRepository orderRequirementRepository,
                              ApprovalInstanceRepository instanceRepository,
                              DeliverGateClient deliverGateClient,
                              DeliverQualityGateClient qualityGateClient,
                              ObjectMapper objectMapper) {
        this.requirementRepository = requirementRepository;
        this.orderRequirementRepository = orderRequirementRepository;
        this.instanceRepository = instanceRepository;
        this.deliverGateClient = deliverGateClient;
        this.qualityGateClient = qualityGateClient;
        this.objectMapper = objectMapper;
    }

    /** 重新取数并判定（不落库）。 */
    public DeployViews.AdmissionResult evaluate(DeployOrderEntity order) {
        List<AdmissionPolicy.ReqFact> facts = facts(order);

        Map<Long, GateDecision> gates = null;
        Map<Long, Integer> openHigh = null;
        if (!facts.isEmpty()) {
            List<Long> ids = facts.stream().map(AdmissionPolicy.ReqFact::id).toList();
            try {
                gates = deliverGateClient.evaluateAll(ids);
            } catch (RuntimeException ex) {
                gates = null;
            }
            try {
                openHigh = deliverGateClient.openHighDefects(ids);
            } catch (RuntimeException ex) {
                openHigh = null;
            }
        } else {
            gates = Map.of();
            openHigh = Map.of();
        }

        DeployViews.GateEvidence gate = gate(order);
        List<AdmissionItem> items = policy.evaluate(new AdmissionPolicy.Inputs(facts, gates, openHigh, gate));
        return new DeployViews.AdmissionResult(items, LocalDateTime.now(),
                AdmissionPolicy.signature(facts, gate == null ? null : gate.hash()), gate);
    }

    /**
     * 第 6 项取数：一次求值（交付侧按 {@code deployOrderId + result_digest} 幂等复用快照）。
     *
     * <p>失败一律返回 {@code null}——{@link AdmissionPolicy} 会判 FAIL 且 {@code detail} 带
     * {@code GATE_UNAVAILABLE}，这样"deliver 挂了"与"门禁真的没过"在准入清单上是两种可区分的结论。</p>
     */
    private DeployViews.GateEvidence gate(DeployOrderEntity order) {
        try {
            DeliverQualityGateClient.Evaluation evaluation = qualityGateClient.evaluateDetailed(
                    order.getReleaseId(), order.getId(), requirementIds(order));
            return DeployViews.GateEvidence.of(evaluation.snapshot(), evaluation.gateId(), evaluation.reportId());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public boolean allPassed(DeployViews.AdmissionResult result) {
        return result != null && result.allPassed();
    }

    /** 判定并把快照写回实体（由 Service 在同事务内落库）。 */
    public DeployViews.AdmissionResult evaluateAndStore(DeployOrderEntity order) {
        DeployViews.AdmissionResult result = evaluate(order);
        order.setAdmissionResultJson(toJson(result));
        return result;
    }

    /**
     * 详情页用的快照：{@code stale=true} 表示判定之后需求集合/状态变了（指纹不一致），
     * 页面必须提示"重新校验"，不能把旧结论当现行结论渲染。
     */
    public DeployViews.AdmissionSnapshot snapshot(DeployOrderEntity order) {
        DeployViews.AdmissionResult stored = parse(order.getAdmissionResultJson());
        if (stored == null) {
            return new DeployViews.AdmissionSnapshot(List.of(), null, false);
        }
        boolean stale = stored.signature() != null
                && !stored.signature().equals(AdmissionPolicy.signature(facts(order),
                        stored.gate() == null ? null : stored.gate().hash()));
        List<AdmissionItem> items = AdmissionPolicy.sort(AdmissionPolicy.markForced(
                stored.items(), order.isForcedRelease()));
        return new DeployViews.AdmissionSnapshot(items, stored.checkedAt(), stale, stored.signature(),
                DeployViews.AdmissionSnapshot.legacyOf(items), stored.gate());
    }

    // ------------------------------------------------------------ 取数

    public List<Long> requirementIds(DeployOrderEntity order) {
        return orderRequirementRepository
                .findByTenantIdAndDeployOrderIdOrderByRequirementIdAsc(order.getTenantId(), order.getId())
                .stream()
                .map(DeployOrderRequirementEntity::getRequirementId)
                .toList();
    }

    public List<AdmissionPolicy.ReqFact> facts(DeployOrderEntity order) {
        List<Long> ids = requirementIds(order);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, RequirementEntity> byId = new HashMap<>();
        for (RequirementEntity entity : requirementRepository.findAllById(ids)) {
            if (order.getTenantId().equals(entity.getTenantId())) {
                byId.put(entity.getId(), entity);
            }
        }
        List<AdmissionPolicy.ReqFact> facts = new ArrayList<>();
        for (Long id : ids) {
            RequirementEntity entity = byId.get(id);
            if (entity == null) {
                continue;
            }
            facts.add(new AdmissionPolicy.ReqFact(entity.getId(), entity.getCode(), entity.getType(),
                    entity.getStatus(), entity.getMrUrl(), entity.getEstimateDays(), entity.getActualDays(),
                    blockingApprovals(order.getTenantId(), entity)));
        }
        facts.sort(Comparator.comparingLong(AdmissionPolicy.ReqFact::id));
        return facts;
    }

    /**
     * 需求级审批：取"需求链上真正承载审批的那一代"的最近一次实例。
     *
     * <p>只有 BUS 有 {@code submit → APPROVING} 迁移，RD/PDR 的表里没有 APPROVING，
     * 它们本身发不起审批。如果按 RD 的 id 去查实例，正常单据永远差"提报:未发起"，
     * 准入第 5 项（AC-12）：提报审批与评审审批都必须在同一条需求链上 APPROVED。
     * REQ_REVIEW 已有 review_start 入口（D-10），因此「未发起」本身就是阻断项，
     * 不能再退化成「只有实例存在才要求」——那会让评审缺失悄悄通过准入。</p>
     */
    private List<String> blockingApprovals(long tenantId, RequirementEntity requirement) {
        RequirementEntity line = approvalLine(tenantId, requirement);
        List<String> blocking = new ArrayList<>();
        ApprovalInstance submit = instanceRepository
                .findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                        tenantId, RequirementSummaryProvider.BIZ_TYPE, line.getId())
                .orElse(null);
        if (submit == null) {
            blocking.add("提报:未发起");
        } else if (!ApprovalInstance.STATUS_APPROVED.equals(submit.getStatus())) {
            blocking.add("提报:" + submit.getStatus());
        }
        ApprovalInstance review = instanceRepository
                .findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                        tenantId, RequirementReviewCallback.BIZ_TYPE, line.getId())
                .orElse(null);
        if (review == null) {
            blocking.add("评审:未发起");
        } else if (!ApprovalInstance.STATUS_APPROVED.equals(review.getStatus())) {
            blocking.add("评审:" + review.getStatus());
        }
        return blocking;
    }

    /** 沿父子链上溯，返回第一代已有审批实例的需求；没有则回落到自身。 */
    private RequirementEntity approvalLine(long tenantId, RequirementEntity start) {
        RequirementEntity cursor = start;
        for (int depth = 0; cursor != null && depth < 8; depth++) {
            if (hasInstance(tenantId, cursor.getId())) {
                return cursor;
            }
            Long parentId = cursor.getParentId();
            cursor = parentId == null ? null : requirementRepository.findById(parentId)
                    .filter(parent -> Long.valueOf(tenantId).equals(parent.getTenantId()))
                    .orElse(null);
        }
        return start;
    }

    private boolean hasInstance(long tenantId, long requirementId) {
        return instanceRepository
                .findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                        tenantId, RequirementSummaryProvider.BIZ_TYPE, requirementId)
                .isPresent()
                || instanceRepository
                .findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                        tenantId, RequirementReviewCallback.BIZ_TYPE, requirementId)
                .isPresent();
    }

    // ------------------------------------------------------------ 快照 JSON

    public String toJson(DeployViews.AdmissionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", result.items());
        payload.put("checkedAt", result.checkedAt());
        payload.put("signature", result.signature());
        // 第 6 项的证据一起落库：提交时据此写 deploy_order.quality_gate*（AC-5.6），
        // 详情页也能在 deliver 暂时不可用时展示"当时判定的是哪一份快照"。
        payload.put("gate", result.gate());
        return objectMapper.writeValueAsString(payload);
    }

    /** 脏数据/旧格式一律降级为 null（详情不因一条坏快照打不开），由页面提示"未校验"。 */
    public DeployViews.AdmissionResult parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            DeployViews.AdmissionResult parsed = objectMapper.readValue(json, DeployViews.AdmissionResult.class);
            if (parsed == null || parsed.items() == null) {
                return null;
            }
            return parsed;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
