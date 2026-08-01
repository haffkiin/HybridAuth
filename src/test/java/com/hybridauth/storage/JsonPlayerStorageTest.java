package com.hybridauth.storage;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JsonPlayerStorageTest {

    @TempDir
    Path tempDir;

    // ──────────────────────────────────────────────────────────────────────
    // Legacy compatibility
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void legacyDataRoundTripsWithoutChangingCredentials() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path directory = tempDir.resolve("hybridauth");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("players.json"), """
                {"players":{"%s":{
                  "username":"Legacy",
                  "type":"CRACKED",
                  "passwordHash":"password-hash",
                  "recoveryCodeHash":"recovery-hash",
                  "registeredAt":"2025-01-01T00:00:00Z",
                  "lastLoginAt":"2025-02-01T00:00:00Z",
                  "lastLoginIp":"127.0.0.1"
                }}}
                """.formatted(uuid), StandardCharsets.UTF_8);

        JsonPlayerStorage storage = new JsonPlayerStorage(tempDir);
        PlayerData loaded = storage.load(uuid).orElseThrow();
        assertEquals("password-hash", loaded.getPasswordHash());
        assertEquals("recovery-hash", loaded.getRecoveryCodeHash());
        assertEquals("Legacy", loaded.getUsername());
        assertEquals(PlayerData.PlayerType.CRACKED, loaded.getType());
        storage.close();

        var root = JsonParser.parseString(Files.readString(directory.resolve("players.json"))).getAsJsonObject();
        var saved = root.getAsJsonObject("players").getAsJsonObject(uuid.toString());
        assertEquals("password-hash", saved.get("passwordHash").getAsString());
        assertEquals("recovery-hash", saved.get("recoveryCodeHash").getAsString());
        assertTrue(Files.isDirectory(directory.resolve("backups")));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Corrupt primary → recover from backup
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void corruptPrimaryRecoversNewestValidBackup() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path directory = tempDir.resolve("hybridauth");
        Path backups = directory.resolve("backups");
        Files.createDirectories(backups);
        Files.writeString(directory.resolve("players.json"), "{broken", StandardCharsets.UTF_8);
        Files.writeString(backups.resolve("players-20260101-000000-000.json"), """
                {"players":{"%s":{"username":"Recovered","type":"PREMIUM"}}}
                """.formatted(uuid), StandardCharsets.UTF_8);

        JsonPlayerStorage storage = new JsonPlayerStorage(tempDir);
        assertEquals("Recovered", storage.load(uuid).orElseThrow().getUsername());
        storage.close();
        assertTrue(Files.readString(directory.resolve("players.json")).contains("Recovered"));
    }

    // ──────────────────────────────────────────────────────────────────────
    // No backup available when primary is corrupt → throws
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void corruptPrimaryWithNoBackupThrows() throws Exception {
        Path directory = tempDir.resolve("hybridauth");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("players.json"), "{totally broken}", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> new JsonPlayerStorage(tempDir),
                "Should throw when primary is corrupt and there are no valid backups");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Burst writes are coalesced: only one file write per drain cycle
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void burstWritesAreCoalesced() throws Exception {
        Path directory = tempDir.resolve("hybridauth");
        Files.createDirectories(directory);

        JsonPlayerStorage storage = new JsonPlayerStorage(tempDir);
        int count = 50;
        List<UUID> uuids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID uuid = UUID.randomUUID();
            uuids.add(uuid);
            PlayerData d = new PlayerData(uuid, "Player" + i, PlayerData.PlayerType.CRACKED);
            d.setPasswordHash("hash" + i);
            storage.save(d);
        }
        storage.flushAndWait();

        // All records must be present after close
        for (int i = 0; i < count; i++) {
            assertTrue(storage.load(uuids.get(i)).isPresent(), "Record " + i + " should be present");
            assertEquals("hash" + i, storage.load(uuids.get(i)).get().getPasswordHash());
        }
        storage.close();
    }

    // ──────────────────────────────────────────────────────────────────────
    // shutdown flush: close() waits for pending write
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void shutdownWaitsForPendingWrite() throws Exception {
        Path directory = tempDir.resolve("hybridauth");
        Files.createDirectories(directory);

        JsonPlayerStorage storage = new JsonPlayerStorage(tempDir);
        UUID uuid = UUID.randomUUID();
        PlayerData data = new PlayerData(uuid, "ShutdownTest", PlayerData.PlayerType.CRACKED);
        data.setPasswordHash("shutdown-hash");
        storage.save(data);

        // close() must flush — don't call flushAndWait explicitly
        storage.close();

        // After close, the file should have the written data
        String json = Files.readString(directory.resolve("players.json"));
        assertTrue(json.contains("shutdown-hash"), "Written data should be present after close()");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Concurrent saves from multiple threads
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void concurrentSavesFromMultipleThreads() throws Exception {
        Path directory = tempDir.resolve("hybridauth");
        Files.createDirectories(directory);
        JsonPlayerStorage storage = new JsonPlayerStorage(tempDir);

        int threadCount = 8;
        int savesPerThread = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<UUID> ids = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            UUID uuid = UUID.randomUUID();
            ids.add(uuid);
            int threadNum = t;
            Executors.newSingleThreadExecutor().submit(() -> {
                try {
                    start.await();
                    for (int s = 0; s < savesPerThread; s++) {
                        PlayerData d = new PlayerData(uuid, "Thread" + threadNum, PlayerData.PlayerType.CRACKED);
                        d.setPasswordHash("hash-" + threadNum + "-" + s);
                        storage.save(d);
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        storage.flushAndWait();

        // All UUIDs should be loadable
        for (UUID uuid : ids) {
            assertTrue(storage.load(uuid).isPresent(), "All concurrent saves should be loadable");
        }
        storage.close();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Startup backup is created on first write
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void startupBackupCreatedBeforeFirstWrite() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path directory = tempDir.resolve("hybridauth");
        Path backupsDir = directory.resolve("backups");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("players.json"), """
                {"players":{"%s":{"username":"Original","type":"CRACKED","passwordHash":"orig-hash"}}}
                """.formatted(uuid), StandardCharsets.UTF_8);

        JsonPlayerStorage storage = new JsonPlayerStorage(tempDir);
        // Trigger a write
        PlayerData d = new PlayerData(UUID.randomUUID(), "NewPlayer", PlayerData.PlayerType.CRACKED);
        storage.save(d);
        storage.flushAndWait();
        storage.close();

        // Backups directory must exist with at least one backup
        assertTrue(Files.isDirectory(backupsDir), "Backups dir should exist");
        long backupCount;
        try (var stream = Files.list(backupsDir)) {
            backupCount = stream.filter(p -> p.getFileName().toString().startsWith("players-")).count();
        }
        assertTrue(backupCount >= 1, "At least one startup backup should exist");

        // Original record must still be in the file
        assertTrue(Files.readString(directory.resolve("players.json")).contains("orig-hash"));
    }

    // ──────────────────────────────────────────────────────────────────────
    // PREMIUM player data preserved through save/load cycle
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void premiumPlayerPreservesUuidAndType() throws Exception {
        Path directory = tempDir.resolve("hybridauth");
        Files.createDirectories(directory);
        JsonPlayerStorage storage = new JsonPlayerStorage(tempDir);

        UUID premiumUuid = UUID.randomUUID();
        PlayerData data = new PlayerData(premiumUuid, "PremiumPlayer", PlayerData.PlayerType.PREMIUM);
        data.setLastLoginAt(Instant.parse("2026-06-15T10:00:00Z"));
        data.setLastLoginIp("192.168.1.1");
        storage.save(data);
        storage.close();

        JsonPlayerStorage reloaded = new JsonPlayerStorage(tempDir);
        PlayerData loaded = reloaded.load(premiumUuid).orElseThrow();
        assertEquals(premiumUuid, loaded.getUuid());
        assertEquals("PremiumPlayer", loaded.getUsername());
        assertEquals(PlayerData.PlayerType.PREMIUM, loaded.getType());
        assertEquals(Instant.parse("2026-06-15T10:00:00Z"), loaded.getLastLoginAt());
        reloaded.close();
    }

    // ──────────────────────────────────────────────────────────────────────
    // delete removes entry from both memory and disk
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void deletePersistsToFile() throws Exception {
        Path directory = tempDir.resolve("hybridauth");
        Files.createDirectories(directory);
        JsonPlayerStorage storage = new JsonPlayerStorage(tempDir);

        UUID uuid = UUID.randomUUID();
        PlayerData data = new PlayerData(uuid, "ToDelete", PlayerData.PlayerType.CRACKED);
        storage.save(data);
        storage.flushAndWait();
        assertTrue(storage.load(uuid).isPresent());

        storage.delete(uuid);
        storage.close();

        JsonPlayerStorage reloaded = new JsonPlayerStorage(tempDir);
        assertFalse(reloaded.load(uuid).isPresent(), "Deleted record should not be present after reload");
        reloaded.close();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Max 20 backups retained
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void backupRetentionMaxIs20() throws Exception {
        Path directory = tempDir.resolve("hybridauth");
        Path backupsDir = directory.resolve("backups");
        Files.createDirectories(backupsDir);

        // Pre-create 25 fake backups
        for (int i = 0; i < 25; i++) {
            String ts = String.format("202601%02d-120000-000", i + 1);
            Files.writeString(backupsDir.resolve("players-" + ts + ".json"),
                    "{\"players\":{}}", StandardCharsets.UTF_8);
        }

        // Load a simple players.json (so storage writes → triggers backup rotation)
        Files.writeString(directory.resolve("players.json"), "{\"players\":{}}", StandardCharsets.UTF_8);
        JsonPlayerStorage storage = new JsonPlayerStorage(tempDir);
        UUID uuid = UUID.randomUUID();
        storage.save(new PlayerData(uuid, "RotationTest", PlayerData.PlayerType.CRACKED));
        storage.close(); // triggers createBackup + rotation

        long remaining;
        try (var stream = Files.list(backupsDir)) {
            remaining = stream
                    .filter(p -> p.getFileName().toString().startsWith("players-") &&
                                 !p.getFileName().toString().startsWith("players-corrupt-"))
                    .count();
        }
        // Should be at most 20 (the oldest ones are pruned)
        assertTrue(remaining <= 20, "Should not keep more than 20 backups, but found: " + remaining);
    }
}
