package com.qe.demo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
// User is a top-level record in User.java (same package — no import needed)

/**
 * UserService — sample business-logic class used as the target for AI-driven
 * JUnit 5 test generation in the QE-RAG system test-sandbox.
 *
 * <p>Intentionally covers several common test-worthy patterns:
 * <ul>
 *   <li>Null / empty input guards</li>
 *   <li>Simple CRUD-style in-memory operations</li>
 *   <li>Validation logic that throws checked/unchecked exceptions</li>
 *   <li>Delegation between methods (call-graph edges)</li>
 * </ul>
 */
public class UserService {

    // -------------------------------------------------------------------------
    // In-memory "database" (keyed by userId)
    // -------------------------------------------------------------------------
    private final Map<String, User> userStore = new HashMap<>();

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Creates a new {@link User} and persists it in the store.
     *
     * @param userId   non-null, non-blank unique identifier
     * @param username non-null, non-blank display name
     * @param email    non-null string; validated via {@link #isValidEmail(String)}
     * @return the newly created {@link User}
     * @throws IllegalArgumentException if any parameter is null/blank or the email is invalid
     * @throws IllegalStateException    if a user with the same {@code userId} already exists
     */
    public User createUser(String userId, String username, String email) {
        validateNotBlank(userId, "userId");
        validateNotBlank(username, "username");
        validateNotBlank(email, "email");

        if (!isValidEmail(email)) {
            throw new IllegalArgumentException("Invalid email format: " + email);
        }
        if (userStore.containsKey(userId)) {
            throw new IllegalStateException("User already exists with id: " + userId);
        }

        User user = new User(userId, username, email);
        userStore.put(userId, user);
        return user;
    }

    /**
     * Retrieves a user by ID.
     *
     * @param userId non-null, non-blank user identifier
     * @return {@link Optional} containing the user, or empty if not found
     */
    public Optional<User> findById(String userId) {
        validateNotBlank(userId, "userId");
        return Optional.ofNullable(userStore.get(userId));
    }

    /**
     * Returns all users currently in the store.
     *
     * @return immutable snapshot list; never null
     */
    public List<User> getAllUsers() {
        return new ArrayList<>(userStore.values());
    }

    /**
     * Updates the display name of an existing user.
     *
     * @param userId      non-null, non-blank user identifier
     * @param newUsername non-null, non-blank new display name
     * @return the updated {@link User}
     * @throws IllegalArgumentException if inputs are blank
     * @throws IllegalStateException    if the user is not found
     */
    public User updateUsername(String userId, String newUsername) {
        validateNotBlank(userId, "userId");
        validateNotBlank(newUsername, "newUsername");

        User existing = requireUser(userId);
        User updated = new User(existing.userId(), newUsername, existing.email());
        userStore.put(userId, updated);
        return updated;
    }

    /**
     * Deletes a user by ID.
     *
     * @param userId non-null, non-blank user identifier
     * @return {@code true} if a user was removed, {@code false} if no such user existed
     */
    public boolean deleteUser(String userId) {
        validateNotBlank(userId, "userId");
        return userStore.remove(userId) != null;
    }

    /**
     * Returns the total number of registered users.
     *
     * @return count ≥ 0
     */
    public int getUserCount() {
        return userStore.size();
    }

    // =========================================================================
    // Validation helpers (package-private for unit-test access)
    // =========================================================================

    /**
     * Checks whether an email string contains exactly one '@' with non-empty
     * local-part and domain sections.
     *
     * @param email candidate email string
     * @return {@code true} if the format is acceptable
     */
    boolean isValidEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String[] parts = email.split("@", -1);
        return parts.length == 2
                && !parts[0].isBlank()
                && parts[1].contains(".")
                && !parts[1].startsWith(".")
                && !parts[1].endsWith(".");
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
    }

    private User requireUser(String userId) {
        User user = userStore.get(userId);
        if (user == null) {
            throw new IllegalStateException("No user found with id: " + userId);
        }
        return user;
    }

    // User value object is defined in User.java (top-level record, same package).
}
