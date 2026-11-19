package com.example.req.release;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.deploy.DeployOrderEntity;
import com.example.req.requirement.Rows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 提交快照的固化、轮次、回放与段级 stale（r30 / AC-10 / AC-11）。
 *
 * <p>三条不可动摇的规则，全部收在这一个类里：</p>
 * <ol>
 *   <li><b>只插不改</b>：写路径只有 {@link #capture}，读路径是 {@link #latest}/{@link #history}/{@link #replay}；
 *       没有 update、没有 delete、没有"重算快照"（AC-11 静态审计点）。</li>
 *   <li><b>hash 是存储字节的 hash</b>：{@code payload_json} 只序列化一次，{@code payload_hash} 直接取这份字符串的
 *       SHA-256。回放读的就是库里那串字节，绝不"反序列化后再序列化一遍"——那是 hash 漂移的经典来源（AC-10）。</li>
 *   <li><b>stale 由后端判定</b>：回放/详情时用当前链的段 digest 与快照里的 digest 逐段比较，
 *       前端只渲染 {@code segmentStale}，不自己比对（DESIGN §4 / AC-10）。</li>
 * </ol>
 *
 * <p>轮次连续性：{@code round_no = MAX+1}，并发由唯一键 {@code uk_flow_snapshot_round} 兜底
 * （第二条插入撞键 → 整个 submit 回滚，不会出现 1、3 空洞）。</p>
 */
@Service
public class FlowSnapshotService {

    /** 回放找不到轮次（AC-10：按轮次回放是只读入口，越界/不存在的轮次是 404，不是空 200）。 */
    public static final String SNAPSHOT_NOT_FOUND = "SNAPSHOT_NOT_FOUND";

    private static final String DEFAULT_LEVEL = "NORMAL";

    private final FlowSnapshotRepository repository;
    private final FlowChainService chainService;
    private final ScopedJdbc jdbcRead;
    private final ObjectMapper objectMapper;

    public FlowSnapshotService(FlowSnapshotRepository repository,
                               FlowChainService chainService,
                               ScopedJdbc jdbcRead,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.chainService = chainService;
        this.jdbcRead = jdbcRead;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 写（唯一一条）

    /**
     * 固化一轮快照。**必须在所有提交闸门通过之后调用**——拒绝路径（计划未批 / 未锁 / 门未过 / 准入未过）
     * 一旦提前走到这里，AC-11 的"拒绝不写快照、轮次不增"就直接破了。调用顺序（而不是 if 判断）是保证。
     *
     * <p>挂 {@code REQUIRED} 传播：与调用方的 submit 同生共死，快照写入与状态变更不会一半一半。</p>
     */
    @Transactional
    public FlowChainViews.FlowSnapshotView capture(DeployOrderEntity order,
                                                   FlowChainViews.FlowChain chain,
                                                   Long definitionId,
                                                   Integer definitionVersion,
                                                   List<String> nodeKeys,
                                                   Long actorId) {
        DataScope scope = TenantContext.require();
        long tenantId = scope.tenantId();
        int round = nextRound(tenantId, order.getId());
        LocalDateTime capturedAt = LocalDateTime.now().withNano(0);
        String level = chain == null ? null : chain.releaseLevel();
        if (level == null || level.isBlank()) {
            level = DEFAULT_LEVEL;
        }
        List<String> keys = nodeKeys == null ? List.of() : List.copyOf(nodeKeys);

        SnapshotPayload payload = new SnapshotPayload(round, capturedAt.toString(), level,
                definitionId, definitionVersion, keys, chain);
        String json = objectMapper.writeValueAsString(payload);
        String hash = sha256(json);

        FlowSnapshotEntity entity = new FlowSnapshotEntity();
        entity.setTenantId(tenantId);
        entity.setDeployOrderId(order.getId());
        entity.setReleaseId(order.getReleaseId());
        entity.setRoundNo(round);
        entity.setReleaseLevel(level);
        entity.setDefinitionId(definitionId == null ? 0L : definitionId);
        entity.setDefinitionVersion(definitionVersion == null ? 0 : definitionVersion);
        entity.setNodeKeys(String.join(",", keys));
        entity.setPayloadJson(json);
        entity.setPayloadHash(hash);
        entity.setCapturedAt(capturedAt);
        entity.setCreatedBy(actorId);
        repository.save(entity);

        // 刚固化的这份就是"当前"：段级 stale 恒为 false（前端提交成功后不应立刻看到"已变化"）。
        return new FlowChainViews.FlowSnapshotView(round, capturedAt.toString(), level, definitionId,
                definitionVersion, keys, chain, hash, emptyStale(chain), false);
    }

    // ------------------------------------------------------------------ 读

    /** 最新一轮；没有快照 → {@code null}（详情页据此渲染"尚无回执"，而不是伪造第 0 轮）。 */
    @Transactional(readOnly = true)
    public FlowChainViews.FlowSnapshotView latest(long deployOrderId) {
        DataScope scope = TenantContext.require();
        return repository
                .findTopByTenantIdAndDeployOrderIdOrderByRoundNoDesc(scope.tenantId(), deployOrderId)
                .map(this::toView)
                .orElse(null);
    }

    /** 轮次条（升序）：只给画「第 N 轮」需要的最小字段。 */
    @Transactional(readOnly = true)
    public List<FlowChainViews.FlowSnapshotSummary> history(long deployOrderId) {
        DataScope scope = TenantContext.require();
        List<FlowChainViews.FlowSnapshotSummary> out = new ArrayList<>();
        for (FlowSnapshotEntity entity : repository
                .findByTenantIdAndDeployOrderIdOrderByRoundNoAsc(scope.tenantId(), deployOrderId)) {
            out.add(new FlowChainViews.FlowSnapshotSummary(entity.getRoundNo(),
                    entity.getCapturedAt() == null ? null : entity.getCapturedAt().toString(),
                    entity.getPayloadHash()));
        }
        return out;
    }

    /**
     * 按轮次回放：返回的是<b>当轮固化的四段原样</b>，不叠加当前值；
     * 差异只通过 {@code segmentStale/changedSinceSubmit} 表达。不存在该轮 → 404 {@code SNAPSHOT_NOT_FOUND}。
     */
    @Transactional(readOnly = true)
    public FlowChainViews.FlowSnapshotView replay(long deployOrderId, int round) {
        DataScope scope = TenantContext.require();
        FlowSnapshotEntity entity = repository
                .findByTenantIdAndDeployOrderIdAndRoundNo(scope.tenantId(), deployOrderId, round)
                .orElseThrow(() -> new ApiException(404, SNAPSHOT_NOT_FOUND,
                        "该发布单没有第 " + round + " 轮提交快照",
                        Map.of("deployOrderId", deployOrderId, "round", round)));
        return toView(entity);
    }

    /**
     * 版本的发布等级（{@code release} 表一列，1 条主键查询）。
     *
     * <p>为什么不在 {@code ReleaseEntity} 上加字段读：等级是 r30 才有的列，发布单详情只需要这一个值，
     * 直接按既有 {@code /*scope:*}{@code /} 口径读一列，避免为它把整行版本实体（含需求/里程碑聚合）拉起来。</p>
     */
    @Transactional(readOnly = true)
    public String releaseLevel(long releaseId) {
        Map<String, Object> row = jdbcRead.one("""
                SELECT r.release_level AS release_level
                FROM `release` r
                WHERE /*scope:r*/ AND r.id = :releaseId
                """, Map.of("releaseId", releaseId));
        return ReleaseLevel.normalise(row == null ? null : Rows.str(row, "release_level"));
    }

    // ------------------------------------------------------------------ 内部

    private int nextRound(long tenantId, long deployOrderId) {
        return repository.findTopByTenantIdAndDeployOrderIdOrderByRoundNoDesc(tenantId, deployOrderId)
                .map(entity -> entity.getRoundNo() == null ? 1 : entity.getRoundNo() + 1)
                .orElse(1);
    }

    private FlowChainViews.FlowSnapshotView toView(FlowSnapshotEntity entity) {
        SnapshotPayload payload = parse(entity.getPayloadJson());
        Map<String, Boolean> stale = stale(payload.chain(), entity.getReleaseId());
        return new FlowChainViews.FlowSnapshotView(
                entity.getRoundNo(),
                entity.getCapturedAt() == null ? payload.capturedAt() : entity.getCapturedAt().toString(),
                entity.getReleaseLevel() == null ? payload.releaseLevel() : entity.getReleaseLevel(),
                payload.definitionId(),
                payload.definitionVersion(),
                payload.nodeKeys(),
                payload.chain(),
                entity.getPayloadHash(),
                stale,
                stale.containsValue(true));
    }

    private SnapshotPayload parse(String json) {
        try {
            return objectMapper.readValue(json, SnapshotPayload.class);
        } catch (RuntimeException ex) {
            // 快照是证据：读不出来说明行被外部写过，宁可 500 也不能编一份"看起来正常"的四段。
            throw new IllegalStateException("快照 payload 不可解析（该表只应被本服务插入）", ex);
        }
    }

    /**
     * 段级 stale：库里的 digest 与<b>当前</b>链的 digest 逐段比较。
     *
     * <p>当前链取不到（deliver 全挂等）时返回空 map + {@code changedSinceSubmit=false}：
     * "没法比较"不等于"变了"，但也绝不报 false 冒充一致——空 map 在页面上是不盖章。</p>
     */
    private Map<String, Boolean> stale(FlowChainViews.FlowChain stored, long releaseId) {
        Map<String, String> before = stored == null ? Map.of() : stored.digests();
        Map<String, String> now;
        try {
            FlowChainViews.FlowChain current = chainService.assemble(releaseId);
            if (current == null || allMissing(current)) {
                // 聚合降级（deliver 不可用）时四段都是 MISSING：那不代表"四段都变了"，
                // 只是"现在读不到"。此时不盖章，而不是把所有段标成 stale 骗人。
                return Map.of();
            }
            now = current.digests();
        } catch (RuntimeException ex) {
            return Map.of();
        }
        if (now.isEmpty()) {
            return Map.of();
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String key : now.keySet()) {
            out.put(key, !Objects.equals(before.get(key), now.get(key)));
        }
        return out;
    }

    /** 聚合降级判据：四段全是 MISSING 说明"现在读不到"，而不是"四段都变了"。 */
    private static boolean allMissing(FlowChainViews.FlowChain chain) {
        return FlowChainViews.SEGMENT_MISSING.equals(chain.testPlan().status())
                && FlowChainViews.SEGMENT_MISSING.equals(chain.signoff().status())
                && FlowChainViews.SEGMENT_MISSING.equals(chain.qualityGate().status())
                && FlowChainViews.SEGMENT_MISSING.equals(chain.deployAdmission().status());
    }

    private static Map<String, Boolean> emptyStale(FlowChainViews.FlowChain chain) {
        if (chain == null || chain.digests().isEmpty()) {
            return Map.of();
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String key : chain.digests().keySet()) {
            out.put(key, false);
        }
        return out;
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /**
     * {@code payload_json} 的结构（就是回放内容本身）。
     *
     * <p>用 record 而不是手拼 Map/手解析：读写同一个类型，字段不可能在"存"与"读"之间漂移；
     * 序列化顺序即组件顺序，所以同一份链路永远得到同一串字节、同一个 hash（AC-10）。</p>
     */
    public record SnapshotPayload(int round,
                                  String capturedAt,
                                  String releaseLevel,
                                  Long definitionId,
                                  Integer definitionVersion,
                                  List<String> nodeKeys,
                                  FlowChainViews.FlowChain chain) {

        public SnapshotPayload {
            nodeKeys = nodeKeys == null ? List.of() : List.copyOf(nodeKeys);
        }
    }
}
