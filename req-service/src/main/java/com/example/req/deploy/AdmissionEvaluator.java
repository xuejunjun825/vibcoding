package com.example.req.deploy;

import com.example.platform.contract.GateDecision;
import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalInstanceRepository;
import com.example.req.integration.DeliverGateClient;
import com.example.req.integration.DeliverQualityGateClient;
import com.example.req.integration.admission.AdmissionItemConfigService;
import com.example.req.integration.admission.AdmissionViews;
import com.example.req.integration.admission.IntegrationEvidenceReader;
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
import java.util.Set;
import java.util.function.Supplier;

/**
 * 准入取数 + 快照读写（判定规则在 {@link AdmissionPolicy}，这里只负责把事实取齐）。
 *
 * <p>跨服务取数只有三处：一次批量门禁 + 一次缺陷计数（{@link DeliverGateClient}）、
 * 一次发布质量门（{@link DeliverQualityGateClient}）。7/8/9 三项<b>零外呼</b>：
 * {@link IntegrationEvidenceReader} 只读本地事实表（构建记录 / Sonar 快照 / 制品记录），
 * 外呼发生在"触发构建 / 刷新快照 / 登记制品"的后台动作里。</p>
 *
 * <p>500ms 预算（TECHNICAL §7.3）：门禁 ≤250ms、缺陷 ≤200ms、质量门 ≤400ms，⑦⑧⑨ 本地读
 * 每项 ≤30ms；剩余预算不足 30ms 时不再发新调用，该项直接判 FAIL（fail-closed，绝不放行）。</p>
 */
@Component
public class AdmissionEvaluator {

    /** 准入总预算（PRD AC-8.6）。 */
    public static final long ADMISSION_BUDGET_MS = 500;
    /** 单次调用前的最小剩余预算：低于它就不再发起，避免尾巴上的一次慢调用拖爆总预算。 */
    private static final long MIN_SLICE_MS = 30;

    private final RequirementRepository requirementRepository;
    private final DeployOrderRequirementRepository orderRequirementRepository;
    private final ApprovalInstanceRepository instanceRepository;
    private final DeliverGateClient deliverGateClient;
    private final DeliverQualityGateClient qualityGateClient;
    private final IntegrationEvidenceReader evidenceReader;
    private final AdmissionItemConfigService itemConfigService;
    private final AdmissionPolicy policy = new AdmissionPolicy();
    private final ObjectMapper objectMapper;

    public AdmissionEvaluator(RequirementRepository requirementRepository,
                              DeployOrderRequirementRepository orderRequirementRepository,
                              ApprovalInstanceRepository instanceRepository,
                              DeliverGateClient deliverGateClient,
                              DeliverQualityGateClient qualityGateClient,
                              IntegrationEvidenceReader evidenceReader,
                              AdmissionItemConfigService itemConfigService,
                              ObjectMapper objectMapper) {
        this.requirementRepository = requirementRepository;
        this.orderRequirementRepository = orderRequirementRepository;
        this.instanceRepository = instanceRepository;
        this.deliverGateClient = deliverGateClient;
        this.qualityGateClient = qualityGateClient;
        this.evidenceReader = evidenceReader;
        this.itemConfigService = itemConfigService;
        this.objectMapper = objectMapper;
    }

    /** 重新取数并判定（不落库）。 */
    public DeployViews.AdmissionResult evaluate(DeployOrderEntity order) {
        long deadline = System.nanoTime() + ADMISSION_BUDGET_MS * 1_000_000L;
        long tenantId = order.getTenantId();
        List<AdmissionPolicy.ReqFact> facts = facts(order);

        Map<Long, GateDecision> gates = null;
        Map<Long, Integer> openHigh = null;
        if (!facts.isEmpty()) {
            List<Long> ids = facts.stream().map(AdmissionPolicy.ReqFact::id).toList();
            if (budgetLeft(deadline)) {
                try {
                    gates = deliverGateClient.evaluateAll(ids);
                } catch (RuntimeException ex) {
                    gates = null;
                }
            }
            if (budgetLeft(deadline)) {
                try {
                    openHigh = deliverGateClient.openHighDefects(ids);
                } catch (RuntimeException ex) {
                    openHigh = null;
                }
            }
        } else {
            gates = Map.of();
            openHigh = Map.of();
        }

        DeployViews.GateEvidence gate = gate(order, deadline);
        Set<String> closed = closedCodes(tenantId);
        AdmissionViews.Evidence build = evidence(deadline,
                () -> evidenceReader.build(tenantId, order.getId(), order.getBuildId()));
        AdmissionViews.Evidence sonar = evidence(deadline,
                () -> evidenceReader.sonarAggregate(tenantId, requirementIds(order)));
        AdmissionViews.Evidence artifact = evidence(deadline,
                () -> evidenceReader.artifact(tenantId, order.getArtifactId()));

        List<AdmissionItem> items = policy.evaluate(new AdmissionPolicy.Inputs(
                facts, gates, openHigh, gate, closed, build, sonar, artifact));
        String signature = AdmissionPolicy.signature(facts, gate == null ? null : gate.hash(),
                AdmissionPolicy.integrationDigest(items, closed));
        return new DeployViews.AdmissionResult(items, null, LocalDateTime.now(), signature, gate);
    }

    // ------------------------------------------------------------ 预算

    private static boolean budgetLeft(long deadline) {
        return (deadline - System.nanoTime()) / 1_000_000L >= MIN_SLICE_MS;
    }

    /**
     * 本地证据读取 + 预算：剩余预算不足 = 本次不读，按 {@code ADAPTER_TIMEOUT} 判 FAIL；
     * 读取抛异常 = {@code EVIDENCE_UNAVAILABLE}（fail-closed，绝不因为读不到就当没有要求）。
     */
    private AdmissionViews.Evidence evidence(long deadline, Supplier<AdmissionViews.Evidence> supplier) {
        if (!budgetLeft(deadline)) {
            return AdmissionViews.Evidence.fail("ADAPTER_TIMEOUT",
                    "准入预算已用尽（剩余不足 " + MIN_SLICE_MS + "ms），该项未取数，按失败处理", null);
        }
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            return AdmissionViews.Evidence.fail("EVIDENCE_UNAVAILABLE",
                    "读取本地证据失败：" + ex.getClass().getSimpleName()
                            + (ex.getMessage() == null ? "" : "：" + ex.getMessage()), null);
        }
    }

    /** 项开关：读失败按"全部开启"处理（默认即开启，关闭是显式状态）。 */
    private Set<String> closedCodes(long tenantId) {
        try {
            return itemConfigService.closedCodes(tenantId);
        } catch (RuntimeException ex) {
            return Set.of();
        }
    }

    /**
     * 第 6 项取数：一次求值（交付侧按 {@code deployOrderId + result_digest} 幂等复用快照）。
     *
     * <p>失败一律返回 {@code null}——{@link AdmissionPolicy} 会判 FAIL 且 {@code detail} 带
     * {@code GATE_UNAVAILABLE}，这样"deliver 挂了"与"门禁真的没过"在准入清单上是两种可区分的结论。</p>
     */
    private DeployViews.GateEvidence gate(DeployOrderEntity order, long deadline) {
        if (!budgetLeft(deadline)) {
            return null;
        }
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
            // NULL 快照 = 从未评估过（M6 之前落库的旧单）：显式给 legacy=true / gate=null，
            // 不依赖 3 参构造器的派生逻辑，避免这条语义再次被"空集 ⇒ 非历史"的实现悄悄改回去（R2）。
            return new DeployViews.AdmissionSnapshot(List.of(), null, false, null, true, null);
        }
        String currentFacts = AdmissionPolicy.factsDigest(facts(order));
        boolean stale = stored.signature() != null && !stored.signature().endsWith("|" + currentFacts);
        List<AdmissionItem> items = AdmissionPolicy.sort(AdmissionPolicy.markForced(
                stored.items(), order.isForcedRelease()));
        return new DeployViews.AdmissionSnapshot(items, stored.checkedAt(), stale, stored.signature(),
                DeployViews.AdmissionSnapshot.legacyOf(items), stored.gate(),
                DeployViews.AdmissionCounts.of(items), 0, items.size() < AdmissionItem.ORDER.size());
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
     * 准入第 3 项（AC-8.1）：提报审批与评审审批都必须在同一条需求链上 APPROVED。
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
        payload.put("counts", result.counts());
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
