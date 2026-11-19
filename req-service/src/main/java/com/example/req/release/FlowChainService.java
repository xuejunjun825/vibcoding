package com.example.req.release;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.req.deploy.AdmissionItem;
import com.example.req.deploy.AdmissionEvaluator;
import com.example.req.deploy.DeployOrderEntity;
import com.example.req.deploy.DeployOrderRepository;
import com.example.req.deploy.DeployOrderStates;
import com.example.req.deploy.DeployViews;
import com.example.req.integration.DeliverFlowEvidenceClient;
import com.example.req.requirement.Rows;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * r30 / AC-8 / AC-9：版本「发版链路」四段的<b>唯一聚合点</b>。
 *
 * <p>四段各自的属主：① 测试计划、② 报告三签、③ 质量门在 deliver（一次内部调用批量取事实）；
 * ④ 发布单准入在 req（最新一张单 + 准入判定）。这里只做三件事：拉事实、折成
 * {@link FlowChainViews} 的固定契约、给每段算一个 digest。<b>不做任何判定</b>——
 * 计划是否批准由计划的 status 说、门是否通过由 {@code GateSnapshot.result} 说、
 * 准入是否满足由 {@code AdmissionPolicy} 说；聚合层再判一次就会让详情页与提交闸门各说各话。</p>
 *
 * <p>缺段语义（DESIGN §4）：deliver 不可用 → 前三段 {@code MISSING + reason}，整次详情仍 200。
 * 缺段绝不允许被渲染成通过，也不允许返回 null 段。</p>
 *
 * <p>digest 只在这里算一次：快照（T3）与详情复用同一个方法，快照侧若另算一套，
 * 段级 stale 比较就会永远"变了"或永远"没变"。</p>
 */
@Service
public class FlowChainService {

    private static final String SEGMENT_MISSING = FlowChainViews.SEGMENT_MISSING;
    private static final String SEGMENT_PASS = FlowChainViews.SEGMENT_PASS;
    private static final String SEGMENT_FAIL = FlowChainViews.SEGMENT_FAIL;
    private static final String SEGMENT_PENDING = FlowChainViews.SEGMENT_PENDING;

    /** {@code reason} 上限：设计稿只给 2 行，堆栈式长文案在页面上没有价值。 */
    private static final int REASON_MAX = 160;
    private static final int DIGEST_HEX = 16;

    private final ScopedJdbc jdbcRead;
    private final DeliverFlowEvidenceClient evidenceClient;
    private final DeployOrderRepository deployOrders;
    private final AdmissionEvaluator admission;

    /**
     * 摘要用的规范 JSON：属性名按字母序、Map 键排序——同一份链路在两次读取里必须得到同一个 digest，
     * 与 record 组件的声明顺序、deliver 返回的 Map 顺序都无关。放在本类内部而非注入共享 bean，
     * 避免别处改全局 ObjectMapper 配置时悄悄改掉 stale 的判定口径。
     */
    private final JsonMapper canonical = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public FlowChainService(ScopedJdbc jdbcRead,
                            DeliverFlowEvidenceClient evidenceClient,
                            DeployOrderRepository deployOrders,
                            AdmissionEvaluator admission) {
        this.jdbcRead = jdbcRead;
        this.evidenceClient = evidenceClient;
        this.deployOrders = deployOrders;
        this.admission = admission;
    }

    // ------------------------------------------------------------------ 聚合

    /** 一次 GET 版本详情要的全部链路事实（AC-8）。 */
    @Transactional(readOnly = true)
    public FlowChainViews.FlowChain assemble(long releaseId) {
        DataScope scope = TenantContext.require();
        long tenantId = scope.tenantId();
        String releaseLevel = releaseLevel(releaseId);
        List<Long> requirementIds = requirementIds(tenantId, releaseId);

        FlowChainViews.TestPlanSegment testPlan;
        FlowChainViews.SignoffSegment signoff;
        FlowChainViews.QualityGateSegment qualityGate;
        try {
            DeliverFlowEvidenceClient.Evidence evidence = evidenceClient.fetch(releaseId, requirementIds);
            testPlan = testPlan(evidence.testPlan());
            signoff = signoff(evidence.signoff());
            qualityGate = qualityGate(evidence.qualityGate());
        } catch (DeliverFlowEvidenceClient.EvidenceUnavailable ex) {
            String reason = trim("deliver 证据不可用：" + ex.getMessage());
            testPlan = FlowChainViews.TestPlanSegment.missing(reason);
            signoff = FlowChainViews.SignoffSegment.missing(reason);
            qualityGate = FlowChainViews.QualityGateSegment.missing(reason);
        }

        FlowChainViews.DeployAdmissionSegment deployAdmission = deployAdmission(tenantId, releaseId);
        FlowChainViews.FlowChain bare = new FlowChainViews.FlowChain(releaseLevel, testPlan, signoff,
                qualityGate, deployAdmission);
        return new FlowChainViews.FlowChain(bare.releaseLevel(), bare.testPlan(), bare.signoff(),
                bare.qualityGate(), bare.deployAdmission(), digests(bare));
    }

    /**
     * 段级 digest：键是四段名（{@code testPlan/signoff/qualityGate/deployAdmission}），值取 sha256 的
     * 前 16 位 hex。**唯一计算点**——快照捕获与详情读取都调这里，别处不得另写一份。
     */
    public Map<String, String> digests(FlowChainViews.FlowChain chain) {
        return Map.of(
                "testPlan", digest(chain.testPlan()),
                "signoff", digest(chain.signoff()),
                "qualityGate", digest(chain.qualityGate()),
                "deployAdmission", digest(chain.deployAdmission()));
    }

    private String digest(Object segment) {
        try {
            byte[] json = canonical.writeValueAsString(segment).getBytes(StandardCharsets.UTF_8);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(json);
            StringBuilder hex = new StringBuilder(DIGEST_HEX);
            for (int i = 0; i < DIGEST_HEX / 2; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    // ------------------------------------------------------------------ ① 计划（deliver 事实）

    private static FlowChainViews.TestPlanSegment testPlan(Map<String, Object> segment) {
        if (segment == null) {
            return FlowChainViews.TestPlanSegment.missing("deliver 未返回测试计划段");
        }
        String status = segmentStatus(segment.get("status"), "测试计划段");
        if (status == null) {
            return FlowChainViews.TestPlanSegment.missing("测试计划段没有状态");
        }
        List<FlowChainViews.PlanLine> plans = new ArrayList<>();
        for (Map<String, Object> row : maps(segment.get("plans"))) {
            plans.add(new FlowChainViews.PlanLine(str(row, "code"), str(row, "status"),
                    str(row, "conclusion")));
        }
        return new FlowChainViews.TestPlanSegment(status, str(segment, "reason"),
                integer(segment.get("total"), 0), integer(segment.get("approved"), 0),
                integer(segment.get("pending"), 0), bool(segment.get("allApproved")), plans);
    }

    // ------------------------------------------------------------------ ② 三签（deliver 事实）

    private static FlowChainViews.SignoffSegment signoff(Map<String, Object> segment) {
        if (segment == null) {
            return FlowChainViews.SignoffSegment.missing("deliver 未返回报告三签段");
        }
        String status = segmentStatus(segment.get("status"), "报告三签段");
        if (status == null) {
            return FlowChainViews.SignoffSegment.missing("报告三签段没有状态");
        }
        List<FlowChainViews.SignoffLevel> levels = new ArrayList<>();
        for (Map<String, Object> row : maps(segment.get("levels"))) {
            Long orderNo = lng(row, "orderNo");
            levels.add(new FlowChainViews.SignoffLevel(orderNo == null ? null : orderNo.intValue(),
                    str(row, "signRole"), str(row, "roleName"), str(row, "status"),
                    str(row, "signerName"), str(row, "signedAt")));
        }
        return new FlowChainViews.SignoffSegment(status, str(segment, "reason"),
                lng(segment, "reportId"),
                segment.get("versionNo") == null ? null : integer(segment.get("versionNo"), 0),
                str(segment, "conclusion"), levels,
                integer(segment.get("approved"), 0), integer(segment.get("total"), 0),
                str(segment, "pendingRole"));
    }

    // ------------------------------------------------------------------ ③ 质量门（deliver 事实）

    private static FlowChainViews.QualityGateSegment qualityGate(Map<String, Object> segment) {
        if (segment == null) {
            return FlowChainViews.QualityGateSegment.missing("deliver 未返回质量门段");
        }
        String status = segmentStatus(segment.get("status"), "质量门段");
        if (status == null) {
            return FlowChainViews.QualityGateSegment.missing("质量门段没有状态");
        }
        List<FlowChainViews.RuleLine> failedRules = ruleLines(segment.get("failedRules"));
        List<FlowChainViews.RuleLine> skippedRules = ruleLines(segment.get("skippedRules"));
        List<Map<String, Object>> waivers = maps(segment.get("waivers"));
        return new FlowChainViews.QualityGateSegment(status, str(segment, "reason"),
                str(segment, "result"), lng(segment, "snapshotId"), lng(segment, "gateId"),
                lng(segment, "reportId"), str(segment, "computedAt"),
                failedRules, skippedRules, waivers,
                count(segment, "total"), count(segment, "passedCount"), count(segment, "waivedCount"),
                count(segment, "failedCount"), count(segment, "skippedCount"));
    }

    /** 规则行映射：deliver 的 {@code failedRules} / {@code skippedRules} 同构（门规则的展示子集）。 */
    private static List<FlowChainViews.RuleLine> ruleLines(Object value) {
        List<FlowChainViews.RuleLine> out = new ArrayList<>();
        for (Map<String, Object> row : maps(value)) {
            out.add(new FlowChainViews.RuleLine(str(row, "code"), str(row, "status"),
                    bool(row.get("required")), str(row, "actual"), str(row, "threshold"),
                    str(row, "fixHint")));
        }
        return out;
    }

    // ------------------------------------------------------------------ ④ 准入（req 自有）

    private FlowChainViews.DeployAdmissionSegment deployAdmission(long tenantId, long releaseId) {
        DeployOrderEntity order = latestOrder(tenantId, releaseId);
        if (order == null) {
            return FlowChainViews.DeployAdmissionSegment.missing("该版本还没有发布单");
        }
        try {
            if (DeployOrderStates.DRAFT.equals(order.getStatus())) {
                DeployViews.AdmissionResult live = admission.evaluate(order);
                boolean allPassed = live.allPassed();
                String status = live.items().isEmpty() ? SEGMENT_MISSING
                        : (allPassed ? SEGMENT_PENDING : SEGMENT_FAIL);
                String reason = live.items().isEmpty() ? "发布单准入清单为空"
                        : (allPassed ? "发布单草稿，9 项准入已满足，待提交"
                                : live.counts().failed() + " 项准入未通过（草稿实时评估）");
                return new FlowChainViews.DeployAdmissionSegment(status, reason, order.getId(),
                        order.getCode(), order.getStatus(), false, itemMaps(live.items()),
                        live.counts().passed(), live.counts().failed());
            }
            DeployViews.AdmissionSnapshot frozen = admission.snapshot(order);
            boolean allPassed = !frozen.items().isEmpty()
                    && frozen.items().stream().noneMatch(item -> AdmissionItem.FAIL.equals(item.status()));
            String status;
            String reason;
            if (frozen.items().isEmpty()) {
                status = SEGMENT_MISSING;
                reason = "发布单尚未评估准入（旧单据没有快照）";
            } else if (allPassed) {
                status = SEGMENT_PASS;
                reason = null;
            } else {
                status = SEGMENT_FAIL;
                reason = frozen.counts().failed() + " 项准入未通过（提交时冻结的快照）";
            }
            return new FlowChainViews.DeployAdmissionSegment(status, reason, order.getId(),
                    order.getCode(), order.getStatus(), true, itemMaps(frozen.items()),
                    frozen.counts().passed(), frozen.counts().failed());
        } catch (RuntimeException ex) {
            return FlowChainViews.DeployAdmissionSegment.missing(
                    trim("发布单准入不可读：" + ex.getClass().getSimpleName()
                            + (ex.getMessage() == null ? "" : "：" + ex.getMessage())));
        }
    }

    private DeployOrderEntity latestOrder(long tenantId, long releaseId) {
        // 与 DeployOrderService 的列表口径一致：调用者的可见域非空时必须按「版本的域」过滤，
        // 否则受限用户会在详情里看到别域发布单的准入结论（字段级越权）。
        DataScope scope = TenantContext.require();
        boolean domainFilter = scope.domainIds() != null && !scope.domainIds().isEmpty();
        List<Long> domainIds = domainFilter ? new ArrayList<>(scope.domainIds()) : List.of(-1L);
        List<DeployOrderEntity> page = deployOrders
                .search(tenantId, releaseId, "", "", domainFilter, domainIds, PageRequest.of(0, 1))
                .getContent();
        return page.isEmpty() ? null : page.get(0);
    }

    private static List<Map<String, Object>> itemMaps(List<AdmissionItem> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AdmissionItem item : items) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("code", item.code());
            row.put("name", item.name());
            row.put("order", item.order());
            row.put("status", item.status());
            row.put("passed", item.passed());
            row.put("detail", item.detail());
            row.put("fixHint", item.fixHint());
            row.put("reasonCode", item.reasonCode());
            row.put("evidenceHash", item.evidenceHash());
            row.put("evidence", item.evidence());
            row.put("forced", item.forced());
            out.add(row);
        }
        return out;
    }

    // ------------------------------------------------------------------ 本地读取

    private String releaseLevel(long releaseId) {
        Map<String, Object> row = jdbcRead.one("""
                SELECT r.release_level AS release_level
                FROM `release` r
                WHERE /*scope:r*/ AND r.id = :releaseId
                """, Map.of("releaseId", releaseId));
        // 详情入口已保证版本可见；这里仍然按"没有等级"处理，绝不让 null 行变成 500。
        return ReleaseLevel.normalise(row == null ? null : Rows.str(row, "release_level"));
    }

    /**
     * 版本下的需求集合：读路径走 {@link ScopedJdbc}（L9 读路径单点），JOIN 与 scope 标记与
     * {@code ReleaseService.requirementsOf} 同一条口径——受限用户只把自己看得见的需求交给
     * deliver 做计划覆盖求交，聚合总数不泄露别域需求。租户仍显式绑定（不只靠 scope 标记）。
     */
    private List<Long> requirementIds(long tenantId, long releaseId) {
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT rr.requirement_id AS requirement_id
                FROM release_requirement rr
                JOIN requirement r ON r.id = rr.requirement_id AND r.tenant_id = rr.tenant_id
                WHERE rr.tenant_id = :tenantId AND rr.release_id = :releaseId AND /*scope:r*/
                ORDER BY rr.requirement_id ASC
                """, Map.of("tenantId", tenantId, "releaseId", releaseId));
        List<Long> ids = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Long id = Rows.toLong(Rows.object(row, "requirement_id"));
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------ 解析工具

    /** 只接受契约里的四个段状态；未知/缺失一律 null（调用点按 MISSING 处理，绝不猜成 PASS）。 */
    private static String segmentStatus(Object raw, String what) {
        String status = raw == null ? null : String.valueOf(raw).trim().toUpperCase();
        if (status == null || status.isEmpty()) {
            return null;
        }
        return switch (status) {
            case SEGMENT_MISSING, SEGMENT_PASS, SEGMENT_FAIL, SEGMENT_PENDING -> status;
            default -> null;
        };
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long lng(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * 可空计数：段里没有这个键（旧快照）就返回 {@code null}。
     *
     * <p>PASS/WAIVED/FAIL 三个计数同理——「deliver 没给这个字段」与「真的是 0 条」必须可区分，
     * 否则回放旧快照会凭空多出「跳过 0 条」，把「没记录」说成「已确认没有」。</p>
     */
    private static Integer count(Map<String, Object> segment, String key) {
        Object value = segment.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    map.forEach((k, v) -> {
                        if (k != null) {
                            row.put(String.valueOf(k), v);
                        }
                    });
                    out.add(row);
                }
            }
        }
        return out;
    }

    private static String trim(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= REASON_MAX ? reason : reason.substring(0, REASON_MAX - 1) + "…";
    }
}
