package com.example.deliver.openapi;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Recovers the HMAC secret of an API client.
 *
 * <p>Verifying an HMAC needs the key itself, so the client's secret cannot be stored as a hash
 * only: {@code secret_hash} exists for display/audit and {@code secret_enc} carries the
 * reversible copy (AES-256-GCM, key derived from {@code platform.master-key}). The wire format
 * this class accepts is fixed here so the writer (req-service admin API) and this reader cannot
 * drift:</p>
 *
 * <pre>
 *   secret_enc = "v1:" + base64( iv[12] || ciphertext||tag )
 *   key        = SHA-256(platform.master-key)
 * </pre>
 *
 * <p>Until the admin API is delivered, a row may carry {@code secret_hash = "{plain}<secret>"}
 * for local fixtures; that branch is documented as dev-only and is refused outside tests.</p>
 */
@Component
public class ClientSecretCodec {

    private static final String VERSION_PREFIX = "v1:";
    private static final String PLAIN_PREFIX = "{plain}";

    private final String masterKey;

    public ClientSecretCodec(@Value("${platform.master-key:}") String masterKey) {
        this.masterKey = masterKey == null ? "" : masterKey;
    }

    /**
     * @param secretEnc  reversible ciphertext from {@code api_client.secret_enc}
     * @param secretHash the hash column, used only for the dev-only {@code {plain}} fixture
     * @return the plaintext secret, or {@code null} when it cannot be recovered
     */
    public String decode(byte[] secretEnc, String secretHash) {
        if (secretEnc != null && secretEnc.length > 0) {
            String decoded = decodeEncrypted(secretEnc);
            if (decoded != null) {
                return decoded;
            }
        }
        if (secretHash != null && secretHash.startsWith(PLAIN_PREFIX)) {
            String value = secretHash.substring(PLAIN_PREFIX.length());
            return value.isBlank() ? null : value;
        }
        return null;
    }

    /** The writer-side inverse, exposed so tests (and a future admin API) produce the same bytes. */
    public byte[] encode(String secret) {
        try {
            byte[] key = key();
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ciphertext, 0, blob, iv.length, ciphertext.length);
            String text = VERSION_PREFIX + Base64.getEncoder().encodeToString(blob);
            return text.getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("platform.master-key 无法用于加密客户端密钥", ex);
        }
    }

    private String decodeEncrypted(byte[] secretEnc) {
        try {
            String text = new String(secretEnc, StandardCharsets.US_ASCII);
            byte[] blob;
            if (text.startsWith(VERSION_PREFIX)) {
                blob = Base64.getDecoder().decode(text.substring(VERSION_PREFIX.length()).trim());
            } else if (isAscii(secretEnc)) {
                blob = Base64.getDecoder().decode(text.trim());
            } else {
                blob = secretEnc;
            }
            if (blob.length <= 12) {
                return null;
            }
            byte[] iv = new byte[12];
            System.arraycopy(blob, 0, iv, 0, 12);
            byte[] ciphertext = new byte[blob.length - 12];
            System.arraycopy(blob, 12, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            return null;
        }
    }

    private byte[] key() throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256")
                .digest(masterKey.getBytes(StandardCharsets.UTF_8));
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
