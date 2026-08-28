package com.qe.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link User} record.
 *
 * <p>{@code User} is an immutable Java record, so tests verify:
 * <ul>
 *   <li>Canonical constructor correctly stores all components.</li>
 *   <li>Accessor methods return the expected values.</li>
 *   <li>Record {@code equals()} and {@code hashCode()} follow value semantics.</li>
 *   <li>Record {@code toString()} includes all components.</li>
 * </ul>
 */
class UserTest {

    // -------------------------------------------------------------------------
    // Constructor & accessors
    // -------------------------------------------------------------------------

    @Test
    void constructor_validValues_storesAllComponents() {
        User user = new User("u-001", "Alice", "alice@example.com");

        assertEquals("u-001", user.userId());
        assertEquals("Alice", user.username());
        assertEquals("alice@example.com", user.email());
    }

    @Test
    void constructor_emptyStrings_storesEmptyValues() {
        // User record has no validation — blank values are accepted
        User user = new User("", "", "");

        assertEquals("", user.userId());
        assertEquals("", user.username());
        assertEquals("", user.email());
    }

    // -------------------------------------------------------------------------
    // equals — value semantics
    // -------------------------------------------------------------------------

    @Test
    void equals_sameValues_returnsTrue() {
        User a = new User("u-001", "Alice", "alice@example.com");
        User b = new User("u-001", "Alice", "alice@example.com");

        assertEquals(a, b);
    }

    @Test
    void equals_differentUserId_returnsFalse() {
        User a = new User("u-001", "Alice", "alice@example.com");
        User b = new User("u-002", "Alice", "alice@example.com");

        assertNotEquals(a, b);
    }

    @Test
    void equals_differentUsername_returnsFalse() {
        User a = new User("u-001", "Alice", "alice@example.com");
        User b = new User("u-001", "Bob", "alice@example.com");

        assertNotEquals(a, b);
    }

    @Test
    void equals_differentEmail_returnsFalse() {
        User a = new User("u-001", "Alice", "alice@example.com");
        User b = new User("u-001", "Alice", "other@example.com");

        assertNotEquals(a, b);
    }

    // -------------------------------------------------------------------------
    // hashCode — consistent with equals
    // -------------------------------------------------------------------------

    @Test
    void hashCode_equalRecords_sameHashCode() {
        User a = new User("u-001", "Alice", "alice@example.com");
        User b = new User("u-001", "Alice", "alice@example.com");

        assertEquals(a.hashCode(), b.hashCode());
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    @Test
    void toString_containsAllComponents() {
        User user = new User("u-001", "Alice", "alice@example.com");
        String s = user.toString();

        assertTrue(s.contains("u-001"), "toString should contain userId");
        assertTrue(s.contains("Alice"), "toString should contain username");
        assertTrue(s.contains("alice@example.com"), "toString should contain email");
    }
}
