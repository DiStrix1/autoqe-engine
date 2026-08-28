package com.qe.demo;

/**
 * Immutable value object representing a user entity in the QE demo domain.
 *
 * <p>Extracted as a top-level record so that generated JUnit 5 tests can
 * reference it directly as {@code User} without qualifying the outer class.
 *
 * @param userId   unique identifier
 * @param username display name
 * @param email    contact address
 */
public record User(String userId, String username, String email) {}
