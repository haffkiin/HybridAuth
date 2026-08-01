package com.hybridauth.auth;

import java.util.UUID;

public record PremiumLookupResult(Status status, UUID premiumUuid, String canonicalName) {
    public enum Status {
        PREMIUM,
        NOT_PREMIUM,
        API_UNAVAILABLE
    }

    public static PremiumLookupResult premium(UUID uuid, String canonicalName) {
        return new PremiumLookupResult(Status.PREMIUM, uuid, canonicalName);
    }

    /** Backward-compatible factory for integrations that only need the UUID. */
    public static PremiumLookupResult premium(UUID uuid) {
        return premium(uuid, "");
    }

    public static PremiumLookupResult notPremium() {
        return new PremiumLookupResult(Status.NOT_PREMIUM, null, null);
    }

    public static PremiumLookupResult apiUnavailable() {
        return new PremiumLookupResult(Status.API_UNAVAILABLE, null, null);
    }
}
