package com.example.req.release;

import java.util.List;
import java.util.Map;

/**
 * 发版链路（{@code flowChain}）与配套只读视图的<b>唯一字段契约</b>（r30 / AC-8）。
 *
 * <p>为什么所有跨层字段先落在这里：AC-8 要求「一次 GET 版本详情拿全四段链路 + 逐级审批 + 驳回」，
 * AC-9 又用静态检索断言前端不自行判定。键名一旦各层自由发挥（{@code testPlan} /
 * {@code test_plan} / {@code plan}），前端就得写三套兜底，gate 与签名的口径也会跟着漂。
 * 所以 record 的组件名就是 JSON 键名，deliver 的事实、req 的聚合、Vue 的类型都从这里抄。</p>
 *
 * <p>四段一律是<b>对象</b>，缺数据时 {@code status="MISSING"} + {@code reason}，
 * 不允许 null 段（DESIGN §4 反模式：空段显示成通过 / 整段隐藏）。</p>
 */
public final class FlowChainViews {

    /** 段状态：无数据。**不是通过**，也不是空态。 */
    public static final String SEGMENT_MISSING = "MISSING";
    /** 段状态：放行。 */
    public static final String SEGMENT_PASS = "PASS";
    /** 段状态：明确不过。 */
    public static final String SEGMENT_FAIL = "FAIL";
    /** 段状态：还在等（计划待批 / 签署未齐 / 发布单草稿）。 */
    public static final String SEGMENT_PENDING = "PENDING";

    /** 审批级状态：已完成、当前级、被驳回、还没轮到。 */
    public static final String LEVEL_APPROVED = "APPROVED";
    public static final String LEVEL_ACTIVE = "ACTIVE";
    public static final String LEVEL_REJECTED = "REJECTED";
    public static final String LEVEL_WAITING = "WAITING";

    private FlowChainViews() {
    }

    /**
     * 四段链路 + 段 digest。
     *
     * <p>{@code digests} 只用于快照的段级 stale 比较（16 位 hex 片段），算法细节不对外承诺；
     * 它由 {@code FlowChainService} 算一次，快照捕获与详情读取都复用，绝不在快照侧另算一套。</p>
     */
    public record FlowChain(String releaseLevel,
                            TestPlanSegment testPlan,
                            SignoffSegment signoff,
                            QualityGateSegment qualityGate,
                            DeployAdmissionSegment deployAdmission,
                            Map<String, String> digests) {

        public FlowChain {
            digests = digests == null ? Map.of() : Map.copyOf(digests);
        }

        /** 五参便捷构造：digest 由聚合层随后补上时使用（不允许出现 null digests）。 */
        public FlowChain(String releaseLevel, TestPlanSegment testPlan, SignoffSegment signoff,
                         QualityGateSegment qualityGate, DeployAdmissionSegment deployAdmission) {
            this(releaseLevel, testPlan, signoff, qualityGate, deployAdmission, Map.of());
        }
    }

    /** ① 测试计划段：该版本的测试计划是否都已批准（deliver 拥有事实）。 */
    public record TestPlanSegment(String status,
                                 String reason,
                                 int total,
                                 int approved,
                                 int pending,
                                 boolean allApproved,
                                 List<PlanLine> plans) {

        public TestPlanSegment {
            plans = plans == null ? List.of() : List.copyOf(plans);
        }

        /** 缺段：照常占位，只带原因。 */
        public static TestPlanSegment missing(String reason) {
            return new TestPlanSegment(SEGMENT_MISSING, reason, 0, 0, 0, false, List.of());
        }
    }

    /** 计划行：只放面板需要的三列（状态与最新轮结论），不搬整张 plan。 */
    public record PlanLine(String code, String status, String conclusion) {
    }

    /** ② 报告三签段：最新一份 RELEASE 报告的签署进度。 */
    public record SignoffSegment(String status,
                                String reason,
                                Long reportId,
                                Integer versionNo,
                                String conclusion,
                                List<SignoffLevel> levels,
                                int approved,
                                int total,
                                String pendingRole) {

        public SignoffSegment {
            levels = levels == null ? List.of() : List.copyOf(levels);
        }

        public static SignoffSegment missing(String reason) {
            return new SignoffSegment(SEGMENT_MISSING, reason, null, null, null, List.of(), 0, 0, null);
        }
    }

    /** 签署级：orderNo 是「第几个签」（1/2/3），roleName 是给人看的名字。 */
    public record SignoffLevel(Integer orderNo,
                               String signRole,
                               String roleName,
                               String status,
                               String signerName,
                               String signedAt) {
    }

    /** ③ 质量门段：deliver 固化的门快照事实（req 只透传，不按签署数自行推 PASS）。 */
    public record QualityGateSegment(String status,
                                    String reason,
                                    String result,
                                    Long snapshotId,
                                    Long gateId,
                                    Long reportId,
                                    String computedAt,
                                    List<RuleLine> failedRules,
                                    List<Map<String, Object>> waivers) {

        public QualityGateSegment {
            failedRules = failedRules == null ? List.of() : List.copyOf(failedRules);
            waivers = waivers == null ? List.of() : List.copyOf(waivers);
        }

        public static QualityGateSegment missing(String reason) {
            return new QualityGateSegment(SEGMENT_MISSING, reason, null, null, null, null, null,
                    List.of(), List.of());
        }
    }

    /** 未过规则行：与 {@code GateSnapshot.GateRuleResult} 同构的四个展示字段。 */
    public record RuleLine(String code, String status, boolean required,
                           String actual, String threshold, String fixHint) {
    }

    /** ④ 发布单准入段：最新一张发布单的 9 项准入（frozen = 读的是提交时冻结的快照）。 */
    public record DeployAdmissionSegment(String status,
                                        String reason,
                                        Long deployOrderId,
                                        String code,
                                        String orderStatus,
                                        boolean frozen,
                                        List<Map<String, Object>> items,
                                        int passedCount,
                                        int failedCount) {

        public DeployAdmissionSegment {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static DeployAdmissionSegment missing(String reason) {
            return new DeployAdmissionSegment(SEGMENT_MISSING, reason, null, null, null, false,
                    List.of(), 0, 0);
        }
    }

    /** 逐级审批阶梯的一行：长度等于定义节点数，顺序等于定义节点顺序（AC-2）。 */
    public record ApprovalLevel(int index,
                                String nodeKey,
                                String nodeName,
                                String mode,
                                String status,
                                List<Long> approvers,
                                String handledAt,
                                String comment) {

        public ApprovalLevel {
            approvers = approvers == null ? List.of() : List.copyOf(approvers);
        }
    }

    /** 快照回执：payloadHash 是提交时整份链路的 sha256（12 位展示，全文进 title）。 */
    public record FlowSnapshotView(int round,
                                   String capturedAt,
                                   String releaseLevel,
                                   Long definitionId,
                                   Integer definitionVersion,
                                   List<String> nodeKeys,
                                   FlowChain chain,
                                   String payloadHash,
                                   Map<String, Boolean> segmentStale,
                                   boolean changedSinceSubmit) {

        public FlowSnapshotView {
            nodeKeys = nodeKeys == null ? List.of() : List.copyOf(nodeKeys);
            segmentStale = segmentStale == null ? Map.of() : Map.copyOf(segmentStale);
        }
    }

    /** 快照列表项（只够画轮次条）。 */
    public record FlowSnapshotSummary(int round, String capturedAt, String payloadHash) {
    }

    /** 绑定槽：GET /admin/flow-bindings 的一行，恒 6 条（未绑定时 definitionId=null）。 */
    public record BindingSlot(String bizType,
                              String releaseLevel,
                              String releaseLevelLabel,
                              Long definitionId,
                              String definitionName,
                              Integer definitionVersion,
                              String status,
                              boolean isDefault,
                              List<NodeLine> nodes,
                              String updatedAt) {

        public BindingSlot {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }
    }

    /** 绑定槽里的节点链展示行。 */
    public record NodeLine(String key, String name, String mode) {
    }
}
