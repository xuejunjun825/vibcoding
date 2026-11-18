package com.example.req.seed;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.req.approval.WorkflowDefinition;
import com.example.req.approval.WorkflowJson;
import com.example.req.release.ReleaseLevel;

/**
 * r30 分级审批链的种子：<b>4 条分级定义 + 6 个默认绑定</b>，全部走 ensure 语义（命中即跳过）。
 *
 * <p>为什么单独一个 Service 而不是塞进 {@link SeedService}：分级链是「配置数据」，
 * 存量租户升级时只补这一块（{@code POST /internal/seed} 末尾调用本类），
 * 与业务演示数据（需求/任务/测试计划）的生命周期不同——放同一个类里，
 * 读代码的人分不清哪些行删了能重建、哪些是配置。</p>
 *
 * <p><b>版本号不是 1</b>：{@code wf_definition} 的唯一键是
 * {@code (tenant_id, biz_type, req_type, domain_id, version)}，而 {@link SeedService} 早已为
 * RELEASE / REQ_DEPLOY 种了 {@code req_type='ANY', domain_id=0, version=1} 的单节点默认定义。
 * 再用 version=1 会直接撞 {@code uk_wf_def}（Duplicate entry），所以分级定义取 2/3/4；
 * 老的单节点定义<b>不删不覆盖</b>（在途实例钉着它的 id，删了历史审批就打不开了），
 * 它仍可被绑定页手工选中，只是不再是兜底默认（{@code WorkflowVersionRepository.defaultCandidates}
 * 按 {@code version DESC} 排序，v2 自然胜出）。</p>
 *
 * <p>为什么 {@code REQ_DEPLOY} 三个等级共用一条定义：三个槽默认指向同一张三节点链
 * （PMO → 平台管理员），槽仍然可以各自改；共用一条定义意味着「改一处、三槽同源」，
 * 不会出现紧急发布单单据走的是另一份副本。</p>
 */
@Service
public class FlowChainSeedService {

    private static final Logger log = LoggerFactory.getLogger(FlowChainSeedService.class);

    /** 版本发布单据的流程业务类型（与 {@code WorkflowVersion.BIZ_RELEASE} 同值）。 */
    public static final String BIZ_RELEASE = "RELEASE";
    /** 发布单（部署单）的流程业务类型（与 {@code WorkflowVersion.BIZ_REQ_DEPLOY} 同值）。 */
    public static final String BIZ_REQ_DEPLOY = "REQ_DEPLOY";

    /** 不限类型/域（唯一键需非 NULL 值）。 */
    private static final String ANY_REQ_TYPE = "ANY";
    private static final long ANY_DOMAIN = 0L;

    /** 一条分级链的定义：名字 + 版本 + 节点链；{@code level=null} 表示该 bizType 下所有等级共用。 */
    public record ChainDefinition(String bizType, String level, String name, int version,
                                  List<WorkflowDefinition.Node> nodes) {
    }

    /** 默认链：顺序即 GET /admin/flow-bindings 的展示顺序（RELEASE 三级 → REQ_DEPLOY 三级）。 */
    public static final List<ChainDefinition> DEFAULTS = List.of(
            new ChainDefinition(BIZ_RELEASE, ReleaseLevel.NORMAL, "版本发布-常规三级", 2, List.of(
                    approval("n1", "产品经理会签", WorkflowDefinition.MODE_ALL, "PRODUCT", "n2"),
                    approval("n2", "PMO 会签", WorkflowDefinition.MODE_ALL, "PMO", "n3"),
                    approval("n3", "管理层", WorkflowDefinition.MODE_ANY, "MGMT", WorkflowDefinition.END))),
            new ChainDefinition(BIZ_RELEASE, ReleaseLevel.EMERGENCY, "版本发布-紧急两级", 3, List.of(
                    approval("n1", "产品经理会签", WorkflowDefinition.MODE_ALL, "PRODUCT", "n2"),
                    approval("n2", "管理层", WorkflowDefinition.MODE_ANY, "MGMT", WorkflowDefinition.END))),
            new ChainDefinition(BIZ_RELEASE, ReleaseLevel.ROLLBACK, "版本发布-回滚两级", 4, List.of(
                    approval("n1", "PMO 会签", WorkflowDefinition.MODE_ALL, "PMO", "n2"),
                    approval("n2", "平台管理员", WorkflowDefinition.MODE_ALL, "PLATFORM_ADMIN",
                            WorkflowDefinition.END))),
            // REQ_DEPLOY：三个等级共用这一条两级链（AC-1 允许“三级同链”）。
            new ChainDefinition(BIZ_REQ_DEPLOY, null, "发布单审批-两级", 2, List.of(
                    approval("n1", "PMO 会签", WorkflowDefinition.MODE_ALL, "PMO", "n2"),
                    approval("n2", "平台管理员", WorkflowDefinition.MODE_ALL, "PLATFORM_ADMIN",
                            WorkflowDefinition.END))));

    /** 6 个绑定槽：{@code bizType + level} → 默认定义名。 */
    private static final Map<String, String> DEFAULT_NAMES = defaultNames();

    private final JdbcTemplate jdbc;
    private final WorkflowJson json;

    public FlowChainSeedService(JdbcTemplate jdbc, WorkflowJson json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 幂等种入分级定义与 6 个默认绑定，返回<b>本次新插入</b>的行数（第二次调用恒为 0）。
     *
     * <p>已存在的定义与绑定一律不更新：定义「发布即固化」，覆盖旧行等于偷偷改在途实例的链路；
     * 绑定若被管理员改过，重跑种子不应把人的选择冲掉。要恢复默认请走
     * {@code DELETE /admin/flow-bindings/{bizType}/{level}}（或本地手工删行）。</p>
     */
    @Transactional
    public int seed(long tenantId, Long createdBy) {
        LocalDateTime now = LocalDateTime.now();
        int inserted = 0;
        Map<String, Long> definitionIdByName = new LinkedHashMap<>();
        for (ChainDefinition chain : DEFAULTS) {
            String definitionJson = json.write(new WorkflowDefinition(WorkflowDefinition.SUPPORTED_SCHEMA,
                    "n1", chain.nodes()));
            Map<String, Object> keys = row("tenant_id", tenantId, "biz_type", chain.bizType(),
                    "req_type", ANY_REQ_TYPE, "domain_id", ANY_DOMAIN, "version", chain.version());
            Ensured ensured = ensure("wf_definition", keys, row("name", chain.name(),
                    "definition_json", definitionJson, "status", "PUBLISHED",
                    "is_default", isDefaultChain(chain) ? 1 : 0,
                    "published_at", now, "published_by", createdBy,
                    "created_at", now, "created_by", createdBy));
            if (ensured.created()) {
                inserted++;
            }
            definitionIdByName.put(chain.bizType() + "|" + chain.name(), ensured.id());
        }
        for (String bizType : List.of(BIZ_RELEASE, BIZ_REQ_DEPLOY)) {
            for (String level : ReleaseLevel.ALL) {
                Long definitionId = definitionIdByName.get(bizType + "|" + defaultName(bizType, level));
                if (definitionId == null) {
                    continue;
                }
                Map<String, Object> keys = row("tenant_id", tenantId, "biz_type", bizType,
                        "release_level", level);
                Ensured ensured = ensure("wf_flow_binding", keys, row("definition_id", definitionId,
                        "created_at", now, "created_by", createdBy));
                if (ensured.created()) {
                    inserted++;
                }
            }
        }
        log.info("分级审批链种子完成：tenant={} 新增 {} 行", tenantId, inserted);
        return inserted;
    }

    /**
     * 该槽的默认定义名（{@link #DEFAULTS} 的常量映射，解析顺序第二步的依据）。
     *
     * <p>返回 null 表示该 bizType 没有默认配置——调用方（{@code FlowBindingService}）
     * 跳过第二步，直接退到「任一 PUBLISHED」。</p>
     */
    public static String defaultName(String bizType, String level) {
        return DEFAULT_NAMES.get(bizType + "|" + level);
    }

    /** 该 bizType 是否受分级绑定管理（绑定页只列这两类）。 */
    public static boolean supportsBizType(String bizType) {
        return BIZ_RELEASE.equals(bizType) || BIZ_REQ_DEPLOY.equals(bizType);
    }

    /** 该 bizType 下是否出现过分级定义（升级脚本据此判断“先跑种子”）。 */
    public boolean hasLevelDefinition(long tenantId, String bizType) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wf_definition WHERE tenant_id = ? AND biz_type = ? AND version > 1",
                Long.class, tenantId, bizType);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 是否把该定义标为 bizType 的「兜底默认」：只有每类的第一条（RELEASE 常规三级 / REQ_DEPLOY 两级）。
     *
     * <p>为什么不是全标 1：{@code WorkflowVersionRepository.defaultCandidates} 只会被别的模块
     * （需求审批、旧客户端不传 definitionId 时）当作兜底，多个 is_default 会让「兜底是哪条」取决于
     * 排序细节；留一个明确的默认更可预期。三个槽的解析本来就有绑定行，不依赖这个标记。</p>
     */
    private static boolean isDefaultChain(ChainDefinition chain) {
        return chain.name().equals(defaultName(chain.bizType(), ReleaseLevel.NORMAL))
                && (chain.level() == null || ReleaseLevel.NORMAL.equals(chain.level()));
    }

    private static Map<String, String> defaultNames() {
        Map<String, String> names = new LinkedHashMap<>();
        for (ChainDefinition chain : DEFAULTS) {
            String bizType = chain.bizType();
            List<String> levels = chain.level() == null ? ReleaseLevel.ALL : List.of(chain.level());
            for (String level : levels) {
                names.put(bizType + "|" + level, chain.name());
            }
        }
        return Map.copyOf(names);
    }

    private static WorkflowDefinition.Node approval(String key, String name, String mode,
                                                    String roleCode, String next) {
        return new WorkflowDefinition.Node(key, WorkflowDefinition.TYPE_APPROVAL, name, mode,
                List.of(new WorkflowDefinition.Approver(WorkflowDefinition.Approver.TYPE_ROLE, roleCode, null)),
                List.of(), null, next, List.of());
    }

    /**
     * 按业务键幂等插入：{@code created=true} 表示这次真的写了行（计数用），{@code id} 无论命中/新插都可用。
     *
     * <p>用 record 而不是「返回 id、-1 表示已存在」的整数约定：那个约定在调用方几乎必然被写反
     * （把「已存在」当成「新增」），而 AC-4 的升级脚本正是拿这个计数断言"第二次不增"。</p>
     */
    private record Ensured(long id, boolean created) {
    }

    private Ensured ensure(String table, Map<String, Object> keys, Map<String, Object> extra) {
        Optional<Long> existing = findId(table, keys);
        if (existing.isPresent()) {
            return new Ensured(existing.get(), false);
        }
        Map<String, Object> cols = new LinkedHashMap<>(keys);
        cols.putAll(extra);
        cols.keySet().retainAll(columnsOf(table));
        String names = String.join(", ", cols.keySet());
        String marks = cols.keySet().stream().map(c -> "?").reduce((a, b) -> a + ", " + b).orElse("");
        String sql = "INSERT INTO `" + table + "` (" + names + ") VALUES (" + marks + ")";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            for (Object value : cols.values()) {
                ps.setObject(i++, value);
            }
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) {
            throw new IllegalStateException("分级链种子插入后没有拿到自增 id: " + table + " " + keys);
        }
        return new Ensured(key.longValue(), true);
    }

    private Optional<Long> findId(String table, Map<String, Object> keys) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> entry : keys.entrySet()) {
            where.append(where.length() == 0 ? "" : " AND ").append(entry.getKey()).append(" = ?");
            args.add(entry.getValue());
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM `" + table + "` WHERE " + where + " LIMIT 1", Long.class, args.toArray());
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ?", String.class, table);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
