package com.qe.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    private SessionManager sessionManager;
    private static final long TTL_MILLIS = 500L;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager(TTL_MILLIS);
    }

    @Test
    @DisplayName("createSession adds session and returns valid token")
    void testCreateSession() {
        String token = sessionManager.createSession("tok-123", "usr-456", "john_doe");

        assertEquals("tok-123", token);
        assertEquals(1, sessionManager.getActiveSessionCount());

        Optional<SessionManager.SessionInfo> info = sessionManager.validateAndRefresh("tok-123");
        assertTrue(info.isPresent());
        assertEquals("usr-456", info.get().userId());
        assertEquals("john_doe", info.get().username());
    }

    @Test
    @DisplayName("validateAndRefresh returns empty for non-existent token")
    void testValidateUnknownToken() {
        Optional<SessionManager.SessionInfo> info = sessionManager.validateAndRefresh("invalid-token");
        assertFalse(info.isPresent());
    }

    @Test
    @DisplayName("invalidate removes active session and decrements count")
    void testInvalidateSession() {
        sessionManager.createSession("tok-1", "u1", "alice");
        sessionManager.createSession("tok-2", "u2", "bob");
        assertEquals(2, sessionManager.getActiveSessionCount());

        boolean removed = sessionManager.invalidate("tok-1");
        assertTrue(removed);
        assertEquals(1, sessionManager.getActiveSessionCount());
        assertFalse(sessionManager.validateAndRefresh("tok-1").isPresent());
    }

    @Test
    @DisplayName("createSession throws NullPointerException when arguments are null")
    void testCreateSessionNullChecks() {
        assertThrows(NullPointerException.class, () ->
            sessionManager.createSession(null, "u1", "alice"));

        assertThrows(NullPointerException.class, () ->
            sessionManager.createSession("tok-1", null, "alice"));

        assertThrows(NullPointerException.class, () ->
            sessionManager.createSession("tok-1", "u1", null));
    }

    @Test
    @DisplayName("clearAll removes all active sessions")
    void testClearAll() {
        sessionManager.createSession("tok-1", "u1", "alice");
        sessionManager.createSession("tok-2", "u2", "bob");
        assertEquals(2, sessionManager.getActiveSessionCount());

        sessionManager.clearAll();
        assertEquals(0, sessionManager.getActiveSessionCount());
    }
}
