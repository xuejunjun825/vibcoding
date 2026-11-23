package com.example.req.automation.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.platform.common.audit.AuditListener;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Contract tests for the audit -> listener bridge (r39 T3).
 *
 * <p>Three properties are load-bearing for the whole automation design and are asserted here
 * against a stubbed {@link JdbcTemplate}, i.e. without a database:</p>
 * <ol>
 *   <li><b>rollback means no event</b> — if the audit {@code INSERT} fails, the listener is never
 *       called and the failure reaches the caller, so the business transaction rolls back with the
 *       event that does not exist;</li>
 *   <li><b>the event carries {@code audit_log.id}</b> — event ids end in that id, so retrying the
 *       same business action cannot collapse two events into one;</li>
 *   <li><b>a listener failure never breaks the business path</b> — audit rides on the business
 *       transaction, so a broken outbox must not turn a successful request into a 500.</li>
 * </ol>
 */
class AuditListenerContractTest {

    private static final long TENANT = 1L;

    private final List<Map<String, Object>> received = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.set(DataScope.system(TENANT));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A JdbcTemplate whose only interesting method is the keyed insert. */
    private static JdbcTemplate jdbc(boolean fail, Long generatedId) {
        return new JdbcTemplate() {
            @Override
            public int update(PreparedStatementCreator creator, KeyHolder keyHolder) {
                if (fail) {
                    throw new IllegalStateException("audit_log insert failed");
                }
                if (generatedId != null) {
                    keyHolder.getKeyList().add(Map.of("id", generatedId));
                }
                return 1;
            }
        };
    }

    private AuditService service(JdbcTemplate jdbc, List<AuditListener> listeners) {
        return new AuditService(jdbc, new ObjectMapper(),
                mock(PlatformTransactionManager.class), listeners);
    }

    private AuditListener recordingListener() {
        return (action, bizType, bizId, detail) -> {
            Map<String, Object> row = new LinkedHashMap<>(detail);
            row.put("__action", action);
            row.put("__bizType", bizType);
            row.put("__bizId", bizId);
            received.add(row);
        };
    }

    @Test
    @DisplayName("failed audit insert -> listener never called, failure propagates (rollback = 0 events)")
    void failedInsertProducesNoEvent() {
        AuditService audit = service(jdbc(true, null), List.of(recordingListener()));

        assertThatThrownBy(() -> audit.record("CREATE", "REQUIREMENT", 500L, Map.of("type", "RD")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit_log insert failed");
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("listener receives the action, aggregate id and the new audit_log.id as auditId")
    void listenerSeesAuditId() {
        AuditService audit = service(jdbc(false, 42L), List.of(recordingListener()));

        audit.record("CREATE", "REQUIREMENT", 500L, Map.of("type", "RD", "status", "DRAFT"));

        assertThat(received).hasSize(1);
        Map<String, Object> detail = received.get(0);
        assertThat(detail).containsEntry("auditId", 42L)
                .containsEntry("__action", "CREATE")
                .containsEntry("__bizType", "REQUIREMENT")
                .containsEntry("__bizId", 500L)
                .containsEntry("type", "RD");
    }

    @Test
    @DisplayName("a listener that throws does not break the caller of record()")
    void listenerFailureIsContained() {
        AuditListener broken = (action, bizType, bizId, detail) -> {
            throw new IllegalStateException("outbox down");
        };
        AuditService audit = service(jdbc(false, 7L), List.of(broken, recordingListener()));

        audit.record("CREATE", "REQUIREMENT", 500L, Map.of("type", "RD"));

        // The second listener still ran: one broken subscriber does not starve the rest.
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).containsEntry("auditId", 7L);
    }

    @Test
    @DisplayName("no listener registered (deliver-service) is a no-op, not a failure")
    void emptyListenerListIsSafe() {
        AuditService audit = service(jdbc(false, 9L), List.of());

        audit.record("CREATE", "REQUIREMENT", 500L, Map.of("type", "RD"));

        assertThat(received).isEmpty();
    }
}
