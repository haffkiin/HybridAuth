package com.hybridauth.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LoginRateLimiterTest {

    // ──────────────────────────────────────────────────────────────────────
    // Core lock/reconnect/expiry
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void lockSurvivesReconnectAndExpires() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(300, 300, clock);
        UUID uuid = UUID.randomUUID();

        assertTrue(limiter.recordFailure(uuid, "Player", "127.0.0.1", 3).allowed());
        assertTrue(limiter.recordFailure(uuid, "player", "127.0.0.1", 3).allowed()); // case-insensitive name
        LoginRateLimiter.Result locked = limiter.recordFailure(uuid, "PLAYER", "127.0.0.1", 3);
        assertFalse(locked.allowed(), "Should be locked after maxAttempts");
        assertFalse(limiter.status(uuid, "Player", "127.0.0.1", 3).allowed(), "Lock persists across 'reconnects'");

        clock.advanceSeconds(301);
        assertTrue(limiter.status(uuid, "Player", "127.0.0.1", 3).allowed(), "Lock should expire after lockout period");
    }

    @Test
    void successfulLoginClearsOnlyMatchingIdentityAndIp() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(300, 300, clock);
        UUID uuid = UUID.randomUUID();
        limiter.recordFailure(uuid, "Player", "10.0.0.1", 5);
        limiter.recordFailure(uuid, "Player", "10.0.0.2", 5);

        limiter.clear(uuid, "Player", "10.0.0.1");

        assertEquals(0, limiter.status(uuid, "Player", "10.0.0.1", 5).attempts(),
                "Cleared IP should have 0 attempts");
        assertEquals(1, limiter.status(uuid, "Player", "10.0.0.2", 5).attempts(),
                "Other IP should still have its attempt count");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Different IPs are tracked independently (until the name-level limit trips)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void differentIpsTrackedIndependently() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(300, 300, clock);
        UUID uuid = UUID.randomUUID();

        // Lock from IP1
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(uuid, "Alice", "1.1.1.1", 3);
        }
        assertFalse(limiter.status(uuid, "Alice", "1.1.1.1", 3).allowed(), "IP1 should be locked");
        assertTrue(limiter.status(uuid, "Alice", "2.2.2.2", 3).allowed(),
                "IP2 should stay allowed while the name-level counter is below its threshold");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Name-level limit defeats IP rotation
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void nameLevelLockBlocksRotatedIps() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(300, 300, clock);
        UUID uuid = UUID.randomUUID();
        int nameMaxAttempts = 3 * 3; // maxAttempts * NAME_MAX_ATTEMPTS_MULTIPLIER

        // Атакующий меняет IP на каждую попытку — per-IP лимит не срабатывает,
        // но глобальный счётчик по имени накапливается
        for (int i = 0; i < nameMaxAttempts - 1; i++) {
            assertTrue(limiter.recordFailure(uuid, "Bob", "10.0.0." + i, 3).allowed(),
                    "Attempt " + i + " from a fresh IP should not be identity-locked");
        }
        LoginRateLimiter.Result locked = limiter.recordFailure(uuid, "Bob", "10.0.0.999", 3);
        assertFalse(locked.allowed(), "Name-level counter should lock after threshold");
        assertFalse(limiter.status(UUID.randomUUID(), "BOB", "10.9.9.9", 3).allowed(),
                "Even a different UUID and IP is blocked once the name is locked");
    }

    @Test
    void nameLevelLockoutExpires() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(300, 300, clock);
        UUID uuid = UUID.randomUUID();

        for (int i = 0; i < 9; i++) {
            limiter.recordFailure(uuid, "Carol", "10.0.0." + i, 3);
        }
        assertFalse(limiter.status(uuid, "Carol", "10.0.0.100", 3).allowed());

        clock.advanceSeconds(301);
        assertTrue(limiter.status(uuid, "Carol", "10.0.0.100", 3).allowed(),
                "Name-level lockout should expire like the identity lockout");
    }

    @Test
    void successfulLoginClearsNameLevelCounter() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(300, 300, clock);
        UUID uuid = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(uuid, "Dave", "10.0.0." + i, 3);
        }
        assertTrue(limiter.status(uuid, "Dave", "10.0.0.100", 3).allowed(),
                "Below the name-level threshold attempts from a new IP stay allowed");

        limiter.clear(uuid, "Dave", "10.0.0.0");
        assertEquals(0, limiter.status(UUID.randomUUID(), "Dave", "10.0.0.200", 3).attempts(),
                "Successful login should reset the name-level counter too");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lockout period behaviour
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void lockoutExpiresAfterWindowAndLockout() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(60, 30, clock); // 60s window, 30s lockout
        UUID uuid = UUID.randomUUID();

        // Max out attempts
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(uuid, "Bob", "3.3.3.3", 3);
        }
        assertFalse(limiter.status(uuid, "Bob", "3.3.3.3", 3).allowed(), "Should be locked immediately");

        clock.advanceSeconds(29);
        assertFalse(limiter.status(uuid, "Bob", "3.3.3.3", 3).allowed(), "Still locked at 29s (within 30s lockout)");

        // Advance past both the lockout (30s) and the window (60s)
        clock.advanceSeconds(40); // total = 69s > window (60s) and > lockout (30s)
        assertTrue(limiter.status(uuid, "Bob", "3.3.3.3", 3).allowed(), "Unlocked at 69s: both lockout and window expired");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Window expiry removes old attempts
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void attemptsExpireAfterWindow() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(60, 300, clock); // 60s window
        UUID uuid = UUID.randomUUID();

        limiter.recordFailure(uuid, "Carol", "5.5.5.5", 3);
        limiter.recordFailure(uuid, "Carol", "5.5.5.5", 3);
        assertEquals(2, limiter.status(uuid, "Carol", "5.5.5.5", 3).attempts());

        clock.advanceSeconds(61); // slide past window
        assertEquals(0, limiter.status(uuid, "Carol", "5.5.5.5", 3).attempts(),
                "Old attempts outside window should be pruned");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Success clears rate-limit entry (simulate successful login)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void clearAfterSuccessfulLoginResetsCounter() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(300, 300, clock);
        UUID uuid = UUID.randomUUID();

        limiter.recordFailure(uuid, "Dave", "6.6.6.6", 5);
        limiter.recordFailure(uuid, "Dave", "6.6.6.6", 5);
        assertEquals(2, limiter.status(uuid, "Dave", "6.6.6.6", 5).attempts());

        limiter.clear(uuid, "Dave", "6.6.6.6");
        assertEquals(0, limiter.status(uuid, "Dave", "6.6.6.6", 5).attempts(),
                "Attempts should be reset after clear (successful login)");
        assertTrue(limiter.status(uuid, "Dave", "6.6.6.6", 5).allowed());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Recovery: after lockout expires, new failures start fresh window
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void afterLockoutExpiresNewAttemptsCanLockAgain() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(300, 60, clock);
        UUID uuid = UUID.randomUUID();

        for (int i = 0; i < 3; i++) limiter.recordFailure(uuid, "Eve", "7.7.7.7", 3);
        assertFalse(limiter.status(uuid, "Eve", "7.7.7.7", 3).allowed());

        clock.advanceSeconds(360); // past both lockout and window
        assertTrue(limiter.status(uuid, "Eve", "7.7.7.7", 3).allowed(), "Should be unlocked after full expiry");

        // New lockout cycle
        for (int i = 0; i < 3; i++) limiter.recordFailure(uuid, "Eve", "7.7.7.7", 3);
        assertFalse(limiter.status(uuid, "Eve", "7.7.7.7", 3).allowed(), "Should lock again after new failures");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Cleanup removes stale entries
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void cleanupRemovesExpiredEntries() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(30, 30, clock);
        UUID uuid = UUID.randomUUID();

        limiter.recordFailure(uuid, "Frank", "8.8.8.8", 5);
        clock.advanceSeconds(60);
        limiter.cleanup();
        // After cleanup, the state should be gone — status returns fresh allowed result
        assertTrue(limiter.status(uuid, "Frank", "8.8.8.8", 5).allowed());
        assertEquals(0, limiter.status(uuid, "Frank", "8.8.8.8", 5).attempts());
    }

    // ──────────────────────────────────────────────────────────────────────
    // retryAfterMillis reported correctly
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void retryAfterMillisIsPositiveWhenLocked() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(300, 120, clock); // 120s lockout
        UUID uuid = UUID.randomUUID();

        for (int i = 0; i < 3; i++) limiter.recordFailure(uuid, "Grace", "9.9.9.9", 3);
        LoginRateLimiter.Result status = limiter.status(uuid, "Grace", "9.9.9.9", 3);
        assertFalse(status.allowed());
        assertTrue(status.retryAfterMillis() > 0, "retryAfterMillis should be positive when locked");
        assertTrue(status.retryAfterMillis() <= 120_000L, "retryAfterMillis should not exceed lockout");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Configure reconfigures thresholds
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void reconfigureUpdatesWindow() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(300, 300, clock);
        UUID uuid = UUID.randomUUID();

        limiter.recordFailure(uuid, "Henry", "10.10.10.10", 5);
        limiter.configure(10, 10); // shrink window to 10s

        clock.advanceSeconds(11);
        // After window shrinks and time passes, old attempts should be pruned
        assertEquals(0, limiter.status(uuid, "Henry", "10.10.10.10", 5).attempts());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helper
    // ──────────────────────────────────────────────────────────────────────

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
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
