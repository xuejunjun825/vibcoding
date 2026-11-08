package com.example.platform.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Open-API signature: HMAC-SHA256 over a canonical string, closing with the body digest.
 *
 * <p>The canonical string is an external contract — the CI example in the docs must produce
 * byte-identical output — so it lives in one class instead of being re-assembled at each
 * filter. Shape ({@code \n}-separated, no trailing newline):</p>
 *
 * <pre>
 * METHOD
 * PATH
 * APP_KEY
 * TIMESTAMP
 * hex(sha256(rawBody))
 * </pre>
 *
 * <p>Timestamps are validated by the caller (±300s); this class only proves integrity.
 * Signature comparison is constant-time, and an empty/missing signature never passes.</p>
 */
public final class HmacSigner {

    private static final String HMAC = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private HmacSigner() {
    }

    /** Builds the canonical string. {@code body} may be empty; it is digested, never included raw. */
    public static String canonical(String method, String path, String appKey, String timestamp, byte[] body) {
        return String.valueOf(method).toUpperCase()
                + "\n" + String.valueOf(path)
                + "\n" + String.valueOf(appKey)
                + "\n" + String.valueOf(timestamp)
                + "\n" + sha256Hex(body);
    }

    /** Lower-case hex HMAC-SHA256 of the canonical string with the client secret. */
    public static String sign(String secret, String canonical) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("secret is required");
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return HEX.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    /** Constant-time verification; {@code null}/blank signature is false, never an exception. */
    public static boolean verify(String secret, String canonical, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        String expected = sign(secret, canonical);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    /** Lower-case hex SHA-256 of the raw body; {@code null} body is treated as empty. */
    public static String sha256Hex(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(body == null ? new byte[0] : body));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
