package com.qe.demo;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private final Map<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();
    private final long sessionTtlMillis;

    public record SessionInfo(String userId, String username, long createdAt, long lastAccessedAt) {}

    public SessionManager(long sessionTtlMillis) {
        if (sessionTtlMillis <= 0) {
            throw new IllegalArgumentException("Session TTL must be positive");
        }
        this.sessionTtlMillis = sessionTtlMillis;
    }

    public String createSession(String token, String userId, String username) {
        Objects.requireNonNull(token, "Token cannot be null");
        Objects.requireNonNull(userId, "UserId cannot be null");
        Objects.requireNonNull(username, "Username cannot be null");

        if (token.isBlank() || userId.isBlank()) {
            throw new IllegalArgumentException("Token and UserId cannot be blank");
        }

        long now = System.currentTimeMillis();
        activeSessions.put(token, new SessionInfo(userId, username, now, now));
        return token;
    }

    public Optional<SessionInfo> validateAndRefresh(String token) {
        if (token == null || !activeSessions.containsKey(token)) {
            return Optional.empty();
        }

        SessionInfo session = activeSessions.get(token);
        long now = System.currentTimeMillis();

        if (now - session.lastAccessedAt() > sessionTtlMillis) {
            activeSessions.remove(token);
            return Optional.empty();
        }

        SessionInfo refreshed = new SessionInfo(session.userId(), session.username(), session.createdAt(), now);
        activeSessions.put(token, refreshed);
        return Optional.of(refreshed);
    }

    public boolean invalidate(String token) {
        if (token == null) {
            return false;
        }
        return activeSessions.remove(token) != null;
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    public void clearAll() {
        activeSessions.clear();
    }
}
