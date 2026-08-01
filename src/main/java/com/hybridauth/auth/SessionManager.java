package com.hybridauth.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private record SessionKey(UUID uuid, String username) {
        private SessionKey {
            username = username.toLowerCase(Locale.ROOT);
        }
    }

    private static class Session {
        private final String ipAddress;
        private final Instant lastSeen;

        private Session(String ipAddress) {
            this.ipAddress = ipAddress;
            this.lastSeen = Instant.now();
        }
    }

    private final Map<SessionKey, Session> activeSessions = new ConcurrentHashMap<>();
    private int sessionDurationMinutes = 720;
    private boolean enabled = true;

    public void setSessionDurationMinutes(int sessionDurationMinutes) {
        this.sessionDurationMinutes = sessionDurationMinutes;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            activeSessions.clear();
        }
    }

    public void createSession(String username, UUID uuid, String ipAddress) {
        if (!enabled || username == null || uuid == null || ipAddress == null || ipAddress.isBlank()) {
            return;
        }

        activeSessions.put(new SessionKey(uuid, username), new Session(ipAddress));
    }

    public boolean hasValidSession(String username, UUID uuid, String ipAddress) {
        if (!enabled || username == null || uuid == null || ipAddress == null || ipAddress.isBlank()) {
            return false;
        }

        SessionKey key = new SessionKey(uuid, username);
        Session session = activeSessions.get(key);
        if (session == null) {
            return false;
        }

        if (!session.ipAddress.equals(ipAddress)) {
            activeSessions.remove(key);
            return false;
        }

        if (sessionDurationMinutes == 0) {
            return true;
        }

        if (Instant.now().isBefore(session.lastSeen.plus(sessionDurationMinutes, ChronoUnit.MINUTES))) {
            createSession(username, uuid, ipAddress);
            return true;
        }

        activeSessions.remove(key);
        return false;
    }

    public void endSession(String username, UUID uuid) {
        if (username != null && uuid != null) {
            activeSessions.remove(new SessionKey(uuid, username));
        }
    }

    public void clear() {
        activeSessions.clear();
    }
}
