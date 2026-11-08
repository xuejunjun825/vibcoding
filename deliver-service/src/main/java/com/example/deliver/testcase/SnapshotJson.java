package com.example.deliver.testcase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON codec for {@code test_case_version.snapshot_json}.
 *
 * <p>Deliberately dependency-free: the runtime ships Jackson 3 ({@code tools.jackson}) which is not
 * on the compile classpath this module is verified with, and the snapshot shape is a closed, tiny
 * object. Writing and reading it here keeps the version snapshot stable even if the JSON library
 * changes, and makes "history displays what it showed then" testable without a container.</p>
 */
final class SnapshotJson {

    private SnapshotJson() {
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder(256);
        writeValue(out, value);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            writeString(out, text);
        } else if (value instanceof Boolean flag) {
            out.append(flag.booleanValue());
        } else if (value instanceof Number number) {
            out.append(number.toString());
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                writeValue(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeValue(out, item);
            }
            out.append(']');
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
    }

    static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Parser parser = new Parser(json);
        Object value = parser.value();
        parser.skipSpace();
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("snapshot is not a JSON object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static String str(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    static int num(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean flag) {
            return flag.booleanValue();
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objects(Map<String, Object> map, String key) {
        Object value = map.get(key);
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> row) {
                    Map<String, Object> converted = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : row.entrySet()) {
                        converted.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    out.add(converted);
                }
            }
        }
        return out;
    }

    static List<String> strings(Map<String, Object> map, String key) {
        Object value = map.get(key);
        List<String> out = new ArrayList<>();
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    /** A minimal recursive-descent reader for the object this class writes. */
    private static final class Parser {

        private final String text;
        private int pos;

        private Parser(String text) {
            this.text = text;
        }

        private void skipSpace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private Object value() {
            skipSpace();
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unexpected end of snapshot");
            }
            char ch = text.charAt(pos);
            return switch (ch) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) {
                throw new IllegalArgumentException("bad literal at " + pos);
            }
            pos += word.length();
            return value;
        }

        private Map<String, Object> object() {
            Map<String, Object> out = new LinkedHashMap<>();
            pos++;
            skipSpace();
            if (pos < text.length() && text.charAt(pos) == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipSpace();
                String key = string();
                skipSpace();
                expect(':');
                out.put(key, value());
                skipSpace();
                if (pos < text.length() && text.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                expect('}');
                return out;
            }
        }

        private List<Object> array() {
            List<Object> out = new ArrayList<>();
            pos++;
            skipSpace();
            if (pos < text.length() && text.charAt(pos) == ']') {
                pos++;
                return out;
            }
            while (true) {
                out.add(value());
                skipSpace();
                if (pos < text.length() && text.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                expect(']');
                return out;
            }
        }

        private void expect(char wanted) {
            if (pos >= text.length() || text.charAt(pos) != wanted) {
                throw new IllegalArgumentException("expected '" + wanted + "' at " + pos);
            }
            pos++;
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char ch = text.charAt(pos++);
                if (ch == '"') {
                    return out.toString();
                }
                if (ch != '\\') {
                    out.append(ch);
                    continue;
                }
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape at " + pos);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Object number() {
            int start = pos;
            while (pos < text.length() && "-+.eE0123456789".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String raw = text.substring(start, pos);
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("bad value at " + start);
            }
            if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
                return Double.valueOf(raw);
            }
            return Long.valueOf(raw);
        }
    }
}
