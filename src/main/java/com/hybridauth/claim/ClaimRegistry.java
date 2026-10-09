package com.hybridauth.claim;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Заявки на перенос пиратского аккаунта на лицензию (команда {@code /claim}). Хранятся в памяти:
 * заявка живёт несколько минут, поэтому при перезапуске сервера её просто нужно подать заново.
 *
 * Ключ — ник без учёта регистра: заявка подаётся с пиратским ником, а владелец лицензии заходит
 * с каноническим ником Mojang, и регистр у них может отличаться ({@code remure} и {@code ReMure}).
 */
public final class ClaimRegistry {

    /**
     * @param nick         ник пиратской записи в том регистре, в котором он сохранён
     * @param crackedUuid  UUID пиратской записи
     * @param expiresAtMillis до какого времени заявка действует
     */
    public record Pending(String nick, UUID crackedUuid, long expiresAtMillis) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** Сообщения, которые нужно показать игроку при ближайшем входе (после успешного переноса). */
    private final Map<UUID, String> notices = new ConcurrentHashMap<>();

    public Pending create(String nick, UUID crackedUuid, long nowMillis, long ttlMillis) {
        Pending claim = new Pending(nick, crackedUuid, nowMillis + ttlMillis);
        pending.put(key(nick), claim);
        return claim;
    }

    /** Действующая заявка на этот ник; просроченная удаляется. */
    public Optional<Pending> find(String nick, long nowMillis) {
        String key = key(nick);
        Pending claim = pending.get(key);
        if (claim == null) {
            return Optional.empty();
        }
        if (claim.expiresAtMillis() <= nowMillis) {
            pending.remove(key, claim);
            return Optional.empty();
        }
        return Optional.of(claim);
    }

    public void remove(String nick) {
        pending.remove(key(nick));
    }

    public int size() {
        return pending.size();
    }

    /** Действующие заявки (для /hybridauth claim list). */
    public java.util.List<Pending> snapshot(long nowMillis) {
        return pending.values().stream().filter(claim -> claim.expiresAtMillis() > nowMillis).toList();
    }

    public void putNotice(UUID id, String message) {
        notices.put(id, message);
    }

    /** Забирает сообщение: показывается один раз. */
    public Optional<String> takeNotice(UUID id) {
        return Optional.ofNullable(notices.remove(id));
    }

    private static String key(String nick) {
        return nick.toLowerCase(Locale.ROOT);
    }
}
