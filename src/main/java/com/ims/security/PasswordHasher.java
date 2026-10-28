package com.ims.security;

import com.ims.common.exception.InternalErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Password hashing with PBKDF2-HMAC-SHA256 (NIST SP 800-132 compliant).
 * Output format: pbkdf2-sha256$iterations$base64(salt)$base64(dk)
 * Verification is constant-time.
 */
public final class PasswordHasher {

    private static final Logger log = LoggerFactory.getLogger(PasswordHasher.class);
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int KEY_LENGTH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int iterations;
    private final int saltBytes;

    public PasswordHasher(int iterations, int saltBytes) {
        if (iterations < 100_000) {
            log.warn("PBKDF2 iterations below 100k ({}); acceptable for tests only", iterations);
        }
        this.iterations = iterations;
        this.saltBytes = saltBytes;
    }

    public String hash(String password) {
        byte[] salt = new byte[saltBytes];
        RANDOM.nextBytes(salt);
        byte[] derived = pbkdf2(password, salt, iterations);
        return "pbkdf2-sha256$" + iterations
                + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derived);
    }

    /** Constant-time verification against a stored hash. */
    public boolean verify(String password, String storedHash) {
        if (password == null || storedHash == null || !storedHash.startsWith("pbkdf2-sha256$")) {
            return false;
        }
        try {
            String[] parts = storedHash.split("\\$");
            int storedIterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] storedKey = Base64.getDecoder().decode(parts[3]);
            byte[] candidate = pbkdf2(password, salt, storedIterations);
            return MessageDigest.isEqual(storedKey, candidate);
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    /**
     * Policy check for new passwords: min 10 chars, at least one letter and
     * one digit. Returns an error message or null when acceptable.
     */
    public static String policyViolation(String password) {
        if (password == null || password.length() < 10) {
            return "Password must be at least 10 characters long";
        }
        if (password.length() > 128) {
            return "Password must not exceed 128 characters";
        }
        if (!password.matches(".*[A-Za-z].*")) {
            return "Password must contain at least one letter";
        }
        if (!password.matches(".*[0-9].*")) {
            return "Password must contain at least one digit";
        }
        return null;
    }

    private byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt,
                    iterations, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new InternalErrorException("Password hashing unavailable", e);
        }
    }
}
