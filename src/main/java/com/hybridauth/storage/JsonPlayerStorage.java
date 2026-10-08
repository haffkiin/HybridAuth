package com.hybridauth.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class JsonPlayerStorage implements PlayerStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger("HybridAuth");
    private static final int MAX_BACKUPS = 20;
    private static final long FLUSH_TIMEOUT_MILLIS = 30_000L;
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path filePath;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private final Gson gson;
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hybridauth-json-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final Object stateLock = new Object();
    private String pendingSnapshot;
    private long requestedVersion;
    private long writtenVersion;
    private boolean drainScheduled;
    private boolean startupBackupCreated;
    private boolean closed;

    public JsonPlayerStorage(Path configDir) {
        this.filePath = configDir.resolve("hybridauth").resolve("players.json");
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(Instant.class, new InstantAdapter())
                .registerTypeAdapter(UUID.class, new UUIDAdapter())
                .create();
        loadOrRecover();
    }

    private void loadOrRecover() {
        if (!Files.exists(filePath)) {
            LOGGER.info("[HybridAuth] players.json не найден, начинаю с пустой базы.");
            return;
        }
        try {
            players.putAll(readPlayers(filePath));
            LOGGER.info("[HybridAuth] Загружено записей из players.json: {}", players.size());
        } catch (Exception primaryError) {
            LOGGER.error("[HybridAuth] players.json повреждён, пробую ротационные бэкапы.", primaryError);
            recoverFromBackup(primaryError);
        }
    }

    private Map<UUID, PlayerData> readPlayers(Path source) throws IOException {
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject values = root.has("players") ? root.getAsJsonObject("players") : new JsonObject();
            Map<UUID, PlayerData> loaded = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                UUID uuid = UUID.fromString(entry.getKey());
                PlayerData data = gson.fromJson(entry.getValue(), PlayerData.class);
                if (data == null || data.getUsername() == null || data.getType() == null) {
                    throw new IOException("Некорректная запись игрока " + entry.getKey());
                }
                data.setUuid(uuid);
                loaded.put(uuid, data);
            }
            return loaded;
        } catch (RuntimeException exception) {
            throw new IOException("Некорректный JSON в " + source, exception);
        }
    }

    private void recoverFromBackup(Exception primaryError) {
        Path backupDirectory = filePath.getParent().resolve("backups");
        List<Path> backups;
        try {
            backups = listBackups(backupDirectory);
        } catch (IOException exception) {
            primaryError.addSuppressed(exception);
            throw new IllegalStateException("Данные авторизации повреждены, бэкапы прочитать не удалось", primaryError);
        }

        for (int i = backups.size() - 1; i >= 0; i--) {
            Path backup = backups.get(i);
            try {
                Map<UUID, PlayerData> recovered = readPlayers(backup);
                preserveCorruptPrimary();
                Path temporary = filePath.resolveSibling("players.json.recovery.tmp");
                Files.copy(backup, temporary, StandardCopyOption.REPLACE_EXISTING);
                atomicReplace(temporary, filePath);
                players.clear();
                players.putAll(recovered);
                LOGGER.warn("[HybridAuth] players.json восстановлен из {}", backup);
                return;
            } catch (Exception exception) {
                LOGGER.warn("[HybridAuth] Бэкап {} непригоден: {}", backup, exception.getMessage());
            }
        }
        throw new IllegalStateException("Данные авторизации повреждены, рабочего бэкапа нет", primaryError);
    }

    private void preserveCorruptPrimary() throws IOException {
        Path backupDirectory = filePath.getParent().resolve("backups");
        Files.createDirectories(backupDirectory);
        Path corruptCopy = backupDirectory.resolve(
                "players-corrupt-" + LocalDateTime.now().format(BACKUP_TIMESTAMP) + ".json");
        Files.copy(filePath, corruptCopy, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void save(PlayerData data) {
        players.put(data.getUuid(), data);
        enqueueSnapshot();
    }

    @Override
    public Optional<PlayerData> load(UUID uuid) {
        return Optional.ofNullable(players.get(uuid));
    }

    @Override
    public Optional<PlayerData> loadByUsername(String username) {
        return players.values().stream()
                .filter(player -> player.getUsername().equalsIgnoreCase(username))
                .findFirst();
    }

    @Override
    public Optional<PlayerData> loadByExactUsername(String username) {
        return players.values().stream()
                .filter(player -> player.getUsername().equals(username))
                .findFirst();
    }

    @Override
    public List<PlayerData> listAll() {
        return List.copyOf(players.values());
    }

    @Override
    public boolean delete(UUID uuid) {
        PlayerData removed = players.remove(uuid);
        if (removed != null) enqueueSnapshot();
        return removed != null;
    }

    @Override
    public boolean deleteByUsername(String username) {
        return loadByUsername(username).map(PlayerData::getUuid).map(this::delete).orElse(false);
    }

    @Override
    public int count() {
        return players.size();
    }

    @Override
    public void flush() {
        flushAndWait();
    }

    @Override
    public void flushAndWait() {
        long target = enqueueSnapshot();
        long deadline = System.currentTimeMillis() + FLUSH_TIMEOUT_MILLIS;
        synchronized (stateLock) {
            while (writtenVersion < target) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new IllegalStateException("Время ожидания записи players.json истекло");
                }
                try {
                    stateLock.wait(remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Запись players.json прервана", exception);
                }
            }
        }
    }

    private long enqueueSnapshot() {
        String snapshot = createSnapshot();
        synchronized (stateLock) {
            if (closed) throw new IllegalStateException("Хранилище авторизации закрыто");
            pendingSnapshot = snapshot;
            long version = ++requestedVersion;
            if (!drainScheduled) {
                drainScheduled = true;
                writer.execute(this::drainWrites);
            }
            return version;
        }
    }

    private String createSnapshot() {
        JsonObject root = new JsonObject();
        JsonObject values = new JsonObject();
        for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
            PlayerData data = entry.getValue();
            JsonObject json = new JsonObject();
            json.addProperty("username", data.getUsername());
            json.addProperty("type", data.getType().name());
            if (data.getPasswordHash() != null) json.addProperty("passwordHash", data.getPasswordHash());
            if (data.getRecoveryCodeHash() != null) json.addProperty("recoveryCodeHash", data.getRecoveryCodeHash());
            if (data.getRegisteredAt() != null) json.addProperty("registeredAt", data.getRegisteredAt().toString());
            if (data.getLastLoginAt() != null) json.addProperty("lastLoginAt", data.getLastLoginAt().toString());
            if (data.getLastLoginIp() != null) json.addProperty("lastLoginIp", data.getLastLoginIp());
            values.add(entry.getKey().toString(), json);
        }
        root.add("players", values);
        return gson.toJson(root);
    }

    private void drainWrites() {
        while (true) {
            String snapshot;
            long version;
            synchronized (stateLock) {
                snapshot = pendingSnapshot;
                version = requestedVersion;
                pendingSnapshot = null;
            }
            try {
                writeSnapshot(snapshot);
                synchronized (stateLock) {
                    writtenVersion = Math.max(writtenVersion, version);
                    stateLock.notifyAll();
                    if (pendingSnapshot == null) {
                        drainScheduled = false;
                        return;
                    }
                }
            } catch (IOException exception) {
                LOGGER.error("[HybridAuth] Не удалось записать players.json, повторяю попытку.", exception);
                synchronized (stateLock) {
                    if (pendingSnapshot == null) pendingSnapshot = snapshot;
                    drainScheduled = true;
                    stateLock.notifyAll();
                }
                writer.schedule(this::drainWrites, 1, TimeUnit.SECONDS);
                return;
            }
        }
    }

    private void writeSnapshot(String snapshot) throws IOException {
        Files.createDirectories(filePath.getParent());
        if (!startupBackupCreated && Files.exists(filePath)) {
            if (!createBackupInternal()) throw new IOException("Не удалось создать бэкап при запуске");
            startupBackupCreated = true;
        }
        Path temporary = filePath.resolveSibling("players.json.tmp");
        Files.writeString(temporary, snapshot, StandardCharsets.UTF_8);
        atomicReplace(temporary, filePath);
    }

    @Override
    public boolean createBackup() {
        flushAndWait();
        try {
            return writer.submit(this::createBackupInternal).get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            LOGGER.error("[HybridAuth] Не удалось создать бэкап данных авторизации.", exception);
            return false;
        }
    }

    private boolean createBackupInternal() {
        if (!Files.exists(filePath)) return false;
        Path backupDirectory = filePath.getParent().resolve("backups");
        Path backup = backupDirectory.resolve(
                "players-" + LocalDateTime.now().format(BACKUP_TIMESTAMP) + ".json");
        try {
            Files.createDirectories(backupDirectory);
            Files.copy(filePath, backup, StandardCopyOption.REPLACE_EXISTING);
            removeOldBackups(backupDirectory);
            LOGGER.info("[HybridAuth] Бэкап данных авторизации создан: {}", backup);
            return true;
        } catch (IOException exception) {
            LOGGER.error("[HybridAuth] Не удалось создать бэкап данных авторизации.", exception);
            return false;
        }
    }

    private List<Path> listBackups(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("players-"))
                    .filter(path -> !path.getFileName().toString().startsWith("players-corrupt-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private void removeOldBackups(Path directory) throws IOException {
        List<Path> backups = listBackups(directory);
        for (int i = 0; i < backups.size() - MAX_BACKUPS; i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void close() {
        flushAndWait();
        synchronized (stateLock) {
            closed = true;
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }

    private static class InstantAdapter
            implements com.google.gson.JsonSerializer<Instant>, com.google.gson.JsonDeserializer<Instant> {
        @Override
        public JsonElement serialize(Instant src, Type type, com.google.gson.JsonSerializationContext context) {
            return new com.google.gson.JsonPrimitive(src.toString());
        }

        @Override
        public Instant deserialize(JsonElement json, Type type, com.google.gson.JsonDeserializationContext context) {
            return Instant.parse(json.getAsString());
        }
    }

    private static class UUIDAdapter
            implements com.google.gson.JsonSerializer<UUID>, com.google.gson.JsonDeserializer<UUID> {
        @Override
        public JsonElement serialize(UUID src, Type type, com.google.gson.JsonSerializationContext context) {
            return new com.google.gson.JsonPrimitive(src.toString());
        }

        @Override
        public UUID deserialize(JsonElement json, Type type, com.google.gson.JsonDeserializationContext context) {
            return UUID.fromString(json.getAsString());
        }
    }
}
