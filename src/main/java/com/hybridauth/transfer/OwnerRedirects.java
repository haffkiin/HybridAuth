package com.hybridauth.transfer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Таблица «прежний владелец → новый владелец» для питомцев после переноса аккаунта.
 *
 * Питомцы хранятся в данных чанков, и найти все в выгруженных чанках нельзя. Поэтому таблица
 * сохраняется на диск, а владелец меняется у каждого питомца в момент его загрузки в мир.
 * Цепочки (A → B, затем B → C) разворачиваются до конца, циклы не зацикливают поиск.
 * Без Minecraft: покрывается юнит-тестами.
 */
public final class OwnerRedirects {

    private static final Logger LOGGER = LoggerFactory.getLogger("HybridAuth");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    private final Path file;
    private final Map<String, String> redirects = new LinkedHashMap<>();

    public OwnerRedirects(Path file) {
        this.file = file;
        load();
    }

    /** Снимок таблицы для отката. */
    public synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(redirects);
    }

    /** Возвращает таблицу к снимку (откат переноса). */
    public synchronized void restore(Map<String, String> snapshot) {
        redirects.clear();
        redirects.putAll(snapshot);
        save();
    }

    /** Все владельцы, которые раньше вели на {@code from}, теперь ведут на {@code to}, а сам {@code from} тоже. */
    public synchronized void add(UUID from, UUID to) {
        if (from.equals(to)) {
            return;
        }
        String source = from.toString();
        String target = to.toString();
        redirects.replaceAll((ignored, destination) -> source.equals(destination) ? target : destination);
        // Аккаунт, на который переехали, снова «живой»: цепочка через него дальше не идёт
        redirects.remove(target);
        redirects.put(source, target);
        // Перенос туда и обратно оставляет записи вида A → A, они не нужны
        redirects.entrySet().removeIf(entry -> entry.getKey().equals(entry.getValue()));
        save();
    }

    public synchronized boolean isEmpty() {
        return redirects.isEmpty();
    }

    /** Конечный владелец для UUID; сам UUID, если для него переноса не было. */
    public synchronized UUID resolve(UUID owner) {
        String current = owner.toString();
        Set<String> visited = new HashSet<>();
        while (visited.add(current)) {
            String next = redirects.get(current);
            if (next == null || next.equals(current)) {
                break;
            }
            current = next;
        }
        try {
            return UUID.fromString(current);
        } catch (IllegalArgumentException e) {
            return owner;
        }
    }

    private void load() {
        try {
            if (Files.isRegularFile(file)) {
                Map<String, String> parsed = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), MAP_TYPE);
                if (parsed != null) {
                    redirects.putAll(parsed);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[HybridAuth] Не удалось прочитать {}: перенос владельцев питомцев начнётся с пустой таблицы.", file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(redirects), StandardCharsets.UTF_8);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("[HybridAuth] Не удалось сохранить {}", file, e);
        }
    }
}
