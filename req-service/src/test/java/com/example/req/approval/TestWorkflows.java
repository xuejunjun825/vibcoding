package com.example.req.approval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 内存版流程注册表：复刻 {@code WorkflowVersionRepository.resolveCandidates} 的优先级
 * （req_type 精确 > ANY，domain 精确 > 0，版本高者优先，同版本取 id 大者），
 * 让 AC-6/AC-8 的「匹配与版本固化」可以脱离数据库断言。
 */
final class TestWorkflows implements WorkflowRegistry {

    record Entry(long id, int version, String bizType, String reqType, long domainId,
                 String status, boolean isDefault, WorkflowDefinition definition) {
    }

    private final List<Entry> entries = new ArrayList<>();

    TestWorkflows add(long id, int version, String bizType, String reqType, long domainId,
                      String status, boolean isDefault, WorkflowDefinition definition) {
        entries.add(new Entry(id, version, bizType, reqType == null ? WorkflowVersion.ANY_REQ_TYPE : reqType,
                domainId, status, isDefault, definition));
        return this;
    }

    /** 发布一条新版本，返回定义的 id。 */
    long publish(long id, int version, String bizType, WorkflowDefinition definition) {
        return add(id, version, bizType, WorkflowVersion.ANY_REQ_TYPE, WorkflowVersion.ANY_DOMAIN,
                WorkflowVersion.STATUS_PUBLISHED, false, definition).entries.getLast().id();
    }

    @Override
    public ResolvedWorkflow resolve(long tenantId, String bizType, String reqType, long domainId) {
        String wantedReqType = reqType == null ? WorkflowVersion.ANY_REQ_TYPE : reqType;
        Entry best = entries.stream()
                .filter(entry -> WorkflowVersion.STATUS_PUBLISHED.equals(entry.status()))
                .filter(entry -> bizType.equals(entry.bizType()))
                .filter(entry -> wantedReqType.equals(entry.reqType())
                        || WorkflowVersion.ANY_REQ_TYPE.equals(entry.reqType()))
                .filter(entry -> entry.domainId() == domainId || entry.domainId() == WorkflowVersion.ANY_DOMAIN)
                .max(Comparator
                        .comparingInt((Entry entry) -> wantedReqType.equals(entry.reqType()) ? 1 : 0)
                        .thenComparingInt(entry -> entry.domainId() == domainId ? 1 : 0)
                        .thenComparingInt(Entry::version)
                        .thenComparingLong(Entry::id))
                .orElse(null);
        if (best == null) {
            best = entries.stream()
                    .filter(entry -> WorkflowVersion.STATUS_PUBLISHED.equals(entry.status()))
                    .filter(entry -> bizType.equals(entry.bizType()))
                    .filter(Entry::isDefault)
                    .max(Comparator.comparingInt(Entry::version).thenComparingLong(Entry::id))
                    .orElse(null);
        }
        return best == null ? null : new ResolvedWorkflow(best.id(), best.version(), best.bizType(),
                best.status(), best.definition());
    }

    @Override
    public ResolvedWorkflow byId(long definitionId) {
        Entry entry = entries.stream()
                .filter(candidate -> candidate.id() == definitionId)
                .findFirst()
                .orElseThrow(() -> ApprovalException.notFound("流程定义不存在：" + definitionId));
        return new ResolvedWorkflow(entry.id(), entry.version(), entry.bizType(), entry.status(), entry.definition());
    }
}
