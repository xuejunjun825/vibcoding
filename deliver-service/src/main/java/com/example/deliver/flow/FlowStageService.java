package com.example.deliver.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.deliver.support.Rows;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage configuration: the seven-stage pipeline the board draws (AC-8).
 *
 * <p>Two rules live here and nowhere else. <b>Full save</b>: the request is the whole list, so a
 * missing row is a deletion (an id that is absent is removed, a row without an id is inserted).
 * <b>At least two enabled stages per requirement type</b>, otherwise the board could not describe
 * a flow at all — hence 409 {@code STAGE_MIN_REQUIRED}, which the page renders as "该类型至少要保留
 * 两个启用阶段".</p>
 *
 * <p>{@code stage_definition} is a tenant-level config table and is intentionally not part of
 * {@code ScopeSql} (it carries no domain/vendor column); every statement here pins
 * {@code tenant_id} explicitly. Reads for the board go through {@code ScopedJdbc}; this class is
 * the config surface.</p>
 */
@Service
public class FlowStageService {

    public static final String STAGE_MIN_REQUIRED = "STAGE_MIN_REQUIRED";
    public static final String STAGE_MAPPING_CONFLICT = "STAGE_MAPPING_CONFLICT";

    private static final Pattern CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,23}$");

    /** Requirement types the product ships with; used only to validate a configuration. */
    private static final List<String> DEFAULT_TYPES = List.of("BUS", "PDR", "RD");

    private static final String SELECT_COLUMNS = """
            SELECT id, code, name, order_no, mapping_json, req_types, enabled
            FROM stage_definition
            WHERE tenant_id = :tenantId
            ORDER BY order_no, id
            """;

    private static final List<String[]> DEFAULT_STAGES = List.of(
            new String[]{"SUBMIT", "提报", "1", "{\"enterEvents\":[\"REQUIREMENT_CREATED\"]}"},
            new String[]{"REVIEW", "评审", "2",
                    "{\"enterStatuses\":[\"APPROVING\",\"PENDING_REVIEW\"],\"leaveStatuses\":[\"POOLED\"]}"},
            new String[]{"SCHEDULE", "排期", "3",
                    "{\"enterStatuses\":[\"POOLED\",\"SCHEDULED\"],\"enterEvents\":[\"RELEASE_ATTACHED\"]}"},
            new String[]{"DEV", "开发", "4", "{\"enterStatuses\":[\"IN_DEV\"]}"},
            new String[]{"TEST", "测试", "5", "{\"enterStatuses\":[\"PENDING_TEST\"]}"},
            new String[]{"RELEASE", "发布", "6",
                    "{\"enterStatuses\":[\"TEST_PASSED\",\"MERGED\",\"DELIVERED\"]}"},
            new String[]{"DELIVERY", "交付", "7",
                    "{\"enterStatuses\":[\"DONE\",\"ACCEPTED_CLOSED\",\"CLOSED\"]}"});

    private static final String INSERT = """
            INSERT INTO stage_definition
                (tenant_id, code, name, order_no, mapping_json, req_types, enabled,
                 created_at, updated_at, updated_by)
            VALUES
                (:tenantId, :code, :name, :orderNo, :mappingJson, :reqTypes, :enabled,
                 CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), :userId)
            """;

    private static final String UPDATE = """
            UPDATE stage_definition
               SET code = :code, name = :name, order_no = :orderNo,
                   mapping_json = :mappingJson, req_types = :reqTypes, enabled = :enabled,
                   updated_at = CURRENT_TIMESTAMP(3), updated_by = :userId
             WHERE id = :id AND tenant_id = :tenantId
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper mapper = new ObjectMapper();

    public FlowStageService(NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    /** Every configured stage, ordered as the board draws them; seeds the seven defaults once. */
    @Transactional
    public List<FlowViews.StageView> list() {
        long tenantId = TenantContext.require().tenantId();
        ensureDefaults(tenantId);
        return read(tenantId);
    }

    /** The stages the board may draw: disabled stages are gone from the columns and the metrics. */
    public List<FlowViews.StageView> enabled() {
        List<FlowViews.StageView> stages = new ArrayList<>();
        for (FlowViews.StageView stage : list()) {
            if (stage.enabled()) {
                stages.add(stage);
            }
        }
        return stages;
    }

    private List<FlowViews.StageView> read(long tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT_COLUMNS, Map.of("tenantId", tenantId));
        List<FlowViews.StageView> stages = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            stages.add(new FlowViews.StageView(
                    Rows.toLong(Rows.object(row, "id"), 0L),
                    Rows.str(row, "code", ""),
                    Rows.str(row, "name", ""),
                    (int) Rows.toLong(Rows.object(row, "order_no"), 0L),
                    jsonText(Rows.object(row, "mapping_json")),
                    Rows.str(row, "req_types"),
                    Rows.toLong(Rows.object(row, "enabled"), 0L) == 1L));
        }
        return stages;
    }

    /** Some drivers hand JSON back as bytes; normalise before the mapper sees it. */
    private static String jsonText(Object value) {
        if (value == null) {
            return "{}";
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return value.toString();
    }

    // ------------------------------------------------------------------ full save

    /** Replace the tenant's stage list; validates, writes {@code STAGE_DEFINITION_UPDATE}. */
    @Transactional
    public List<FlowViews.StageView> save(List<FlowViews.StageCmd> commands) {
        long tenantId = TenantContext.require().tenantId();
        long userId = TenantContext.require().userId();
        if (commands == null || commands.isEmpty()) {
            throw new ApiException(400, "VALIDATION_FAILED", "stages 不能为空", null);
        }
        List<FlowViews.StageCmd> normalized = normalize(commands);
        validate(normalized);

        List<FlowViews.StageView> before = read(tenantId);
        Set<Long> kept = new LinkedHashSet<>();
        for (FlowViews.StageCmd cmd : normalized) {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("userId", userId)
                    .addValue("code", cmd.code())
                    .addValue("name", cmd.name())
                    .addValue("orderNo", cmd.orderNo())
                    .addValue("mappingJson", cmd.mappingJson())
                    .addValue("reqTypes", blankToNull(cmd.reqTypes()))
                    .addValue("enabled", Boolean.TRUE.equals(cmd.enabled()) ? 1 : 0);
            if (cmd.id() == null) {
                jdbc.update(INSERT, params);
            } else {
                params.addValue("id", cmd.id());
                int updated = jdbc.update(UPDATE, params);
                if (updated == 0) {
                    throw new ApiException(404, "STAGE_NOT_FOUND", "阶段不存在: " + cmd.id(),
                            Map.of("id", cmd.id()));
                }
                kept.add(cmd.id());
            }
        }
        List<Long> stale = new ArrayList<>();
        for (FlowViews.StageView stage : before) {
            if (!kept.contains(stage.id())) {
                stale.add(stage.id());
            }
        }
        if (!stale.isEmpty()) {
            jdbc.update("DELETE FROM stage_definition WHERE tenant_id = :tenantId AND id IN (:ids)",
                    new MapSqlParameterSource("tenantId", tenantId).addValue("ids", stale));
        }
        List<FlowViews.StageView> after = read(tenantId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", after);
        detail.put("by", userId);
        audit.record("STAGE_DEFINITION_UPDATE", "stage_definition", null, detail);
        return after;
    }

    private List<FlowViews.StageCmd> normalize(List<FlowViews.StageCmd> commands) {
        List<FlowViews.StageCmd> out = new ArrayList<>(commands.size());
        int index = 0;
        for (FlowViews.StageCmd cmd : commands) {
            index++;
            if (cmd == null) {
                throw new ApiException(400, "VALIDATION_FAILED", "stages 含空项", null);
            }
            String code = cmd.code() == null ? "" : cmd.code().trim();
            if (!CODE.matcher(code).matches()) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "阶段编码必须以字母开头且只含字母/数字/下划线（≤24 字符）: " + code,
                        Map.of("code", code));
            }
            String name = cmd.name() == null ? "" : cmd.name().trim();
            if (name.isEmpty()) {
                throw new ApiException(400, "VALIDATION_FAILED", "阶段名称不能为空: " + code,
                        Map.of("code", code));
            }
            String mapping = cmd.mappingJson() == null || cmd.mappingJson().isBlank()
                    ? "{}" : cmd.mappingJson().trim();
            parseMapping(mapping, code);
            out.add(new FlowViews.StageCmd(cmd.id(), code, name,
                    cmd.orderNo() == null || cmd.orderNo() <= 0 ? index : cmd.orderNo(),
                    cmd.enabled() == null || cmd.enabled(),
                    blankToNull(cmd.reqTypes()), mapping));
        }
        return out;
    }

    private void validate(List<FlowViews.StageCmd> stages) {
        Set<String> codes = new LinkedHashSet<>();
        Set<Integer> orders = new LinkedHashSet<>();
        for (FlowViews.StageCmd stage : stages) {
            if (!codes.add(stage.code())) {
                throw new ApiException(400, "VALIDATION_FAILED", "阶段编码重复: " + stage.code(),
                        Map.of("code", stage.code()));
            }
            if (!orders.add(stage.orderNo())) {
                throw new ApiException(400, "VALIDATION_FAILED", "阶段排序号重复: " + stage.orderNo(),
                        Map.of("orderNo", stage.orderNo()));
            }
        }
        Set<String> types = new LinkedHashSet<>();
        for (FlowViews.StageCmd stage : stages) {
            types.addAll(splitTypes(stage.reqTypes()));
        }
        if (types.isEmpty()) {
            types.addAll(DEFAULT_TYPES);
        }
        for (String type : types) {
            int enabled = 0;
            Map<String, String> statusOwner = new LinkedHashMap<>();
            for (FlowViews.StageCmd stage : stages) {
                if (!Boolean.TRUE.equals(stage.enabled()) || !appliesTo(stage.reqTypes(), type)) {
                    continue;
                }
                enabled++;
                for (String status : parseMapping(stage.mappingJson(), stage.code()).enterStatuses()) {
                    String previous = statusOwner.putIfAbsent(status, stage.code());
                    if (previous != null) {
                        throw new ApiException(400, STAGE_MAPPING_CONFLICT,
                                "状态 " + status + " 同时映射到 " + previous + " 与 " + stage.code(),
                                Map.of("status", status, "stages", List.of(previous, stage.code())));
                    }
                }
            }
            if (enabled < 2) {
                throw new ApiException(409, STAGE_MIN_REQUIRED,
                        "需求类型 " + type + " 至少需要两个启用阶段（当前 " + enabled + " 个）",
                        Map.of("reqType", type, "enabled", enabled));
            }
        }
    }

    private void ensureDefaults(long tenantId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_definition WHERE tenant_id = :tenantId",
                Map.of("tenantId", tenantId), Long.class);
        if (count != null && count > 0) {
            return;
        }
        for (String[] row : DEFAULT_STAGES) {
            try {
                jdbc.update(INSERT, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("userId", TenantContext.require().userId())
                        .addValue("code", row[0])
                        .addValue("name", row[1])
                        .addValue("orderNo", Integer.parseInt(row[2]))
                        .addValue("mappingJson", row[3])
                        .addValue("reqTypes", null)
                        .addValue("enabled", 1));
            } catch (DataIntegrityViolationException concurrent) {
                // Another request seeded the same (tenant, code) first; the unique key did its job.
            }
        }
    }

    // ------------------------------------------------------------------ shared semantics

    /** Parsed {@code mapping_json}: entry statuses/events plus the optional leave statuses. */
    public record Mapping(Set<String> enterStatuses, Set<String> enterEvents,
                          Set<String> leaveStatuses) {

        public boolean entersByStatus(String status) {
            return status != null && enterStatuses.contains(status);
        }

        public boolean entersByEvent(String event) {
            return event != null && enterEvents.contains(event);
        }
    }

    public static Mapping mapping(FlowViews.StageView stage) {
        return parseMapping(stage.mappingJson(), stage.code());
    }

    public static Mapping parseMapping(String json, String code) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node;
        try {
            node = json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception invalid) {
            throw new ApiException(400, "VALIDATION_FAILED",
                    "阶段 " + code + " 的 mappingJson 不是合法 JSON", Map.of("code", code));
        }
        if (node == null || !node.isObject()) {
            throw new ApiException(400, "VALIDATION_FAILED",
                    "阶段 " + code + " 的 mappingJson 必须是对象", Map.of("code", code));
        }
        return new Mapping(texts(node, "enterStatuses"), texts(node, "enterEvents"),
                texts(node, "leaveStatuses"));
    }

    private static Set<String> texts(JsonNode node, String field) {
        Set<String> values = new LinkedHashSet<>();
        JsonNode array = node.get(field);
        if (array == null || !array.isArray()) {
            return values;
        }
        for (JsonNode item : array) {
            if (item != null && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }
        return values;
    }

    /** Whether a stage participates for a requirement type; blank {@code reqTypes} means "all". */
    public static boolean applicable(FlowViews.StageView stage, String reqType) {
        return reqType == null || appliesTo(stage.reqTypes(), reqType);
    }

    static boolean appliesTo(String reqTypes, String reqType) {
        if (reqType == null) {
            return true;
        }
        List<String> types = splitTypes(reqTypes);
        if (types.isEmpty()) {
            return true;
        }
        for (String type : types) {
            if (type.equalsIgnoreCase(reqType)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> splitTypes(String reqTypes) {
        List<String> types = new ArrayList<>();
        if (reqTypes == null || reqTypes.isBlank()) {
            return types;
        }
        for (String part : reqTypes.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                types.add(trimmed);
            }
        }
        return types;
    }

    /**
     * Semantic role used by the efficiency formula. Codes are tenant-editable, so the role is
     * recognised from the code <em>or</em> the display name — a rename in the config page must not
     * silently drop development and test from the KPI.
     */
    public static String roleOf(FlowViews.StageView stage) {
        String code = stage.code() == null ? "" : stage.code().toUpperCase();
        String name = stage.name() == null ? "" : stage.name();
        if (code.equals("DEV") || code.equals("DEVELOP") || code.equals("DEVELOPMENT")
                || code.equals("DEVELOPING") || code.equals("IN_DEV") || name.contains("开发")) {
            return "DEV";
        }
        if (code.equals("TEST") || code.equals("TESTING") || code.equals("QA")
                || name.contains("测试")) {
            return "TEST";
        }
        if (code.equals("DELIVERY") || code.equals("DELIVER") || code.equals("DELIVERED")
                || name.contains("交付")) {
            return "DELIVERY";
        }
        return "OTHER";
    }

    /** The configured stage playing a role, or {@code null} when the tenant removed it. */
    public static String codeForRole(List<FlowViews.StageView> stages, String role) {
        for (FlowViews.StageView stage : stages) {
            if (role.equals(roleOf(stage))) {
                return stage.code();
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
