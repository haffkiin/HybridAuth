package com.hybridauth.auth;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LoginRateLimiter {
    private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile int windowSeconds;
    private volatile int lockoutSeconds;

    public LoginRateLimiter(int windowSeconds, int lockoutSeconds) {
        this(windowSeconds, lockoutSeconds, Clock.systemUTC());
    }

    LoginRateLimiter(int windowSeconds, int lockoutSeconds, Clock clock) {
        this.clock = clock;
        configure(windowSeconds, lockoutSeconds);
    }

    public void configure(int windowSeconds, int lockoutSeconds) {
        this.windowSeconds = Math.max(1, windowSeconds);
        this.lockoutSeconds = Math.max(1, lockoutSeconds);
        cleanup();
    }

    public Result status(UUID uuid, String username, String ipAddress, int maxAttempts) {
        String key = key(uuid, username, ipAddress);
        AttemptState state = attempts.get(key);
        if (state == null) return new Result(true, 0, 0);
        synchronized (state) {
            long now = clock.millis();
            prune(state, now);
            if (state.lockedUntil > now) {
                return new Result(false, state.timestamps.size(), state.lockedUntil - now);
            }
            if (state.timestamps.size() >= maxAttempts) {
                state.lockedUntil = now + lockoutSeconds * 1000L;
                return new Result(false, state.timestamps.size(), lockoutSeconds * 1000L);
            }
            if (state.timestamps.isEmpty()) attempts.remove(key, state);
            return new Result(true, state.timestamps.size(), 0);
        }
    }

    public Result recordFailure(UUID uuid, String username, String ipAddress, int maxAttempts) {
        AttemptState state = attempts.computeIfAbsent(key(uuid, username, ipAddress), ignored -> new AttemptState());
        synchronized (state) {
            long now = clock.millis();
            prune(state, now);
            state.timestamps.addLast(now);
            if (state.timestamps.size() >= maxAttempts) {
                state.lockedUntil = now + lockoutSeconds * 1000L;
                return new Result(false, state.timestamps.size(), lockoutSeconds * 1000L);
            }
            return new Result(true, state.timestamps.size(), 0);
        }
    }

    public void clear(UUID uuid, String username, String ipAddress) {
        attempts.remove(key(uuid, username, ipAddress));
    }

    public void cleanup() {
        long now = clock.millis();
        attempts.entrySet().removeIf(entry -> {
            AttemptState state = entry.getValue();
            synchronized (state) {
                prune(state, now);
                return state.timestamps.isEmpty() && state.lockedUntil <= now;
            }
        });
    }

    private void prune(AttemptState state, long now) {
        long threshold = now - windowSeconds * 1000L;
        while (!state.timestamps.isEmpty() && state.timestamps.peekFirst() < threshold) {
            state.timestamps.removeFirst();
        }
        if (state.lockedUntil <= now) state.lockedUntil = 0;
    }

    private static String key(UUID uuid, String username, String ipAddress) {
        String name = username == null ? "" : username.toLowerCase(Locale.ROOT);
        String ip = ipAddress == null ? "" : ipAddress.trim();
        return uuid + "|" + name + "|" + ip;
    }

    private static final class AttemptState {
        private final Deque<Long> timestamps = new ArrayDeque<>();
        private long lockedUntil;
    }

    public record Result(boolean allowed, int attempts, long retryAfterMillis) {
    }
}
