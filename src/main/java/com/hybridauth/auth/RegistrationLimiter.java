package com.hybridauth.auth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Мягкий лимит регистраций новых паролей: сколько их может пройти с одного адреса за час
 * и на весь сервер за 10 минут. Нужен против ботов, которые заливают сервер аккаунтами.
 *
 * Лимит считается только по удачным регистрациям и по скользящему окну, поэтому пользователи
 * VPN и общих сетей не блокируются надолго: через час (или раньше, если освободится место)
 * они снова могут зарегистрироваться. Значение {@code 0} выключает соответствующий лимит.
 */
public final class RegistrationLimiter {

    static final long PER_IP_WINDOW_MILLIS = 60L * 60L * 1000L;
    static final long GLOBAL_WINDOW_MILLIS = 10L * 60L * 1000L;

    /** @param retryAfterSeconds через сколько секунд освободится место; 0, если регистрация разрешена */
    public record Decision(boolean allowed, int retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private final Map<String, Deque<Long>> perIp = new HashMap<>();
    private final Deque<Long> global = new ArrayDeque<>();
    private int perIpMax;
    private int globalMax;

    public RegistrationLimiter(int perIpMax, int globalMax) {
        configure(perIpMax, globalMax);
    }

    public synchronized void configure(int perIpMax, int globalMax) {
        this.perIpMax = Math.max(0, perIpMax);
        this.globalMax = Math.max(0, globalMax);
    }

    /** Можно ли сейчас зарегистрировать ещё один аккаунт с этого адреса. Ничего не записывает. */
    public synchronized Decision check(String ip, long nowMillis) {
        prune(nowMillis);
        int retry = 0;
        if (perIpMax > 0) {
            Deque<Long> times = perIp.get(ip);
            if (times != null && times.size() >= perIpMax) {
                retry = Math.max(retry, secondsUntilFree(times.peekFirst(), PER_IP_WINDOW_MILLIS, nowMillis));
            }
        }
        if (globalMax > 0 && global.size() >= globalMax) {
            retry = Math.max(retry, secondsUntilFree(global.peekFirst(), GLOBAL_WINDOW_MILLIS, nowMillis));
        }
        return retry > 0 ? new Decision(false, retry) : Decision.ALLOWED;
    }

    /** Фиксирует удачную регистрацию. */
    public synchronized void record(String ip, long nowMillis) {
        prune(nowMillis);
        perIp.computeIfAbsent(ip, ignored -> new ArrayDeque<>()).addLast(nowMillis);
        global.addLast(nowMillis);
    }

    private void prune(long nowMillis) {
        perIp.values().forEach(times -> dropOlderThan(times, nowMillis - PER_IP_WINDOW_MILLIS));
        perIp.values().removeIf(Deque::isEmpty);
        dropOlderThan(global, nowMillis - GLOBAL_WINDOW_MILLIS);
    }

    private static void dropOlderThan(Deque<Long> times, long threshold) {
        while (!times.isEmpty() && times.peekFirst() <= threshold) {
            times.pollFirst();
        }
    }

    private static int secondsUntilFree(long oldest, long windowMillis, long nowMillis) {
        long millis = oldest + windowMillis - nowMillis;
        return (int) Math.max(1, (millis + 999) / 1000);
    }
}
