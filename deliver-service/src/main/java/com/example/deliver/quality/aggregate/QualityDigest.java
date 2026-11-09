package com.example.deliver.quality.aggregate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.example.deliver.support.Rows;
import com.example.platform.common.scope.ScopedJdbc;
import org.springframework.stereotype.Component;

/**
 * The one and only {@code result_digest} implementation.
 *
 * <p>Every place that needs "did the execution data change since the report was generated"
 * (report generation, QG-02, QG-03, the release quality page) calls this class. A second
 * {@code MessageDigest("SHA-256")} anywhere else would produce a byte-different hash and turn a
 * stale report into a silently valid one.</p>
 *
 * <p>Algorithm (PRD): rows of {@code caseId + ":" + caseVersion + ":" + result}, ordered by
 * case id, joined with {@code "\n"}, UTF-8, lowercase hex. A case added, removed, re-pinned to a
 * new version or re-run all change the digest.</p>
 */
@Component
public class QualityDigest {

    private final ScopedJdbc jdbc;

    public QualityDigest(ScopedJdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** Digest of every plan case that belongs to the release (all scoped plans). */
    public String digest(long releaseId) {
        List<Map<String, Object>> rows = jdbc.list("""
                SELECT pc.case_id, pc.case_version, pc.result
                FROM test_plan_case pc
                JOIN test_plan p ON p.id = pc.plan_id
                WHERE /*scope:p*/ AND p.release_id = :releaseId
                ORDER BY pc.case_id ASC, pc.plan_id ASC
                """, Map.of("releaseId", releaseId));
        List<CaseStamp> stamps = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            stamps.add(new CaseStamp(
                    Rows.toLong(Rows.object(row, "case_id"), 0L),
                    Rows.toInt(Rows.object(row, "case_version"), 1),
                    Rows.str(row, "result", "NOT_RUN")));
        }
        return digest(stamps);
    }

    /** Digest of an explicit stamp list — used when the report freezes its own case set. */
    public String digest(List<CaseStamp> stamps) {
        List<CaseStamp> ordered = new ArrayList<>(stamps == null ? List.of() : stamps);
        ordered.sort(Comparator.comparingLong(CaseStamp::caseId)
                .thenComparingInt(CaseStamp::caseVersion)
                .thenComparing(CaseStamp::result, Comparator.nullsFirst(Comparator.naturalOrder())));
        StringBuilder payload = new StringBuilder(ordered.size() * 24);
        for (CaseStamp stamp : ordered) {
            if (payload.length() > 0) {
                payload.append('\n');
            }
            payload.append(stamp.caseId()).append(':')
                    .append(stamp.caseVersion()).append(':')
                    .append(stamp.result());
        }
        return sha256(payload.toString());
    }

    /** Lowercase hex SHA-256 of the UTF-8 payload. */
    public static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable in this JVM", ex);
        }
    }

    /** One {@code case_id:case_version:result} line of the digest. */
    public record CaseStamp(long caseId, int caseVersion, String result) {
    }
}
