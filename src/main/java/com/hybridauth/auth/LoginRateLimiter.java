package com.hybridauth.auth;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Двухуровневый rate limiter:
 * 1) идентичность (uuid|имя|ip) — блокируется после maxAttempts неудачных попыток;
 * 2) глобальный счётчик по имени — блокируется после NAME_MAX_ATTEMPTS_MULTIPLIER * maxAttempts.
 *
 * Второй уровень не даёт обойти лимит ротацией IP, но порог в 3 раза выше,
 * чтобы злоумышленник не мог "залочить" чужой ник несколькими попытками.
 */
public final class LoginRateLimiter {

    private static final int NAME_MAX_ATTEMPTS_MULTIPLIER = 3;

    private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();
    private final Map<String, AttemptState> nameAttempts = new ConcurrentHashMap<>();
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
        long now = clock.millis();
        AttemptState identityState = attempts.get(key(uuid, username, ipAddress));
        AttemptState nameState = nameAttempts.get(nameKey(username));
        return combine(
                checkState(identityState, now, maxAttempts),
                checkState(nameState, now, maxAttempts * NAME_MAX_ATTEMPTS_MULTIPLIER));
    }

    public Result recordFailure(UUID uuid, String username, String ipAddress, int maxAttempts) {
        long now = clock.millis();
        Result identityResult = record(attempts, key(uuid, username, ipAddress), now, maxAttempts);
        Result nameResult = record(nameAttempts, nameKey(username), now, maxAttempts * NAME_MAX_ATTEMPTS_MULTIPLIER);
        return combine(identityResult, nameResult);
    }

    public void clear(UUID uuid, String username, String ipAddress) {
        attempts.remove(key(uuid, username, ipAddress));
        nameAttempts.remove(nameKey(username));
    }

    public void cleanup() {
        long now = clock.millis();
        attempts.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
        nameAttempts.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
    }

    private Result record(Map<String, AttemptState> states, String key, long now, int maxAttempts) {
        AttemptState state = states.computeIfAbsent(key, ignored -> new AttemptState());
        synchronized (state) {
            prune(state, now);
            state.timestamps.addLast(now);
            if (state.timestamps.size() >= maxAttempts) {
                state.lockedUntil = now + lockoutSeconds * 1000L;
                return new Result(false, state.timestamps.size(), lockoutSeconds * 1000L);
            }
            return new Result(true, state.timestamps.size(), 0);
        }
    }

    private Result checkState(AttemptState state, long now, int maxAttempts) {
        if (state == null) {
            return null;
        }
        synchronized (state) {
            prune(state, now);
            if (state.lockedUntil > now) {
                return new Result(false, state.timestamps.size(), state.lockedUntil - now);
            }
            if (state.timestamps.size() >= maxAttempts) {
                state.lockedUntil = now + lockoutSeconds * 1000L;
                return new Result(false, state.timestamps.size(), lockoutSeconds * 1000L);
            }
            if (state.timestamps.isEmpty()) {
                return null;
            }
            return new Result(true, state.timestamps.size(), 0);
        }
    }

    private static Result combine(Result identityResult, Result nameResult) {
        if (identityResult == null && nameResult == null) {
            return new Result(true, 0, 0);
        }
        if (identityResult != null && !identityResult.allowed()) {
            return identityResult;
        }
        if (nameResult != null && !nameResult.allowed()) {
            return nameResult;
        }
        if (identityResult == null) {
            return nameResult;
        }
        if (nameResult == null) {
            return identityResult;
        }
        return new Result(true,
                Math.max(identityResult.attempts(), nameResult.attempts()),
                Math.max(identityResult.retryAfterMillis(), nameResult.retryAfterMillis()));
    }

    private boolean isExpired(AttemptState state, long now) {
        synchronized (state) {
            prune(state, now);
            return state.timestamps.isEmpty() && state.lockedUntil <= now;
        }
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

    private static String nameKey(String username) {
        return "name|" + (username == null ? "" : username.toLowerCase(Locale.ROOT));
    }

    private static final class AttemptState {
        private final Deque<Long> timestamps = new ArrayDeque<>();
        private long lockedUntil;
    }

    public record Result(boolean allowed, int attempts, long retryAfterMillis) {
    }
}
