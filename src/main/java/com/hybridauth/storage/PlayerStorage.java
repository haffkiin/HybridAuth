package com.hybridauth.storage;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

/**
 * Интерфейс для хранения данных авторизации игроков.
 * Реализации: JsonPlayerStorage (по умолчанию), SqlitePlayerStorage (в будущем).
 */
public interface PlayerStorage {

    /**
     * Сохранить или обновить данные игрока.
     */
    void save(PlayerData data);

    /**
     * Загрузить данные игрока по UUID.
     */
    Optional<PlayerData> load(UUID uuid);

    /**
     * Загрузить данные игрока по нику (case-insensitive).
     */
    Optional<PlayerData> loadByUsername(String username);

    /** Loads a record whose stored username has exactly the same case. */
    default Optional<PlayerData> loadByExactUsername(String username) {
        return loadByUsername(username).filter(player -> player.getUsername().equals(username));
    }

    /** Returns a stable snapshot for diagnostics and conservative migrations. */
    default List<PlayerData> listAll() {
        return List.of();
    }

    /**
     * Удалить данные игрока.
     */
    boolean delete(UUID uuid);

    /**
     * Удалить данные игрока по нику.
     */
    boolean deleteByUsername(String username);

    /**
     * Количество зарегистрированных игроков.
     */
    int count();

    /**
     * Сохранить все данные на диск (для реализаций с ленивой записью).
     */
    void flush();

    default void flushAndWait() {
        flush();
    }

    default void close() {
        flushAndWait();
    }

    /**
     * Creates an immediately restorable snapshot of the authentication database.
     */
    boolean createBackup();
}
