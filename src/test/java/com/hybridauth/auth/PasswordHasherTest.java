package com.hybridauth.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

    @Test
    void roundTripVerifiesCorrectPassword() {
        String hash = PasswordHasher.hash("S3cure-Passw0rd!");
        assertTrue(PasswordHasher.verify("S3cure-Passw0rd!", hash));
    }

    @Test
    void wrongPasswordFails() {
        String hash = PasswordHasher.hash("correct horse");
        assertFalse(PasswordHasher.verify("wrong horse", hash));
    }

    @Test
    void hashesAreSaltedAndUnique() {
        assertNotEquals(PasswordHasher.hash("same"), PasswordHasher.hash("same"));
    }

    @Test
    void malformedStoredHashesAreRejected() {
        assertFalse(PasswordHasher.verify("password", ""));
        assertFalse(PasswordHasher.verify("password", "not-a-hash"));
        assertFalse(PasswordHasher.verify("password", "1:salt"));
        assertFalse(PasswordHasher.verify("password", "abc:!!!:!!!"));
    }

    @Test
    void legacyLowIterationHashStillVerifiesAndNeedsRehash() {
        String legacyHash = PasswordHasher.hash("legacy password", 65536);
        assertTrue(PasswordHasher.verify("legacy password", legacyHash),
                "Хеш со старым рабочим фактором должен проходить проверку");
        assertTrue(PasswordHasher.needsRehash(legacyHash),
                "Старый рабочий фактор должен помечаться для перехеширования");
        assertFalse(PasswordHasher.verify("wrong", legacyHash));
    }

    @Test
    void currentHashDoesNotNeedRehash() {
        String hash = PasswordHasher.hash("modern password");
        assertFalse(PasswordHasher.needsRehash(hash));
    }

    @Test
    void rehashUpgradesSecurityWithoutChangingPassword() {
        String legacyHash = PasswordHasher.hash("password", 65536);
        assertTrue(PasswordHasher.needsRehash(legacyHash));

        // Эмуляция перехеширования при успешном входе
        String upgraded = PasswordHasher.hash("password");
        assertTrue(PasswordHasher.verify("password", upgraded));
        assertFalse(PasswordHasher.needsRehash(upgraded));
    }
}
