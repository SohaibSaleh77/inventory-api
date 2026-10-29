package com.ims.unit;

import com.ims.security.PasswordHasher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PBKDF2 password hashing (fast iteration count for tests). */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(50_000, 16);

    @Test
    void hashAndVerifyRoundTrip() {
        String hash = hasher.hash("Sup3rSecret!42");
        assertTrue(hasher.verify("Sup3rSecret!42", hash));
        assertFalse(hasher.verify("wrong", hash));
    }

    @Test
    void hashIsSaltedAndUnique() {
        String a = hasher.hash("same-password");
        String b = hasher.hash("same-password");
        assertNotEquals(a, b, "each hash must use a fresh salt");
        assertTrue(hasher.verify("same-password", a));
        assertTrue(hasher.verify("same-password", b));
    }

    @Test
    void hashFormatIsStable() {
        String hash = hasher.hash("password123");
        assertTrue(hash.startsWith("pbkdf2-sha256$"));
        String[] parts = hash.split("\\$");
        assertEquals(4, parts.length);
        assertEquals("50000", parts[1]);
    }

    @Test
    void verifyRejectsGarbage() {
        assertFalse(hasher.verify("x", null));
        assertFalse(hasher.verify("x", ""));
        assertFalse(hasher.verify("x", "not-a-hash"));
        assertFalse(hasher.verify("x", "pbkdf2-sha256$not$numbers$$"));
        assertFalse(hasher.verify(null, "pbkdf2-sha256$1$a$b"));
    }

    @Test
    void policyChecks() {
        assertNull(PasswordHasher.policyViolation("goodPass123"));
        assertEquals("Password must be at least 10 characters long",
                PasswordHasher.policyViolation("short1"));
        assertEquals("Password must contain at least one letter",
                PasswordHasher.policyViolation("12345678901"));
        assertEquals("Password must contain at least one digit",
                PasswordHasher.policyViolation("abcdefghijk"));
        assertNull(PasswordHasher.policyViolation("PasswordWith99Digits"));
    }
}
