package com.hybridauth.skin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinStorageTest {

    @TempDir
    Path dir;

    private static SkinEntry entry(String argument) {
        return new SkinEntry(SkinEntry.Source.NICK, argument, SkinVariant.SLIM,
                new SkinProperty(SkinTestData.validValue("slim"), "sig"), 123L);
    }

    @Test
    void putSurvivesReload() {
        UUID id = UUID.randomUUID();
        SkinStorage storage = new SkinStorage(dir);
        assertTrue(storage.put(id, entry("Notch")));

        SkinStorage reloaded = new SkinStorage(dir);
        SkinEntry loaded = reloaded.get(id).orElseThrow();
        assertEquals("Notch", loaded.argument());
        assertEquals(SkinVariant.SLIM, loaded.variant());
        assertEquals("sig", loaded.property().signature());
        assertEquals(123L, loaded.updatedAt());
    }

    @Test
    void unsignedTextureKeepsNullSignature() {
        UUID id = UUID.randomUUID();
        new SkinStorage(dir).put(id, new SkinEntry(SkinEntry.Source.URL, "https://example.com/a.png",
                SkinVariant.CLASSIC, new SkinProperty(SkinTestData.validValue(), null), 1L));
        assertEquals(null, new SkinStorage(dir).get(id).orElseThrow().property().signature());
    }

    @Test
    void removeDeletesEntry() {
        UUID id = UUID.randomUUID();
        SkinStorage storage = new SkinStorage(dir);
        storage.put(id, entry("Notch"));
        assertTrue(storage.remove(id));
        assertFalse(storage.remove(id));
        assertTrue(new SkinStorage(dir).get(id).isEmpty());
    }

    @Test
    void moveAndRestore() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        SkinStorage storage = new SkinStorage(dir);
        SkinEntry original = entry("Notch");
        storage.put(from, original);

        storage.move(from, to);
        assertTrue(storage.get(from).isEmpty());
        assertEquals("Notch", storage.get(to).orElseThrow().argument());

        storage.restore(from, original, to, null);
        assertEquals("Notch", storage.get(from).orElseThrow().argument());
        assertTrue(storage.get(to).isEmpty());
        assertEquals("Notch", new SkinStorage(dir).get(from).orElseThrow().argument());
    }

    @Test
    void moveWithoutSkinChangesNothing() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        SkinStorage storage = new SkinStorage(dir);
        storage.put(to, entry("Keep"));
        storage.move(from, to);
        assertEquals("Keep", storage.get(to).orElseThrow().argument());
    }

    @Test
    void corruptedEntryIsSkippedOthersLoad() throws IOException {
        UUID good = UUID.randomUUID();
        new SkinStorage(dir).put(good, entry("Good"));
        Path file = dir.resolve("skins.json");
        String json = Files.readString(file, StandardCharsets.UTF_8)
                .replace("\"skins\": {", "\"skins\": {\"" + UUID.randomUUID() + "\": {\"source\":\"nope\"},");
        Files.writeString(file, json, StandardCharsets.UTF_8);

        SkinStorage reloaded = new SkinStorage(dir);
        assertEquals(1, reloaded.size());
        assertTrue(reloaded.get(good).isPresent());
    }

    @Test
    void garbageFileDoesNotThrow() throws IOException {
        Files.writeString(dir.resolve("skins.json"), "{ not json", StandardCharsets.UTF_8);
        assertEquals(0, new SkinStorage(dir).size());
    }

    @Test
    void keepsBackupOfPreviousFile() {
        SkinStorage storage = new SkinStorage(dir);
        storage.put(UUID.randomUUID(), entry("First"));
        storage.put(UUID.randomUUID(), entry("Second"));
        assertTrue(Files.isRegularFile(dir.resolve("skins.json.bak")));
    }
}
