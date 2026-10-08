package com.hybridauth.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicensedNameRulesTest {

    @Test
    void exactCanonicalPremiumNameIsConflict() {
        assertTrue(LicensedNameRules.isLicensedExactConflict(
                "ReMure", PremiumLookupResult.Status.PREMIUM, "ReMure"));
    }

    @Test
    void differentCaseIsAllowedPair() {
        // Кракнутый remure рядом с лицензионным ReMure — разрешённая пара
        assertFalse(LicensedNameRules.isLicensedExactConflict(
                "remure", PremiumLookupResult.Status.PREMIUM, "ReMure"));
    }

    @Test
    void notPremiumIsNotConflict() {
        assertFalse(LicensedNameRules.isLicensedExactConflict(
                "Steve", PremiumLookupResult.Status.NOT_PREMIUM, null));
    }

    @Test
    void apiFailureIsNotConflict() {
        // При сбое Mojang кракнутая запись входит как раньше, конфликт не определён
        assertFalse(LicensedNameRules.isLicensedExactConflict(
                "Steve", PremiumLookupResult.Status.API_UNAVAILABLE, null));
    }

    @Test
    void nullUsernameIsNotConflict() {
        assertFalse(LicensedNameRules.isLicensedExactConflict(
                null, PremiumLookupResult.Status.PREMIUM, "ReMure"));
    }
}
