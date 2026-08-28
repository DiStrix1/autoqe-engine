package com.qe.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class StringValidatorTest {

    private StringValidator validator;

    @BeforeEach
    void setUp() {
        validator = new StringValidator();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user@example.com", "first.last@domain.co.uk", "qe_tester+1@cloud.io"})
    @DisplayName("isValidEmail returns true for standard valid email formats")
    void testValidEmails(String email) {
        assertTrue(validator.isValidEmail(email));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "plainaddress", "missing@domain", "@nodomain.com", "user@.com"})
    @DisplayName("isValidEmail returns false for malformed emails")
    void testInvalidEmails(String email) {
        assertFalse(validator.isValidEmail(email));
    }

    @Test
    @DisplayName("isValidEmail returns false when given null")
    void testEmailNull() {
        assertFalse(validator.isValidEmail(null));
    }

    @Test
    @DisplayName("isStrongPassword requires upper, lower, digit, special, and length >= 8")
    void testPasswordStrength() {
        assertTrue(validator.isStrongPassword("P@ssw0rd123"));
        assertFalse(validator.isStrongPassword("short1!"));
        assertFalse(validator.isStrongPassword("alllowercase123!"));
        assertFalse(validator.isStrongPassword("ALLUPPERCASE123!"));
        assertFalse(validator.isStrongPassword("NoSpecialChars123"));
        assertFalse(validator.isStrongPassword(null));
    }

    @Test
    @DisplayName("isAlphanumeric validates strings containing only letters and numbers")
    void testAlphanumeric() {
        assertTrue(validator.isAlphanumeric("AlphaNumeric123"));
        assertFalse(validator.isAlphanumeric("Has-Hyphen"));
        assertFalse(validator.isAlphanumeric("Has Space"));
        assertFalse(validator.isAlphanumeric(null));
        assertFalse(validator.isAlphanumeric(""));
    }

    @Test
    @DisplayName("sanitize escapes HTML tags and quotes")
    void testSanitize() {
        assertEquals("&lt;script&gt;alert(&quot;xss&quot;)&lt;/script&gt;",
                validator.sanitize("<script>alert(\"xss\")</script>"));
        assertEquals("", validator.sanitize(null));
        assertEquals("Clean Text", validator.sanitize("   Clean Text   "));
    }

    @Test
    @DisplayName("validateCreditCardLuhn correctly computes checksum on card numbers")
    void testLuhnAlgorithm() {
        // Valid 16-digit test visa number (passes Luhn)
        assertTrue(validator.validateCreditCardLuhn("49927398716"));
        assertTrue(validator.validateCreditCardLuhn("4992-7398-716"));

        // Invalid checksum
        assertFalse(validator.validateCreditCardLuhn("49927398717"));
        assertFalse(validator.validateCreditCardLuhn(null));
        assertFalse(validator.validateCreditCardLuhn("1234"));
    }
}
