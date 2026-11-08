package com.example.deliver.openapi;

import java.time.LocalDateTime;

import com.example.deliver.testplan.TestExecutionEntity;
import com.example.deliver.testplan.TestExecutionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inserts one automatic execution in its own transaction.
 *
 * <p>Idempotency is enforced by the database, not by a read-then-write check: two CI retries can
 * arrive at the same instant, both pass a pre-check, and both insert. Here the unique key
 * {@code uk_exec_auto(tenant_id, client_id, run_id, case_id)} decides, the loser catches the
 * violation and reads back the winner's row.</p>
 *
 * <p>The insert runs through a {@link TransactionTemplate} rather than {@code @Transactional} on
 * this method: catching the violation inside the same transactional method leaves the JPA session
 * marked rollback-only, and the follow-up read then fails on flush with
 * {@code AssertionFailure: ... has a null identifier} (2026-11-09 T11 实测). With the template the
 * failed attempt rolls back and is gone before the read-back runs in its own transaction.</p>
 */
@Component
public class ExecutionWriter {

    private final TestExecutionRepository executions;
    private final TransactionTemplate insertTx;

    public ExecutionWriter(TestExecutionRepository executions,
                           PlatformTransactionManager transactionManager) {
        this.executions = executions;
        this.insertTx = new TransactionTemplate(transactionManager);
        this.insertTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Inserted(long id, boolean idempotent) {
    }

    public Inserted insert(long tenantId, long clientId, String runId, long caseId, int caseVersion,
                           Long requirementId, Long planId, String result, Integer durationMs,
                           String logUrl, LocalDateTime executedAt, String ip) {
        TestExecutionEntity row = new TestExecutionEntity();
        row.setTenantId(tenantId);
        row.setClientId(clientId);
        row.setRunId(runId);
        row.setCaseId(caseId);
        row.setCaseVersion(caseVersion);
        row.setRequirementId(requirementId);
        row.setPlanId(planId);
        row.setSource("AUTO");
        row.setResult(result);
        row.setDurationMs(durationMs);
        row.setLogUrl(logUrl);
        row.setExecutedAt(executedAt);
        row.setIp(ip);
        try {
            Long id = insertTx.execute(status -> executions.saveAndFlush(row).getId());
            return new Inserted(id == null ? 0L : id, false);
        } catch (DataIntegrityViolationException duplicate) {
            return executions.findByTenantIdAndClientIdAndRunIdAndCaseId(tenantId, clientId, runId,
                            caseId)
                    .map(existing -> new Inserted(existing.getId(), true))
                    .orElseThrow(() -> duplicate);
        }
    }
}
