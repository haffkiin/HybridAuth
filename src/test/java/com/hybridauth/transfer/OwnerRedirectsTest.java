package com.hybridauth.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerRedirectsTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @TempDir
    Path dir;

    @Test
    void resolveWithoutRedirectReturnsSameUuid() {
        assertEquals(A, new OwnerRedirects(dir.resolve("r.json")).resolve(A));
    }

    @Test
    void chainIsFollowedToTheEnd() {
        OwnerRedirects redirects = new OwnerRedirects(dir.resolve("r.json"));
        redirects.add(A, B);
        redirects.add(B, C);
        assertEquals(C, redirects.resolve(A));
        assertEquals(C, redirects.resolve(B));
        assertEquals(C, redirects.resolve(C));
    }

    @Test
    void cycleDoesNotHang() {
        OwnerRedirects redirects = new OwnerRedirects(dir.resolve("r.json"));
        redirects.add(A, B);
        redirects.add(B, A);
        // Главное, что поиск заканчивается; результат — один из участников цикла
        UUID resolved = redirects.resolve(A);
        assertTrue(resolved.equals(A) || resolved.equals(B));
    }

    @Test
    void sameUuidIsIgnored() {
        OwnerRedirects redirects = new OwnerRedirects(dir.resolve("r.json"));
        redirects.add(A, A);
        assertTrue(redirects.isEmpty());
    }

    @Test
    void survivesRestart() {
        Path file = dir.resolve("sub").resolve("r.json");
        new OwnerRedirects(file).add(A, B);
        assertEquals(B, new OwnerRedirects(file).resolve(A));
    }

    @Test
    void restoreUndoesAdd() {
        OwnerRedirects redirects = new OwnerRedirects(dir.resolve("r.json"));
        redirects.add(A, B);
        Map<String, String> snapshot = redirects.snapshot();
        redirects.add(B, C);
        assertEquals(C, redirects.resolve(A));

        redirects.restore(snapshot);
        assertEquals(B, redirects.resolve(A));
        assertEquals(B, new OwnerRedirects(dir.resolve("r.json")).resolve(A));
    }

    @Test
    void brokenFileStartsEmpty() throws Exception {
        Path file = dir.resolve("r.json");
        java.nio.file.Files.writeString(file, "{ не json");
        assertTrue(new OwnerRedirects(file).isEmpty());
    }
}
