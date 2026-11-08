package com.example.req.approval;

import org.springframework.stereotype.Component;

import java.util.List;

/** {@link WorkflowRegistry} 的 JPA 实现：解析顺序与版本固化都落在这里的一处查询上。 */
@Component
public class JpaWorkflowRegistry implements WorkflowRegistry {

    private final WorkflowVersionRepository versions;
    private final WorkflowJson json;

    public JpaWorkflowRegistry(WorkflowVersionRepository versions, WorkflowJson json) {
        this.versions = versions;
        this.json = json;
    }

    @Override
    public ResolvedWorkflow resolve(long tenantId, String bizType, String reqType, long domainId) {
        String type = reqType == null || reqType.isBlank() ? WorkflowVersion.ANY_REQ_TYPE : reqType;
        List<WorkflowVersion> bound = versions.resolveCandidates(tenantId, bizType, type, domainId);
        WorkflowVersion chosen = bound.isEmpty() ? null : bound.get(0);
        if (chosen == null) {
            List<WorkflowVersion> defaults = versions.defaultCandidates(tenantId, bizType);
            chosen = defaults.isEmpty() ? null : defaults.get(0);
        }
        return chosen == null ? null : toResolved(chosen);
    }

    @Override
    public ResolvedWorkflow byId(long definitionId) {
        return versions.findById(definitionId).map(this::toResolved).orElse(null);
    }

    private ResolvedWorkflow toResolved(WorkflowVersion row) {
        return new ResolvedWorkflow(
                row.getId(),
                row.getVersion() == null ? 1 : row.getVersion(),
                row.getName(),
                row.getStatus(),
                json.parse(row.getDefinitionJson()));
    }
}
