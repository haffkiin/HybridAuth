package com.hybridauth.auth;

import com.hybridauth.api.AccountType;
import com.hybridauth.api.IdentityResolution;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HybridIdentityResolverTest {
    @Test
    void crackedPreservesExactCaseAndUsesOfflineUuid() {
        HybridIdentityResolver resolver = new HybridIdentityResolver(null);
        var result = resolver.resolveCracked("ReMure");

        assertTrue(result.isResolved());
        assertEquals(AccountType.CRACKED, result.identity().accountType());
        assertEquals("ReMure", result.identity().name());
        assertEquals(UUID.nameUUIDFromBytes("OfflinePlayer:ReMure".getBytes(StandardCharsets.UTF_8)), result.identity().uuid());
    }

    @Test
    void crackedRejectsInvalidName() {
        HybridIdentityResolver resolver = new HybridIdentityResolver(null);
        assertEquals(IdentityResolution.Status.INVALID_NAME, resolver.resolveCracked("bad-name").status());
    }
}
