package com.ims.unit;

import com.ims.common.exception.ValidationException;
import com.ims.common.util.Validators;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Field validation helpers. */
class ValidatorsTest {

    @Test
    void requireTextTrimsAndRejectsBlank() {
        assertEquals("hello", Validators.requireText(" hello ", "f", 10));
        assertThrows(ValidationException.class, () -> Validators.requireText("  ", "f", 10));
        assertThrows(ValidationException.class, () -> Validators.requireText(null, "f", 10));
    }

    @Test
    void requireTextEnforcesMaxLength() {
        assertThrows(ValidationException.class,
                () -> Validators.requireText("123456", "f", 5));
    }

    @Test
    void codeRejectsSpecialCharacters() {
        assertThrows(ValidationException.class,
                () -> Validators.requireCode("bad code!", "f", 10));
        assertThrows(ValidationException.class,
                () -> Validators.requireCode("!!", "f", 10));
        assertEquals("GOOD-1.2", Validators.requireCode("good-1.2", "f", 10));
    }

    @Test
    void emailValidation() {
        assertNull(Validators.optionalEmail(null, "f"));
        assertNull(Validators.optionalEmail("  ", "f"));
        assertEquals("a@b.co", Validators.optionalEmail(" a@b.co ", "f"));
        assertThrows(ValidationException.class,
                () -> Validators.optionalEmail("not-an-email", "f"));
        assertThrows(ValidationException.class,
                () -> Validators.optionalEmail("a@b", "f"));
    }

    @Test
    void numericGuards() {
        assertThrows(ValidationException.class,
                () -> Validators.requirePositive(BigDecimal.ZERO, "f"));
        assertThrows(ValidationException.class,
                () -> Validators.requirePositive(new BigDecimal("-1"), "f"));
        assertThrows(ValidationException.class,
                () -> Validators.requireNonNegative(new BigDecimal("-0.01"), "f"));
        assertEquals(BigDecimal.ONE, Validators.requirePositive(BigDecimal.ONE, "f"));
    }

    @Test
    void intRangeGuard() {
        assertEquals(3, Validators.requireIntInRange(3, "f", 1, 5));
        assertThrows(ValidationException.class,
                () -> Validators.requireIntInRange(0, "f", 1, 5));
        assertThrows(ValidationException.class,
                () -> Validators.requireIntInRange(6, "f", 1, 5));
        assertThrows(ValidationException.class,
                () -> Validators.requireIntInRange(null, "f", 1, 5));
    }

    @Test
    void positiveIdGuard() {
        assertEquals(5L, Validators.requirePositiveId(5L, "f"));
        assertThrows(ValidationException.class,
                () -> Validators.requirePositiveId(0L, "f"));
        assertThrows(ValidationException.class,
                () -> Validators.requirePositiveId(null, "f"));
    }

    @Test
    void oneOfGuard() {
        assertEquals("A", Validators.requireOneOf("A", "f", Set.of("A", "B")));
        assertThrows(ValidationException.class,
                () -> Validators.requireOneOf("C", "f", Set.of("A", "B")));
    }

    @Test
    void validationExceptionCarriesField() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> Validators.requireText("", "myField", 5));
        assertEquals("must not be blank", e.fieldErrors().get("myField"),
                "the field name must map to its error message");
        assertTrue(e.fieldErrors().containsKey("myField"));
        assertNotNull(e.getMessage());
        assertTrue(e.httpStatus() == 422);
    }
}
