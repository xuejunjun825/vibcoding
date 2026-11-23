package com.example.req.automation.job;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.platform.common.scope.ScopedJdbc;
import com.example.req.automation.engine.AutomationDispatcher;
import com.example.req.automation.engine.AutomationRunWriter;
import com.example.req.automation.engine.AutomationScopes;
import com.example.req.automation.event.AutomationEventDraft;
import com.example.req.automation.event.AutomationEventMapper;
import com.example.req.automation.event.AutomationEventStore;
import com.example.req.automation.rule.AutomationRuleEntity;
import com.example.req.automation.rule.AutomationRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily SCHEDULE scan (08:30 Asia/Shanghai).
 *
 * <p>Two facts drive the shape of this class:</p>
 * <ul>
 *   <li><b>One event per requirement per day.</b> PRD 3.2 wrote {@code SCHEDULE:<date>}, but the
 *       event unique key is {@code (tenant_id, event_id)}, so that id would fold a whole day's
 *       matches into one. The id therefore carries the requirement id — the requirement is what the
 *       run is about.</li>
 *   <li><b>Truncation is its own row.</b> {@code requirement_id IS NULL} cannot participate in the
 *       run unique key (NULL never equals NULL), so the truncation marker is deduplicated by an
 *       explicit existence check and its run is written here, once, as SKIPPED /
 *       SCHEDULE_TRUNCATED. It is the single documented exception to "the key does the dedupe".</li>
 * </ul>
 */
@Component
public class AutomationScheduleScanner {

    private static final Logger log = LoggerFactory.getLogger(AutomationScheduleScanner.class);

    /** Hard ceiling per rule per day; above it the run is marked truncated and no further matches. */
    public static final int MAX_MATCHES_PER_RUN = 200;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AutomationRuleRepository rules;
    private final AutomationEventStore events;
    private final AutomationRunWriter runs;
    private final ScopedJdbc jdbc;
    private final JdbcTemplate plainJdbc;

    public AutomationScheduleScanner(AutomationRuleRepository rules, AutomationEventStore events,
                                     AutomationRunWriter runs, ScopedJdbc jdbc,
                                     JdbcTemplate plainJdbc) {
        this.rules = rules;
        this.events = events;
        this.runs = runs;
        this.jdbc = jdbc;
        this.plainJdbc = plainJdbc;
    }

    @Scheduled(cron = "${platform.automation.schedule-cron:0 30 8 * * *}", zone = "Asia/Shanghai")
    public void scanAllTenants() {
        List<Long> tenants = plainJdbc.queryForList(
                "SELECT DISTINCT tenant_id FROM automation_rule"
                        + " WHERE enabled = 1 AND trigger_type = ?",
                Long.class, AutomationEventMapper.EVENT_SCHEDULE);
        for (Long tenantId : tenants) {
            for (AutomationRuleEntity rule : rules
                    .findByTenantIdAndEnabledAndTriggerTypeOrderByPriorityAscIdAsc(tenantId, true,
                            AutomationEventMapper.EVENT_SCHEDULE)) {
                try {
                    scanRule(rule);
                } catch (RuntimeException ex) {
                    log.warn("automation schedule scan failed: rule={} error={}", rule.getId(),
                            ex.getMessage(), ex);
                }
            }
        }
    }

    /** @return number of events produced (not counting the truncation marker) */
    public int scanRule(AutomationRuleEntity rule) {
        String date = LocalDate.now(ZONE).toString();
        List<Long> matched = AutomationScopes.withScope(AutomationDispatcher.scopeOf(rule),
                () -> jdbc.list("SELECT r.id AS id FROM requirement r"
                                + " WHERE /*scope:r*/ AND r.status NOT IN ('CLOSED', 'REJECTED')"
                                + " ORDER BY r.priority, r.id",
                        Map.of()).stream()
                        .map(row -> ((Number) row.get("id")).longValue())
                        .limit(MAX_MATCHES_PER_RUN + 1L)
                        .toList());
        boolean truncated = matched.size() > MAX_MATCHES_PER_RUN;
        List<Long> selected = truncated ? matched.subList(0, MAX_MATCHES_PER_RUN) : matched;
        if (selected.isEmpty()) {
            return 0;
        }
        List<AutomationEventDraft> drafts = new ArrayList<>(selected.size());
        for (Long requirementId : selected) {
            drafts.add(new AutomationEventDraft(
                    AutomationEventMapper.scheduleEventId(date, requirementId),
                    AutomationEventMapper.EVENT_SCHEDULE,
                    requirementId,
                    AutomationEventMapper.ORIGIN_USER,
                    1,
                    Map.of()));
        }
        int inserted = events.insertIgnore(drafts);
        if (truncated) {
            markTruncated(rule, date, matched.size());
        }
        return inserted;
    }

    private void markTruncated(AutomationRuleEntity rule, String date, int matched) {
        String eventId = AutomationEventMapper.scheduleTruncatedEventId(date);
        log.warn("automation schedule truncated: rule={} date={} matched>{} limit={}",
                rule.getId(), date, MAX_MATCHES_PER_RUN, matched);
        events.insertIgnore(List.of(new AutomationEventDraft(eventId,
                AutomationEventMapper.EVENT_SCHEDULE_TRUNCATED, null,
                AutomationEventMapper.ORIGIN_USER, 1, Map.of())));
        Optional<Long> runId = runs.tryStart(rule.getTenantId(), rule.getId(),
                rule.getVersion() == null ? 1 : rule.getVersion(), null, eventId,
                AutomationEventMapper.EVENT_SCHEDULE_TRUNCATED);
        runId.ifPresent(id -> runs.finish(id, AutomationRunWriter.STATUS_SKIPPED,
                AutomationRunWriter.SKIP_SCHEDULE_TRUNCATED, List.of(), 0L));
    }
}
