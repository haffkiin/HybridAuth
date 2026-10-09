package com.hybridauth.claim;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimRegistryTest {

    private static final long MINUTE = 60_000L;

    @Test
    void findIsCaseInsensitiveAndKeepsStoredNick() {
        ClaimRegistry registry = new ClaimRegistry();
        UUID cracked = UUID.randomUUID();
        registry.create("remure", cracked, 1_000, 10 * MINUTE);

        ClaimRegistry.Pending found = registry.find("ReMure", 2_000).orElseThrow();
        assertEquals("remure", found.nick());
        assertEquals(cracked, found.crackedUuid());
    }

    @Test
    void expiredClaimIsGoneAndRemoved() {
        ClaimRegistry registry = new ClaimRegistry();
        registry.create("ReMure", UUID.randomUUID(), 1_000, 10 * MINUTE);

        assertTrue(registry.find("ReMure", 1_000 + 10 * MINUTE - 1).isPresent());
        assertEquals(Optional.empty(), registry.find("ReMure", 1_000 + 10 * MINUTE));
        assertEquals(0, registry.size());
    }

    @Test
    void newClaimReplacesOldOneForSameNick() {
        ClaimRegistry registry = new ClaimRegistry();
        UUID second = UUID.randomUUID();
        registry.create("ReMure", UUID.randomUUID(), 1_000, MINUTE);
        registry.create("remure", second, 2_000, MINUTE);

        assertEquals(1, registry.size());
        assertEquals(second, registry.find("ReMure", 3_000).orElseThrow().crackedUuid());
    }

    @Test
    void removeDeletesClaim() {
        ClaimRegistry registry = new ClaimRegistry();
        registry.create("ReMure", UUID.randomUUID(), 1_000, MINUTE);
        registry.remove("REMURE");
        assertEquals(Optional.empty(), registry.find("ReMure", 2_000));
    }

    @Test
    void noticeIsShownOnce() {
        ClaimRegistry registry = new ClaimRegistry();
        UUID id = UUID.randomUUID();
        registry.putNotice(id, "готово");
        assertEquals("готово", registry.takeNotice(id).orElseThrow());
        assertEquals(Optional.empty(), registry.takeNotice(id));
    }
}
