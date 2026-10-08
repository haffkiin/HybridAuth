package com.hybridauth.skin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Выбранные скины игроков: {@code config/hybridauth/skins.json}, ключ — UUID игрока.
 * Отдельный файл, чтобы не менять формат {@code players.json}. Запись атомарная, перед ней
 * остаётся копия {@code skins.json.bak}. Объём данных мал, поэтому запись идёт сразу в вызвавшем потоке.
 */
public final class SkinStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger("HybridAuth");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int FORMAT_VERSION = 1;

    private final Path file;
    private final Map<UUID, SkinEntry> skins = new LinkedHashMap<>();

    public SkinStorage(Path hybridAuthDir) {
        this.file = hybridAuthDir.resolve("skins.json");
        load();
    }

    public synchronized Optional<SkinEntry> get(UUID id) {
        return Optional.ofNullable(skins.get(id));
    }

    public synchronized int size() {
        return skins.size();
    }

    /** @return false, если запись не удалось сохранить на диск (в памяти она при этом обновлена) */
    public synchronized boolean put(UUID id, SkinEntry entry) {
        skins.put(id, entry);
        return save();
    }

    /** @return true, если запись была и удалена; сбой записи на диск не скрывается — см. {@link #lastSaveFailed()} */
    public synchronized boolean remove(UUID id) {
        boolean existed = skins.remove(id) != null;
        if (existed) {
            save();
        }
        return existed;
    }

    /**
     * Переносит запись на другой UUID (перенос аккаунта). Запись цели, если была, затирается;
     * для отката вызывающий запоминает обе записи заранее и возвращает их через {@link #restore}.
     */
    public synchronized void move(UUID from, UUID to) {
        SkinEntry entry = skins.remove(from);
        if (entry != null) {
            skins.put(to, entry);
            save();
        }
    }

    /** Возвращает состояние пары UUID после {@link #move}: запись источника и прежняя запись цели. */
    public synchronized void restore(UUID from, SkinEntry sourceEntry, UUID to, SkinEntry targetEntry) {
        if (sourceEntry == null) {
            skins.remove(from);
        } else {
            skins.put(from, sourceEntry);
        }
        if (targetEntry == null) {
            skins.remove(to);
        } else {
            skins.put(to, targetEntry);
        }
        save();
    }

    private boolean saveFailed;

    public synchronized boolean lastSaveFailed() {
        return saveFailed;
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("skins")) {
                LOGGER.warn("[HybridAuth] skins.json имеет неожиданный формат, скины не загружены.");
                return;
            }
            for (Map.Entry<String, JsonElement> item : root.getAsJsonObject().getAsJsonObject("skins").entrySet()) {
                try {
                    UUID id = UUID.fromString(item.getKey());
                    SkinEntry.fromJson(item.getValue().getAsJsonObject()).ifPresentOrElse(
                            entry -> skins.put(id, entry),
                            () -> LOGGER.warn("[HybridAuth] Пропущена повреждённая запись скина {}", item.getKey()));
                } catch (RuntimeException e) {
                    LOGGER.warn("[HybridAuth] Пропущена повреждённая запись скина {}", item.getKey());
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[HybridAuth] Не удалось прочитать skins.json: скины игроков не загружены.", e);
        }
    }

    private boolean save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        JsonObject all = new JsonObject();
        for (Map.Entry<UUID, SkinEntry> item : skins.entrySet()) {
            all.add(item.getKey().toString(), item.getValue().toJson());
        }
        root.add("skins", all);

        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            if (Files.isRegularFile(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            saveFailed = false;
            return true;
        } catch (IOException e) {
            LOGGER.error("[HybridAuth] Не удалось сохранить skins.json", e);
            saveFailed = true;
            return false;
        }
    }
}
