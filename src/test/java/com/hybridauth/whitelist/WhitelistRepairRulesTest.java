package com.hybridauth.whitelist;

import com.hybridauth.auth.OfflineUuid;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhitelistRepairRulesTest {

    private static final UUID PREMIUM_ID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void exactOfflineDuplicateOfVerifiedPremiumIsStale() {
        assertTrue(WhitelistRepairRules.isStaleOfflineEntry(
                "ReMure", OfflineUuid.forName("ReMure"), "ReMure", PREMIUM_ID, false));
    }

    @Test
    void caseVariantOfflineEntryIsNotTouched() {
        // Раньше такая запись удалялась: кракнутый remure — отдельный игрок
        assertFalse(WhitelistRepairRules.isStaleOfflineEntry(
                "remure", OfflineUuid.forName("remure"), "ReMure", PREMIUM_ID, false));
    }

    @Test
    void crackedRecordWithSameNameProtectsEntry() {
        assertFalse(WhitelistRepairRules.isStaleOfflineEntry(
                "ReMure", OfflineUuid.forName("ReMure"), "ReMure", PREMIUM_ID, true));
    }

    @Test
    void entryWithPremiumIdIsNotStale() {
        assertFalse(WhitelistRepairRules.isStaleOfflineEntry(
                "ReMure", PREMIUM_ID, "ReMure", PREMIUM_ID, false));
    }

    @Test
    void nonOfflineIdIsNotStale() {
        // UUID, который не выводится из ника, не трогаем: неизвестно, кому он принадлежит
        assertFalse(WhitelistRepairRules.isStaleOfflineEntry(
                "ReMure", UUID.randomUUID(), "ReMure", PREMIUM_ID, false));
    }

    @Test
    void nullInputsAreNotStale() {
        assertFalse(WhitelistRepairRules.isStaleOfflineEntry(null, null, "ReMure", PREMIUM_ID, false));
        assertFalse(WhitelistRepairRules.isStaleOfflineEntry("ReMure", OfflineUuid.forName("ReMure"), null, PREMIUM_ID, false));
    }
}
