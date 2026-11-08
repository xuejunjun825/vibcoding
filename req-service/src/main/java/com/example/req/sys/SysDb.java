package com.example.req.sys;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

/**
 * Native-SQL access to the {@code sys_*} tables.
 *
 * <p>These tables have no JPA entities (they are read on every request by the auth filter and
 * written by the admin screens), and the read path here must not go through the ORM. It is
 * deliberately a plain {@link EntityManager} wrapper: the project lint forbids the Spring
 * template helper outside {@code ScopedJdbc} and the seed service, so the platform keeps
 * exactly one raw-SQL helper per concern instead of scattering it through services.</p>
 */
@Component
public class SysDb {

    @PersistenceContext
    private EntityManager em;

    public List<Object[]> rows(String sql, Object... params) {
        Query query = em.createNativeQuery(sql);
        bind(query, params);
        List<?> raw = query.getResultList();
        List<Object[]> result = new ArrayList<>(raw.size());
        for (Object value : raw) {
            // JPA returns a bare scalar (not Object[]) when the SELECT has exactly one column.
            result.add(value instanceof Object[] cells ? cells : new Object[]{value});
        }
        return result;
    }

    public Optional<Object[]> row(String sql, Object... params) {
        List<Object[]> rows = rows(sql, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Object> scalars(String sql, Object... params) {
        Query query = em.createNativeQuery(sql);
        bind(query, params);
        @SuppressWarnings("unchecked")
        List<Object> result = query.getResultList();
        return result;
    }

    public long count(String sql, Object... params) {
        List<Object> values = scalars(sql, params);
        return values.isEmpty() ? 0L : asLong(values.get(0));
    }

    public int update(String sql, Object... params) {
        Query query = em.createNativeQuery(sql);
        bind(query, params);
        return query.executeUpdate();
    }

    /** Insert and return the generated id (MySQL {@code LAST_INSERT_ID()}). */
    public long insert(String sql, Object... params) {
        update(sql, params);
        List<Object> ids = scalars("SELECT LAST_INSERT_ID()");
        return ids.isEmpty() ? 0L : asLong(ids.get(0));
    }

    private static void bind(Query query, Object... params) {
        for (int i = 0; i < params.length; i++) {
            query.setParameter(i + 1, params[i]);
        }
    }

    public static long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof BigInteger big) {
            return big.longValue();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    public static Long asNullableLong(Object value) {
        return value == null ? null : asLong(value);
    }

    public static int asInt(Object value) {
        return (int) asLong(value);
    }

    public static boolean asBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(value.toString());
    }

    public static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    public static String asIso(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof LocalDateTime time) {
            return time.atZone(ZoneId.systemDefault()).toInstant().toString();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        return value.toString();
    }

    public static int pageSize(Integer size) {
        if (size == null || size <= 0) {
            return 20;
        }
        return Math.min(size, 200);
    }

    public static int pageNo(Integer page) {
        return page == null || page <= 0 ? 1 : page;
    }

    /** Access an optional column without a NoResultException when the row exists but is null. */
    public static <T> T orNull(Optional<Object[]> row, int index, Class<T> type) {
        return row.map(values -> type.cast(values[index])).orElse(null);
    }

    /** True when a query that returns exactly one row found nothing. */
    public static boolean missing(java.util.function.Supplier<?> supplier) {
        try {
            supplier.get();
            return false;
        } catch (NoResultException ex) {
            return true;
        }
    }
}
