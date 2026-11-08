package com.example.req.sys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Password hashing.
 *
 * <p>BCrypt is the format the seed data already uses, and it is what every new account gets.
 * A {@code pbkdf2$…} verifier is kept only so that accounts created by an older build keep
 * working; nothing writes it any more.</p>
 */
@Component
public class PasswordService {

    private static final int PBKDF2_ITERATIONS = 120_000;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public String encode(String raw) {
        return encoder.encode(raw);
    }

    public boolean matches(String raw, String stored) {
        if (raw == null || stored == null || stored.isBlank()) {
            return false;
        }
        if (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) {
            return encoder.matches(raw, stored);
        }
        if (stored.startsWith("pbkdf2$")) {
            return matchesPbkdf2(raw, stored);
        }
        return false;
    }

    private boolean matchesPbkdf2(String raw, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, expected.length * 8);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException | java.security.GeneralSecurityException ex) {
            return false;
        }
    }

    /** Only used by tests that need a deterministic, cheap check of the format. */
    public static String pbkdf2ForTest(String raw, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(),
                    salt.getBytes(StandardCharsets.UTF_8), PBKDF2_ITERATIONS, 256);
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return "pbkdf2$" + PBKDF2_ITERATIONS + "$"
                    + Base64.getEncoder().encodeToString(salt.getBytes(StandardCharsets.UTF_8))
                    + "$" + Base64.getEncoder().encodeToString(hash);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
