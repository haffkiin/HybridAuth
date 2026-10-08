package com.hybridauth.transfer;

import com.hybridauth.transfer.AccountTransferRules.Rejection;
import com.hybridauth.transfer.AccountTransferRules.Request;
import com.hybridauth.transfer.AccountTransferRules.SourceKind;
import com.hybridauth.transfer.AccountTransferRules.TargetLicense;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountTransferRulesTest {

    /** Всё в порядке: кракнутый источник, свободный валидный новый ник. */
    private static Request ok() {
        return new Request(SourceKind.CRACKED, false, false, true, false, false, false, false, TargetLicense.FREE);
    }

    @Test
    void validTransferHasNoRejection() {
        assertFalse(AccountTransferRules.firstRejection(ok()).isPresent());
    }

    @Test
    void missingSourceIsRejected() {
        assertEquals(Optional.of(Rejection.SOURCE_NOT_FOUND),
                AccountTransferRules.firstRejection(withSource(SourceKind.MISSING)));
    }

    @Test
    void premiumSourceIsRejected() {
        assertEquals(Optional.of(Rejection.SOURCE_PREMIUM),
                AccountTransferRules.firstRejection(withSource(SourceKind.PREMIUM)));
    }

    @Test
    void sameNameIsRejected() {
        assertEquals(Optional.of(Rejection.SAME_NAME), AccountTransferRules.firstRejection(
                new Request(SourceKind.CRACKED, false, true, true, false, false, false, false, TargetLicense.FREE)));
    }

    @Test
    void invalidTargetNameIsRejected() {
        assertEquals(Optional.of(Rejection.TARGET_INVALID_NAME), AccountTransferRules.firstRejection(
                new Request(SourceKind.CRACKED, false, false, false, false, false, false, false, TargetLicense.FREE)));
    }

    @Test
    void onlinePlayersBlockTransfer() {
        assertEquals(Optional.of(Rejection.SOURCE_ONLINE), AccountTransferRules.firstRejection(
                new Request(SourceKind.CRACKED, true, false, true, false, false, false, false, TargetLicense.FREE)));
        assertEquals(Optional.of(Rejection.TARGET_ONLINE), AccountTransferRules.firstRejection(
                new Request(SourceKind.CRACKED, false, false, true, true, false, false, false, TargetLicense.FREE)));
    }

    @Test
    void existingTargetAccountIsRejected() {
        assertEquals(Optional.of(Rejection.TARGET_ACCOUNT_EXISTS), AccountTransferRules.firstRejection(
                new Request(SourceKind.CRACKED, false, false, true, false, true, false, false, TargetLicense.FREE)));
    }

    @Test
    void existingTargetWorldDataIsRejected() {
        assertEquals(Optional.of(Rejection.TARGET_WORLD_DATA_EXISTS), AccountTransferRules.firstRejection(
                new Request(SourceKind.CRACKED, false, false, true, false, false, true, false, TargetLicense.FREE)));
    }

    @Test
    void listConflictIsRejected() {
        assertEquals(Optional.of(Rejection.TARGET_LIST_CONFLICT), AccountTransferRules.firstRejection(
                new Request(SourceKind.CRACKED, false, false, true, false, false, false, true, TargetLicense.FREE)));
    }

    @Test
    void licensedTargetIsRejected() {
        assertEquals(Optional.of(Rejection.TARGET_LICENSED), AccountTransferRules.firstRejection(
                new Request(SourceKind.CRACKED, false, false, true, false, false, false, false, TargetLicense.LICENSED)));
    }

    @Test
    void unknownLicenseStateFailsClosed() {
        // Mojang не ответил: переносить нельзя, иначе можно создать кракнутую запись на занятом нике
        assertEquals(Optional.of(Rejection.TARGET_LICENSE_UNKNOWN), AccountTransferRules.firstRejection(
                new Request(SourceKind.CRACKED, false, false, true, false, false, false, false, TargetLicense.UNKNOWN)));
    }

    @Test
    void earlierRejectionWinsOverLaterOne() {
        // Источник не найден — это важнее, чем занятый новый ник
        Optional<Rejection> rejection = AccountTransferRules.firstRejection(
                new Request(SourceKind.MISSING, true, false, true, true, true, true, true, TargetLicense.LICENSED));
        assertEquals(Optional.of(Rejection.SOURCE_NOT_FOUND), rejection);
    }

    @Test
    void everyRejectionHasMessage() {
        for (Rejection rejection : Rejection.values()) {
            assertTrue(!rejection.message().isBlank(), rejection.name());
        }
    }

    private static Request withSource(SourceKind source) {
        return new Request(source, false, false, true, false, false, false, false, TargetLicense.FREE);
    }
}
