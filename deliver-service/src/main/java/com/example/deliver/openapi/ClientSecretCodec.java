package com.example.deliver.openapi;

import com.example.platform.common.crypto.AesGcmCodec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Recovers the HMAC secret of an API client.
 *
 * <p>Verifying an HMAC needs the key itself, so the client's secret cannot be stored as a hash
 * only: {@code secret_hash} exists for display/audit and {@code secret_enc} carries the
 * reversible copy (AES-256-GCM, key derived from {@code platform.master-key}).</p>
 *
 * <p>The cryptography and the wire format live in {@link AesGcmCodec} — req-service writes the
 * integration credentials with the same class, and a second copy here would be a second chance
 * for the two sides to drift (a rotated key would then decode on one side only). This class is
 * the deliver-side facade: it keeps the historic public signatures and the dev-only
 * {@code {plain}} fixture branch, and delegates everything else.</p>
 */
@Component
public class ClientSecretCodec {

    private static final String PLAIN_PREFIX = "{plain}";

    private final AesGcmCodec codec;

    public ClientSecretCodec(@Value("${platform.master-key:}") String masterKey) {
        this.codec = AesGcmCodec.ofKey(masterKey == null ? "" : masterKey);
    }

    /**
     * @param secretEnc  reversible ciphertext from {@code api_client.secret_enc}
     * @param secretHash the hash column, used only for the dev-only {@code {plain}} fixture
     * @return the plaintext secret, or {@code null} when it cannot be recovered
     */
    public String decode(byte[] secretEnc, String secretHash) {
        String decoded = codec.decode(secretEnc);
        if (decoded != null) {
            return decoded;
        }
        if (secretHash != null && secretHash.startsWith(PLAIN_PREFIX)) {
            String value = secretHash.substring(PLAIN_PREFIX.length());
            return value.isBlank() ? null : value;
        }
        return null;
    }

    /** The writer-side inverse, exposed so tests (and a future admin API) produce the same bytes. */
    public byte[] encode(String secret) {
        return codec.encode(secret);
    }
}
