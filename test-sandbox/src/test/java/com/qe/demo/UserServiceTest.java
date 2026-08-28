package com.qe.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link UserService}.
 *
 * <p>UserService uses an in-memory HashMap as its store, so no mocking is needed —
 * a fresh instance is created before each test via {@code @BeforeEach}.
 */
class UserServiceTest {

    private UserService userService;

    @BeforeEach
    void setUp() {
        // Fresh store for every test — no state leaks between tests
        userService = new UserService();
    }

    // =========================================================================
    // createUser
    // =========================================================================

    @Test
    void createUser_validInputs_returnsCreatedUser() {
        User user = userService.createUser("u-001", "Alice", "alice@example.com");

        assertNotNull(user);
        assertEquals("u-001", user.userId());
        assertEquals("Alice", user.username());
        assertEquals("alice@example.com", user.email());
    }

    @Test
    void createUser_validInputs_persistsUserInStore() {
        userService.createUser("u-001", "Alice", "alice@example.com");

        // Initial count = 0 + createUser = 1
        assertEquals(1, userService.getUserCount());
    }

    @Test
    void createUser_nullUserId_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> userService.createUser(null, "Alice", "alice@example.com"));
    }

    @Test
    void createUser_blankUsername_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> userService.createUser("u-001", "   ", "alice@example.com"));
    }

    @Test
    void createUser_invalidEmail_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> userService.createUser("u-001", "Alice", "not-an-email"));
    }

    @Test
    void createUser_duplicateUserId_throwsIllegalStateException() {
        userService.createUser("u-001", "Alice", "alice@example.com");

        assertThrows(IllegalStateException.class,
                () -> userService.createUser("u-001", "Bob", "bob@example.com"));
    }

    // =========================================================================
    // findById
    // =========================================================================

    @Test
    void findById_existingUser_returnsNonEmptyOptional() {
        userService.createUser("u-001", "Alice", "alice@example.com");

        Optional<User> result = userService.findById("u-001");

        assertTrue(result.isPresent());
        assertEquals("Alice", result.get().username());
    }

    @Test
    void findById_nonExistentUser_returnsEmptyOptional() {
        Optional<User> result = userService.findById("does-not-exist");

        assertFalse(result.isPresent());
    }

    @Test
    void findById_nullId_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> userService.findById(null));
    }

    // =========================================================================
    // getAllUsers
    // =========================================================================

    @Test
    void getAllUsers_emptyStore_returnsEmptyList() {
        List<User> users = userService.getAllUsers();

        assertNotNull(users);
        assertTrue(users.isEmpty());
    }

    @Test
    void getAllUsers_afterAddingTwoUsers_returnsListOfSizeTwo() {
        userService.createUser("u-001", "Alice", "alice@example.com");
        userService.createUser("u-002", "Bob", "bob@example.com");

        // Initial 0 + 2 creates = 2
        assertEquals(2, userService.getAllUsers().size());
    }

    // =========================================================================
    // updateUsername
    // =========================================================================

    @Test
    void updateUsername_existingUser_updatesAndReturnsNewUser() {
        userService.createUser("u-001", "Alice", "alice@example.com");

        User updated = userService.updateUsername("u-001", "AliceUpdated");

        assertEquals("AliceUpdated", updated.username());
        // Email must remain unchanged
        assertEquals("alice@example.com", updated.email());
    }

    @Test
    void updateUsername_nonExistentUser_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class,
                () -> userService.updateUsername("ghost", "NewName"));
    }

    @Test
    void updateUsername_blankNewUsername_throwsIllegalArgumentException() {
        userService.createUser("u-001", "Alice", "alice@example.com");

        assertThrows(IllegalArgumentException.class,
                () -> userService.updateUsername("u-001", ""));
    }

    // =========================================================================
    // deleteUser
    // =========================================================================

    @Test
    void deleteUser_existingUser_returnsTrue() {
        userService.createUser("u-001", "Alice", "alice@example.com");

        boolean deleted = userService.deleteUser("u-001");

        assertTrue(deleted);
    }

    @Test
    void deleteUser_existingUser_reducesCount() {
        userService.createUser("u-001", "Alice", "alice@example.com");
        userService.createUser("u-002", "Bob", "bob@example.com");
        // Count before delete: 0 + 2 creates = 2

        userService.deleteUser("u-001");
        // Count after delete: 2 - 1 = 1

        assertEquals(1, userService.getUserCount());
    }

    @Test
    void deleteUser_nonExistentUser_returnsFalse() {
        boolean deleted = userService.deleteUser("ghost");

        assertFalse(deleted);
    }

    // =========================================================================
    // getUserCount
    // =========================================================================

    @Test
    void getUserCount_emptyStore_returnsZero() {
        assertEquals(0, userService.getUserCount());
    }

    @Test
    void getUserCount_afterAddingThreeUsers_returnsThree() {
        userService.createUser("u-001", "Alice", "alice@example.com");
        userService.createUser("u-002", "Bob", "bob@example.com");
        userService.createUser("u-003", "Charlie", "charlie@example.com");
        // Initial 0 + 3 creates = 3

        assertEquals(3, userService.getUserCount());
    }

    // =========================================================================
    // isValidEmail (package-private — accessible from same package)
    // =========================================================================

    @Test
    void isValidEmail_validAddress_returnsTrue() {
        assertTrue(userService.isValidEmail("user@example.com"));
    }

    @Test
    void isValidEmail_missingAt_returnsFalse() {
        assertFalse(userService.isValidEmail("userexample.com"));
    }

    @Test
    void isValidEmail_missingDomain_returnsFalse() {
        assertFalse(userService.isValidEmail("user@"));
    }

    @Test
    void isValidEmail_domainWithNoDot_returnsFalse() {
        assertFalse(userService.isValidEmail("user@nodot"));
    }

    @Test
    void isValidEmail_nullInput_returnsFalse() {
        assertFalse(userService.isValidEmail(null));
    }

    @Test
    void isValidEmail_blankInput_returnsFalse() {
        assertFalse(userService.isValidEmail("   "));
    }
}
