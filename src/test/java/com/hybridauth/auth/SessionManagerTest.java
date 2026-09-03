package com.hybridauth.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    @Test
    void sessionValidForSameIp() {
        SessionManager sessions = new SessionManager(new MutableClock());
        UUID uuid = UUID.randomUUID();

        sessions.createSession("Steve", uuid, "1.2.3.4");
        assertTrue(sessions.hasValidSession("Steve", uuid, "1.2.3.4"));
    }

    @Test
    void sessionInvalidatedByDifferentIp() {
        SessionManager sessions = new SessionManager(new MutableClock());
        UUID uuid = UUID.randomUUID();

        sessions.createSession("Steve", uuid, "1.2.3.4");
        assertFalse(sessions.hasValidSession("Steve", uuid, "5.6.7.8"),
                "Сессия привязана к IP и должна сбрасываться при смене адреса");
        // После сброса старый IP тоже больше не действует (сессия удалена)
        assertFalse(sessions.hasValidSession("Steve", uuid, "1.2.3.4"));
    }

    @Test
    void sessionExpiresAfterDuration() {
        MutableClock clock = new MutableClock();
        SessionManager sessions = new SessionManager(clock);
        sessions.setSessionDurationMinutes(60);
        UUID uuid = UUID.randomUUID();

        sessions.createSession("Steve", uuid, "1.2.3.4");
        clock.advanceMinutes(61);
        assertFalse(sessions.hasValidSession("Steve", uuid, "1.2.3.4"), "После длительности сессия истекает");
    }

    @Test
    void successfulChecksRefreshSlidingWindow() {
        MutableClock clock = new MutableClock();
        SessionManager sessions = new SessionManager(clock);
        sessions.setSessionDurationMinutes(60);
        UUID uuid = UUID.randomUUID();

        sessions.createSession("Steve", uuid, "1.2.3.4");
        clock.advanceMinutes(50);
        assertTrue(sessions.hasValidSession("Steve", uuid, "1.2.3.4"), "В пределах длительности сессия валидна");
        clock.advanceMinutes(50); // Итого 100 минут с создания, но 0 минут с последней проверки
        assertTrue(sessions.hasValidSession("Steve", uuid, "1.2.3.4"),
                "Успешная проверка обновляет окно сессии (скользящее окно)");
    }

    @Test
    void zeroDurationMeansValidUntilRestart() {
        MutableClock clock = new MutableClock();
        SessionManager sessions = new SessionManager(clock);
        sessions.setSessionDurationMinutes(0);
        UUID uuid = UUID.randomUUID();

        sessions.createSession("Steve", uuid, "1.2.3.4");
        clock.advanceMinutes(60 * 24 * 30);
        assertTrue(sessions.hasValidSession("Steve", uuid, "1.2.3.4"),
                "Длительность 0 = сессия до перезапуска сервера");
    }

    @Test
    void disabledManagerRejectsAllSessions() {
        SessionManager sessions = new SessionManager(new MutableClock());
        UUID uuid = UUID.randomUUID();

        sessions.createSession("Steve", uuid, "1.2.3.4");
        sessions.setEnabled(false);
        assertFalse(sessions.hasValidSession("Steve", uuid, "1.2.3.4"));
        assertEquals(0, sessions.activeSessionCount(), "Отключение должно чистить все сессии");
    }

    @Test
    void sessionsArePerPlayer() {
        SessionManager sessions = new SessionManager(new MutableClock());
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();

        sessions.createSession("Steve", steve, "1.2.3.4");
        assertFalse(sessions.hasValidSession("Alex", alex, "1.2.3.4"));
        assertEquals(1, sessions.activeSessionCount());
    }

    @Test
    void endSessionAndClearRemoveEntries() {
        SessionManager sessions = new SessionManager(new MutableClock());
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();

        sessions.createSession("Steve", steve, "1.2.3.4");
        sessions.createSession("Alex", alex, "1.2.3.4");
        sessions.endSession("steve", steve); // регистронезависимо
        assertEquals(1, sessions.activeSessionCount());

        sessions.clear();
        assertEquals(0, sessions.activeSessionCount());
        assertFalse(sessions.hasValidSession("Alex", alex, "1.2.3.4"));
    }

    @Test
    void nullOrBlankIpIsRejected() {
        SessionManager sessions = new SessionManager(new MutableClock());
        UUID uuid = UUID.randomUUID();

        sessions.createSession("Steve", uuid, "  ");
        assertFalse(sessions.hasValidSession("Steve", uuid, "1.2.3.4"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        void advanceMinutes(long minutes) {
            instant = instant.plusSeconds(minutes * 60);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
