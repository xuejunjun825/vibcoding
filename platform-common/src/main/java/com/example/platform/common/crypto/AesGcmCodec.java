package com.example.platform.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM codec shared by every module that has to keep a reversible secret at rest
 * ({@code integration_config.credential_enc} in req-service, {@code api_client.secret_enc}
 * in deliver-service).
 *
 * <p>Why one class instead of two: the wire format is a contract between writer and reader.
 * Two copies of the same cryptography drift the moment one of them learns key rotation, and
 * the failure mode is "the secret silently stops decoding" long after the change. The format
 * is fixed here, and every writer/reader delegates:</p>
 *
 * <pre>
 *   encrypted = "&lt;version&gt;:" + base64( iv[12] || ciphertext || tag[16] )
 *   key       = SHA-256( key material of &lt;version&gt; )
 * </pre>
 *
 * <p>The {@code version} prefix is the key ring selector: a row written under {@code v1}
 * still decodes after {@code v2} becomes the writing version, so rotation is a deployment
 * (add v2, switch {@code currentVersion}) rather than a data migration. For backwards
 * compatibility with rows written before keyring support, a payload without a known prefix
 * is tried against every configured key, and an ASCII base64 payload is decoded before the
 * attempt (the legacy {@code v1} writer already produced base64 text).</p>
 *
 * <p>Instances are immutable after construction and safe to share; the {@link SecureRandom}
 * is the only mutable state and it is thread-safe.</p>
 */
public final class AesGcmCodec {

    /** Version written when the caller does not name one. */
    public static final String DEFAULT_VERSION = "v1";

    private static final String SEPARATOR = ":";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final Map<String, byte[]> keys;
    private final String currentVersion;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param currentVersion version used by {@link #encode(String)}; must exist in {@code keyRing}
     *                       when the ring is non-empty
     * @param keyRing        version → key material (the material is hashed, not used directly)
     */
    public AesGcmCodec(String currentVersion, Map<String, String> keyRing) {
        Map<String, byte[]> derived = new LinkedHashMap<>();
        if (keyRing != null) {
            keyRing.forEach((version, material) -> {
                if (version != null && !version.isBlank()
                        && material != null && !material.isBlank()) {
                    derived.put(version.trim(), sha256(material));
                }
            });
        }
        this.keys = Map.copyOf(derived);
        this.currentVersion = resolveVersion(currentVersion, derived);
    }

    /** Single-key codec: the given material is registered as {@link #DEFAULT_VERSION}. */
    public static AesGcmCodec ofKey(String keyMaterial) {
        return new AesGcmCodec(DEFAULT_VERSION, Map.of(DEFAULT_VERSION, keyMaterial == null ? "" : keyMaterial));
    }

    /**
     * Parses a key ring written as {@code v1=material1,v2=material2}. A spec without a
     * {@code version=} pair is treated as the material of {@code currentVersion}, so a plain
     * environment secret keeps working.
     */
    public static AesGcmCodec ofRing(String spec, String currentVersion) {
        String version = currentVersion == null || currentVersion.isBlank()
                ? DEFAULT_VERSION : currentVersion.trim();
        String text = spec == null ? "" : spec.trim();
        if (text.isEmpty()) {
            return new AesGcmCodec(version, Map.of());
        }
        Map<String, String> ring = new LinkedHashMap<>();
        for (String part : text.split(",")) {
            String segment = part.trim();
            if (segment.isEmpty()) {
                continue;
            }
            int eq = segment.indexOf('=');
            if (eq <= 0) {
                ring.clear();
                break;
            }
            String candidate = segment.substring(0, eq).trim();
            String material = segment.substring(eq + 1).trim();
            if (!candidate.matches("[A-Za-z0-9_-]{1,16}") || material.isEmpty()) {
                ring.clear();
                break;
            }
            ring.put(candidate, material);
        }
        if (ring.isEmpty()) {
            ring.put(version, text);
        }
        return new AesGcmCodec(version, ring);
    }

    /** True when at least one key is configured; callers fail closed when it is false. */
    public boolean hasKey() {
        return !keys.isEmpty();
    }

    public String currentVersion() {
        return currentVersion;
    }

    /** Encrypts {@code plain} under {@link #currentVersion()} and returns the wire format bytes. */
    public byte[] encode(String plain) {
        if (plain == null) {
            throw new IllegalArgumentException("待加密内容不能为空");
        }
        byte[] key = keys.get(currentVersion);
        if (key == null) {
            throw new IllegalStateException("未配置凭据密钥版本 " + currentVersion
                    + "（INTEGRATION_CREDENTIAL_KEY / PLATFORM_MASTER_KEY）");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(cipherText, 0, blob, iv.length, cipherText.length);
            String text = currentVersion + SEPARATOR + Base64.getEncoder().encodeToString(blob);
            return text.getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("凭据加密失败（AES-GCM）", ex);
        }
    }

    /**
     * @return the plaintext, or {@code null} when the value is empty, not decodable, or was
     *         written under a key that is no longer in the ring (rotation done wrong)
     */
    public String decode(byte[] encrypted) {
        if (encrypted == null || encrypted.length == 0) {
            return null;
        }
        String text = isAscii(encrypted) ? new String(encrypted, StandardCharsets.US_ASCII) : null;
        if (text != null) {
            int separator = text.indexOf(SEPARATOR.charAt(0));
            if (separator > 0) {
                byte[] key = keys.get(text.substring(0, separator));
                if (key == null) {
                    // A version prefix we cannot resolve is authoritative: never guess a key.
                    return null;
                }
                byte[] blob = base64OrNull(text.substring(separator + 1));
                return blob == null ? null : decrypt(key, blob);
            }
            byte[] legacy = base64OrNull(text.trim());
            if (legacy != null) {
                String decoded = decryptWithAnyKey(legacy);
                if (decoded != null) {
                    return decoded;
                }
            }
        }
        return decryptWithAnyKey(encrypted);
    }

    /** Last four characters of the plaintext, for {@code ***abcd} display; never the secret. */
    public String last4(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        return plain.length() <= 4 ? plain : plain.substring(plain.length() - 4);
    }

    private String decryptWithAnyKey(byte[] blob) {
        for (byte[] key : keys.values()) {
            String plain = decrypt(key, blob);
            if (plain != null) {
                return plain;
            }
        }
        return null;
    }

    private static String decrypt(byte[] key, byte[] blob) {
        if (blob.length <= IV_BYTES) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, IV_BYTES);
            byte[] cipherText = new byte[blob.length - IV_BYTES];
            System.arraycopy(blob, IV_BYTES, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            return null;
        }
    }

    private static byte[] base64OrNull(String text) {
        try {
            return Base64.getDecoder().decode(text.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String resolveVersion(String requested, Map<String, byte[]> ring) {
        if (requested != null && !requested.isBlank() && ring.containsKey(requested.trim())) {
            return requested.trim();
        }
        if (ring.containsKey(DEFAULT_VERSION)) {
            return DEFAULT_VERSION;
        }
        return ring.isEmpty() ? (requested == null || requested.isBlank()
                ? DEFAULT_VERSION : requested.trim()) : ring.keySet().iterator().next();
    }

    private static byte[] sha256(String material) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static boolean isAscii(byte[] bytes) {
        for (byte value : bytes) {
            if (value < 0x20 || value > 0x7E) {
                return false;
            }
        }
        return true;
    }
}
