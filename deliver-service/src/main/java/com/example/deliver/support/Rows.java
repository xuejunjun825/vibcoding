package com.example.deliver.support;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Tolerant accessors for {@link java.util.Map} rows returned by the scope-aware JDBC layer.
 *
 * <p>Column labels come back upper-cased by some drivers/pool settings and lower-cased by
 * others, so every lookup tries the given name first and its upper-case twin second. Keeping
 * that in one place means no query has to care which driver answered it.</p>
 */
public final class Rows {

    private Rows() {
    }

    public static Object object(Map<String, Object> row, String name) {
        if (row == null) {
            return null;
        }
        if (row.containsKey(name)) {
            return row.get(name);
        }
        return row.get(name.toUpperCase());
    }

    public static String str(Map<String, Object> row, String name) {
        Object value = object(row, name);
        return value == null ? null : value.toString();
    }

    public static String str(Map<String, Object> row, String name, String fallback) {
        String value = str(row, name);
        return value == null ? fallback : value;
    }

    public static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : Long.valueOf(text);
    }

    public static long toLong(Object value, long fallback) {
        Long parsed = toLong(value);
        return parsed == null ? fallback : parsed;
    }

    public static Long lng(Map<String, Object> row, String name) {
        return toLong(object(row, name));
    }

    public static Integer toInt(Object value) {
        Long parsed = toLong(value);
        return parsed == null ? null : parsed.intValue();
    }

    public static int toInt(Object value, int fallback) {
        Long parsed = toLong(value);
        return parsed == null ? fallback : parsed.intValue();
    }

    public static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : new BigDecimal(text);
    }

    public static LocalDateTime toDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.util.Date date) {
            return new Timestamp(date.getTime()).toLocalDateTime();
        }
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }

    public static LocalDate toDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        return LocalDate.parse(value.toString().substring(0, 10));
    }
}
