package com.example.req.automation.spec;

import com.example.platform.contract.StateTables;
import com.example.req.requirement.RequirementRules;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * 条件与动作的封闭 schema 解析——自动化 JSON 的唯一入口。
 *
 * <p>为什么封闭：规则内容是用户数据，直接拿去求值等于把表达式语言交给用户。这里只认
 * 白名单键，未知键 400（{@code AUTOMATION_SCHEMA_INVALID}，detail.field 给出键名），
 * 因此不引入 SpEL / JS 也不会出现「用户能写出服务端不认识的规则」。</p>
 *
 * <p>语义：<b>数组内 OR、键之间 AND、字段缺失即不命中</b>。空数组按「不填」处理
 * （前端清空筛选后下发 {@code []}，语义应当是「不加这条限制」而不是「永远不命中」），
 * 解析时统一归一化成 null，求值方只需判 null。</p>
 *
 * <p>状态候选不是字面量表：{@link #statusCandidates()} 直接取
 * {@link StateTables#statuses(String)}，状态机加一个状态，规则条件与前端下拉同时跟着变。</p>
 */
public final class AutomationSpecs {

    /** 条件对象允许的键，顺序即 {@code /automation/meta} 的下发顺序。 */
    public static final List<String> CONDITION_KEYS = List.of(
            "types", "priorityIn", "statusIn", "approvalResults",
            "estimateDays", "domainIds", "vendorIds", "fields");

    /** 人天比较符；{@code eq} 与 {@code gte}+{@code lte} 都可表达「等于」。 */
    public static final List<String> ESTIMATE_OPS = List.of("gt", "gte", "lt", "lte", "eq");

    /** 审批结果，与审批单终态一致。 */
    public static final List<String> APPROVAL_RESULTS = List.of("APPROVED", "REJECTED", "WITHDRAWN");

    /**
     * 可被字段变更触发的字段白名单（列名，与 {@code requirement} 表一致）。
     *
     * <p>r40 从 4 个扩到 8 个：变更单可写的内容/日期/父需求字段也必须能被规则命中，
     * 否则「title 变了就通知」这种规则存不进去。{@code owner_user_id} 不属变更单字段，
     * 但它是既有可触发字段，保留。顺序即 {@code /automation/meta} 的候选顺序。</p>
     */
    public static final List<String> FIELDS = List.of(
            "title", "description", "priority", "owner_user_id",
            "estimate_days", "release_id", "parent_id", "expected_delivery_date");

    /** 优先级枚举，与 {@code RequirementService} 的校验保持一致。 */
    public static final List<String> PRIORITIES = List.of("P0", "P1", "P2", "P3");

    /** 人天取值区间与精度（天 0..1000，最多两位小数）。 */
    public static final BigDecimal MAX_ESTIMATE_DAYS = BigDecimal.valueOf(1000);

    private AutomationSpecs() {
    }

    /** 所有可作条件的 statusIn 候选（BUS/PDR/RD 三张状态表的并集，稳定顺序）。 */
    public static List<String> statusCandidates() {
        return statusCandidates(List.of());
    }

    /**
     * 条件候选取值：指定了 {@code types} 就取这些类型的状态并集，否则取全量并集。
     * 让「BUS 规则里填 RD 的状态」在保存时就被拒绝。
     */
    public static List<String> statusCandidates(Collection<String> types) {
        List<String> source = (types == null || types.isEmpty())
                ? List.of(RequirementRules.TYPE_BUS, RequirementRules.TYPE_PDR, RequirementRules.TYPE_RD)
                : List.copyOf(types);
        Set<String> merged = new LinkedHashSet<>();
        for (String type : source) {
            merged.addAll(StateTables.statuses(type));
        }
        return List.copyOf(merged);
    }

    /** 人天条件：{@code {"op":"gte","value":3.5}}（value 已去尾零，scale ≤ 2）。 */
    public record EstimateDays(String op, BigDecimal value) {
    }

    /**
     * 条件快照：所有字段可空，null = 不限制。
     *
     * @param types           需求类型（BUS/PDR/RD）OR
     * @param priorityIn      优先级 OR
     * @param statusIn        目标状态 OR（只校验合法性，比较的是流转后的 toStatus）
     * @param approvalResults 审批结果 OR
     * @param estimateDays    人天比较（单值）
     * @param domainIds       域 OR
     * @param vendorIds       厂商 OR
     * @param fields          只在这些字段变更时命中（FIELD_CHANGE 专用）
     */
    public record ConditionSpec(
            List<String> types,
            List<String> priorityIn,
            List<String> statusIn,
            List<String> approvalResults,
            EstimateDays estimateDays,
            List<Long> domainIds,
            List<Long> vendorIds,
            List<String> fields) {

        /** 无条件：{} 的解析结果 = 全命中。 */
        public static final ConditionSpec EMPTY =
                new ConditionSpec(null, null, null, null, null, null, null, null);

        public ConditionSpec {
            types = copy(types);
            priorityIn = copy(priorityIn);
            statusIn = copy(statusIn);
            approvalResults = copy(approvalResults);
            domainIds = copy(domainIds);
            vendorIds = copy(vendorIds);
            fields = copy(fields);
        }

        /** 是否有任何限制；false 时求值必然命中（仍会走作用域与限流）。 */
        public boolean isEmpty() {
            return types == null && priorityIn == null && statusIn == null && approvalResults == null
                    && estimateDays == null && domainIds == null && vendorIds == null && fields == null;
        }

        /**
         * 严格解析条件节点：null / {@code null} / {@code {}} 都等于无条件；
         * 任何未知键、越界枚举、错误类型都抛 400。
         */
        public static ConditionSpec parse(JsonNode node) {
            if (node == null || node.isNull()) {
                return EMPTY;
            }
            if (!node.isObject()) {
                throw AutomationErrors.schema("condition", "condition 必须是 JSON 对象");
            }
            for (String key : node.propertyNames()) {
                if (!CONDITION_KEYS.contains(key)) {
                    throw AutomationErrors.schema(key, "未知的条件键：" + key);
                }
            }
            List<String> types = strings(node, "types", AutomationSpecs::requireKnownType);
            List<String> priorityIn = strings(node, "priorityIn", AutomationSpecs::requirePriority);
            List<String> statusIn = strings(node, "statusIn",
                    value -> requireStatus(value, types));
            List<String> approvalResults = strings(node, "approvalResults", AutomationSpecs::requireApprovalResult);
            EstimateDays estimateDays = parseEstimateDays(node.get("estimateDays"));
            List<Long> domainIds = longs(node, "domainIds");
            List<Long> vendorIds = longs(node, "vendorIds");
            List<String> fields = strings(node, "fields", AutomationSpecs::requireField);
            if (types == null && priorityIn == null && statusIn == null && approvalResults == null
                    && estimateDays == null && domainIds == null && vendorIds == null && fields == null) {
                return EMPTY;
            }
            return new ConditionSpec(types, priorityIn, statusIn, approvalResults, estimateDays,
                    domainIds, vendorIds, fields);
        }
    }

    /** 通知收件人：USER 用 id，ROLE 用 code，DOMAIN_OWNER 两个都不填（按需求所属域解析）。 */
    public record Recipient(String kind, Long id, String code) {

        public static final String KIND_USER = "USER";
        public static final String KIND_ROLE = "ROLE";
        public static final String KIND_DOMAIN_OWNER = "DOMAIN_OWNER";
        public static final List<String> KINDS = List.of(KIND_USER, KIND_ROLE, KIND_DOMAIN_OWNER);

        static Recipient parse(JsonNode node, String field) {
            if (node == null || !node.isObject()) {
                throw AutomationErrors.schema(field, "recipients 的每一项必须是 JSON 对象");
            }
            for (String key : node.propertyNames()) {
                if (!"kind".equals(key) && !"id".equals(key) && !"code".equals(key)) {
                    throw AutomationErrors.schema(key, "收件人不允许的键：" + key);
                }
            }
            String kind = text(node.get("kind"));
            if (kind == null || !KINDS.contains(kind)) {
                throw AutomationErrors.schema("kind",
                        "收件人 kind 只能是 " + String.join("/", KINDS) + "，当前 " + kind);
            }
            Long id = node.get("id") == null ? null : positiveLong(node.get("id"), "id");
            String code = text(node.get("code"));
            if (KIND_USER.equals(kind)) {
                if (id == null) {
                    throw AutomationErrors.schema("id", "kind=USER 必须给出用户 id");
                }
            } else if (KIND_ROLE.equals(kind)) {
                if (code == null || code.isBlank()) {
                    throw AutomationErrors.schema("code", "kind=ROLE 必须给出角色 code");
                }
            } else if (id != null) {
                throw AutomationErrors.schema("id", "kind=DOMAIN_OWNER 不接受 id（按需求所属域解析）");
            }
            return new Recipient(kind, id, code);
        }
    }

    /**
     * 动作：四型之一，其余字段按类型封闭。
     *
     * @param type       NOTIFY / ASSIGN_OWNER / ADD_TAG / WEBHOOK
     * @param recipients NOTIFY 专用
     * @param userId     ASSIGN_OWNER 专用
     * @param tag        ADD_TAG 专用
     * @param configId   WEBHOOK 专用（integration_config.id）
     * @param path       WEBHOOK 专用（相对路径，绝不含 scheme/host）
     */
    public record ActionSpec(
            String type,
            List<Recipient> recipients,
            Long userId,
            String tag,
            Long configId,
            String path) {

        /** 打标签长度上限。 */
        public static final int MAX_TAG_LENGTH = 32;

        public ActionSpec {
            recipients = copy(recipients);
        }

        /** 解析动作数组：空 / 非数组 / >10 条 / 未知类型 / 越权类型 / 键不匹配，全部 400。 */
        public static List<ActionSpec> parseArray(JsonNode node) {
            if (node == null || node.isNull() || !node.isArray()) {
                throw AutomationErrors.schema("actions", "actions 必须是 JSON 数组");
            }
            if (node.isEmpty()) {
                throw AutomationErrors.emptyActions();
            }
            if (node.size() > AutomationErrors.MAX_ACTIONS) {
                throw AutomationErrors.tooManyActions(node.size());
            }
            List<ActionSpec> specs = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                specs.add(parseOne(item));
            }
            return List.copyOf(specs);
        }

        private static ActionSpec parseOne(JsonNode node) {
            if (node == null || !node.isObject()) {
                throw AutomationErrors.schema("actions", "actions 的每一项必须是 JSON 对象");
            }
            JsonNode typeNode = node.get("type");
            String type = text(typeNode == null ? null : typeNode);
            if (type == null) {
                throw AutomationErrors.schema("type", "每个动作必须给出 type");
            }
            AutomationActionType actionType = AutomationActionType.of(type);
            List<Recipient> recipients = null;
            Long userId = null;
            String tag = null;
            Long configId = null;
            String path = null;
            switch (actionType) {
                case NOTIFY -> {
                    requireOnlyKeys(node, Set.of("type", "recipients"));
                    JsonNode list = node.get("recipients");
                    if (list == null || !list.isArray() || list.isEmpty()) {
                        throw AutomationErrors.schema("recipients", "NOTIFY 至少需要一个收件人");
                    }
                    List<Recipient> parsed = new ArrayList<>(list.size());
                    for (JsonNode item : list) {
                        parsed.add(Recipient.parse(item, "recipients"));
                    }
                    recipients = List.copyOf(parsed);
                }
                case ASSIGN_OWNER -> {
                    requireOnlyKeys(node, Set.of("type", "userId"));
                    JsonNode candidate = node.get("userId");
                    if (candidate == null) {
                        throw AutomationErrors.schema("userId", "ASSIGN_OWNER 必须给出 userId");
                    }
                    userId = positiveLong(candidate, "userId");
                }
                case ADD_TAG -> {
                    requireOnlyKeys(node, Set.of("type", "tag"));
                    String raw = text(node.get("tag"));
                    if (raw == null || raw.isBlank()) {
                        throw AutomationErrors.schema("tag", "ADD_TAG 必须给出非空的 tag");
                    }
                    String trimmed = raw.trim();
                    if (trimmed.length() > MAX_TAG_LENGTH) {
                        throw AutomationErrors.schema("tag",
                                "tag 不能超过 " + MAX_TAG_LENGTH + " 个字符，当前 " + trimmed.length() + " 个");
                    }
                    tag = trimmed;
                }
                case WEBHOOK -> {
                    requireOnlyKeys(node, Set.of("type", "configId", "path"));
                    JsonNode config = node.get("configId");
                    if (config == null) {
                        throw AutomationErrors.schema("configId", "WEBHOOK 必须给出 configId");
                    }
                    configId = positiveLong(config, "configId");
                    String raw = text(node.get("path"));
                    if (raw == null || raw.isBlank()) {
                        throw AutomationErrors.schema("path", "WEBHOOK 必须给出 path");
                    }
                    String normalized = raw.trim();
                    if (!normalized.startsWith("/")) {
                        throw AutomationErrors.schema("path", "path 必须以 / 开头");
                    }
                    if (normalized.contains("://") || normalized.contains("..")) {
                        throw AutomationErrors.schema("path", "path 只能是相对路径，不能含 :// 或 ..");
                    }
                    if (normalized.length() > 300) {
                        throw AutomationErrors.schema("path", "path 不能超过 300 个字符");
                    }
                    path = normalized;
                }
                default -> throw AutomationErrors.actionNotAllowed(type);
            }
            return new ActionSpec(actionType.code(), recipients, userId, tag, configId, path);
        }

        private static void requireOnlyKeys(JsonNode node, Set<String> allowed) {
            for (String key : node.propertyNames()) {
                if (!allowed.contains(key)) {
                    throw AutomationErrors.schema(key, "该动作不接受键：" + key);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 字段级校验

    private static void requireKnownType(String value) {
        if (!RequirementRules.isKnownType(value)) {
            throw AutomationErrors.schema("types",
                    "types 只能是 " + String.join("/", List.of(RequirementRules.TYPE_BUS,
                            RequirementRules.TYPE_PDR, RequirementRules.TYPE_RD)) + "，当前 " + value);
        }
    }

    private static void requirePriority(String value) {
        if (!PRIORITIES.contains(value)) {
            throw AutomationErrors.schema("priorityIn",
                    "priorityIn 只能是 " + String.join("/", PRIORITIES) + "，当前 " + value);
        }
    }

    private static void requireStatus(String value, List<String> types) {
        if (!statusCandidates(types).contains(value)) {
            throw AutomationErrors.schema("statusIn", "未知的状态：" + value);
        }
    }

    private static void requireApprovalResult(String value) {
        if (!APPROVAL_RESULTS.contains(value)) {
            throw AutomationErrors.schema("approvalResults",
                    "approvalResults 只能是 " + String.join("/", APPROVAL_RESULTS) + "，当前 " + value);
        }
    }

    private static void requireField(String value) {
        if (!FIELDS.contains(value)) {
            throw AutomationErrors.schema("fields",
                    "fields 只能是 " + String.join("/", FIELDS) + "，当前 " + value);
        }
    }

    private static EstimateDays parseEstimateDays(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw AutomationErrors.schema("estimateDays", "estimateDays 必须是 JSON 对象");
        }
        for (String key : node.propertyNames()) {
            if (!"op".equals(key) && !"value".equals(key)) {
                throw AutomationErrors.schema(key, "estimateDays 不允许的键：" + key);
            }
        }
        String op = text(node.get("op"));
        if (op == null || !ESTIMATE_OPS.contains(op)) {
            throw AutomationErrors.schema("op",
                    "estimateDays.op 只能是 " + String.join("/", ESTIMATE_OPS) + "，当前 " + op);
        }
        JsonNode valueNode = node.get("value");
        if (valueNode == null || !valueNode.isNumber()) {
            throw AutomationErrors.schema("value", "estimateDays.value 必须是数字");
        }
        BigDecimal value = valueNode.decimalValue().stripTrailingZeros();
        if (value.scale() > 2) {
            throw AutomationErrors.schema("value", "estimateDays.value 最多两位小数");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(MAX_ESTIMATE_DAYS) > 0) {
            throw AutomationErrors.schema("value",
                    "estimateDays.value 必须在 0 到 " + MAX_ESTIMATE_DAYS.toPlainString() + " 之间");
        }
        return new EstimateDays(op, value);
    }

    /**
     * 字符串数组：元素必须是字符串并通过 {@code check}；空数组归一化为 null（= 不限制），
     * 重复值按首次出现去重（保持用户输入顺序，便于原样回显）。
     */
    private static List<String> strings(JsonNode node, String field, Consumer<String> check) {
        JsonNode array = node.get(field);
        if (array == null || array.isNull()) {
            return null;
        }
        if (!array.isArray()) {
            throw AutomationErrors.schema(field, field + " 必须是 JSON 数组");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : array) {
            String value = text(item);
            if (value == null || value.isBlank()) {
                throw AutomationErrors.schema(field, field + " 的元素必须是非空字符串");
            }
            String trimmed = value.trim();
            check.accept(trimmed);
            values.add(trimmed);
        }
        return values.isEmpty() ? null : List.copyOf(values);
    }

    /** 正整数数组（域 / 厂商 id）；空数组归一化为 null。 */
    private static List<Long> longs(JsonNode node, String field) {
        JsonNode array = node.get(field);
        if (array == null || array.isNull()) {
            return null;
        }
        if (!array.isArray()) {
            throw AutomationErrors.schema(field, field + " 必须是 JSON 数组");
        }
        Set<Long> values = new LinkedHashSet<>();
        for (JsonNode item : array) {
            values.add(positiveLong(item, field));
        }
        return values.isEmpty() ? null : List.copyOf(values);
    }

    private static Long positiveLong(JsonNode node, String field) {
        if (node == null || !node.isNumber()) {
            throw AutomationErrors.schema(field, field + " 必须是数字 id");
        }
        long value = node.asLong();
        if (value <= 0) {
            throw AutomationErrors.schema(field, field + " 必须是正整数");
        }
        return value;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.stringValue() : null;
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? null : List.copyOf(values);
    }
}
