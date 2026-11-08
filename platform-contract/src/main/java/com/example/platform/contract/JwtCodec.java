package com.example.platform.contract;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HS256 JWT signing/verification, JDK only.
 *
 * <p>Lives in the contract because the gateway (WebFlux) and both services (Servlet) must
 * decode the same token. Each implementing its own parser is how "gateway accepts, service
 * rejects" (or worse, the reverse) happens. HMAC-SHA256 via {@link Mac}, base64url without
 * padding, constant-time signature comparison.</p>
 *
 * <p>Only the claims this platform needs are encoded; there is no JWT library involved, so
 * there is no algorithm-confusion surface: the header must be exactly {@code HS256}.</p>
 */
public final class JwtCodec {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String HEADER = B64.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    private static final String HMAC = "HmacSHA256";

    private final byte[] key;

    public JwtCodec(String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("JWT secret must be at least 16 characters");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Signs {@code payload} with a TTL measured from now; {@code expiresAt} is overwritten. */
    public String issue(JwtPayload payload, Duration ttl) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        long now = Instant.now().getEpochSecond();
        long exp = now + ttl.toSeconds();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", payload.userId());
        claims.put("tid", payload.tenantId());
        claims.put("username", payload.username());
        claims.put("roles", payload.roles());
        claims.put("domains", new ArrayList<>(payload.domainIds()));
        claims.put("vendorId", payload.vendorId());
        claims.put("orgs", new ArrayList<>(payload.orgSubtreeIds()));
        claims.put("iat", now);
        claims.put("exp", exp);
        String body = B64.encodeToString(writeJson(claims).getBytes(StandardCharsets.UTF_8));
        String signingInput = HEADER + "." + body;
        String signature = B64.encodeToString(hmac(signingInput.getBytes(StandardCharsets.US_ASCII)));
        return signingInput + "." + signature;
    }

    /**
     * Verifies and decodes a token.
     *
     * @throws IllegalArgumentException when the token is malformed, unsigned, signed with
     *                                  another algorithm/key, or expired
     */
    public JwtPayload parse(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token is required");
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("malformed token");
        }
        if (!HEADER.equals(parts[0])) {
            throw new IllegalArgumentException("unsupported token algorithm");
        }
        byte[] expected = hmac((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        byte[] actual;
        try {
            actual = B64D.decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("malformed token signature", ex);
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("invalid token signature");
        }
        Map<String, Object> claims;
        try {
            String json = new String(B64D.decode(parts[1]), StandardCharsets.UTF_8);
            claims = readJsonObject(json);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("malformed token payload", ex);
        }
        long userId = asLong(claims.get("sub"), 0L);
        long tenantId = asLong(claims.get("tid"), 0L);
        String username = asString(claims.get("username"));
        List<String> roles = asStringList(claims.get("roles"));
        Set<Long> domains = asLongSet(claims.get("domains"));
        Long vendorId = claims.get("vendorId") == null ? null : asLong(claims.get("vendorId"), 0L);
        Set<Long> orgs = asLongSet(claims.get("orgs"));
        long iat = asLong(claims.get("iat"), 0L);
        long exp = asLong(claims.get("exp"), 0L);
        if (tenantId <= 0) {
            throw new IllegalArgumentException("token has no tenant");
        }
        if (exp > 0 && Instant.now().getEpochSecond() >= exp) {
            throw new IllegalArgumentException("token expired");
        }
        return new JwtPayload(userId, tenantId, username, roles, domains, vendorId, orgs, iat, exp);
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    // ------------------------------------------------------------------ minimal JSON

    private static String writeJson(Map<String, Object> claims) {
        StringBuilder out = new StringBuilder(160);
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : claims.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(entry.getKey()).append("\":");
            appendValue(out, entry.getValue());
        }
        return out.append('}').toString();
    }

    private static void appendValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Number number) {
            out.append(number.longValue());
        } else if (value instanceof Boolean flag) {
            out.append(flag);
        } else if (value instanceof Iterable<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                appendValue(out, item);
            }
            out.append(']');
        } else {
            out.append('"');
            String text = String.valueOf(value);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }
    }

    private static Map<String, Object> readJsonObject(String json) {
        Cursor cursor = new Cursor(json);
        Object value = cursor.value();
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("payload is not an object");
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        map.forEach((k, v) -> claims.put(String.valueOf(k), v));
        return claims;
    }

    private static long asLong(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static Set<Long> asLongSet(Object value) {
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (Object item : list) {
            long parsed = asLong(item, 0L);
            if (parsed > 0) {
                result.add(parsed);
            }
        }
        return result;
    }

    /** Tiny recursive-descent reader for the flat payloads this codec writes. */
    private static final class Cursor {

        private final String text;
        private int pos;

        private Cursor(String text) {
            this.text = text;
        }

        private Object value() {
            skipWhitespace();
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unexpected end of json");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                map.put(key, value());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or '}'");
                }
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or ']'");
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char esc = next();
                switch (esc) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        String hex = text.substring(pos, pos + 4);
                        pos += 4;
                        out.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> throw new IllegalArgumentException("bad escape: \\" + esc);
                }
            }
        }

        private Object number() {
            int start = pos;
            while (pos < text.length() && "-+.eE0123456789".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String raw = text.substring(start, pos);
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("expected a value");
            }
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        }

        private Object literal(String literal, Object value) {
            if (!text.startsWith(literal, pos)) {
                throw new IllegalArgumentException("expected " + literal);
            }
            pos += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unexpected end of json");
            }
            return text.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char expected) {
            if (next() != expected) {
                throw new IllegalArgumentException("expected '" + expected + "'");
            }
        }
    }
}
