package com.hybridauth.auth;

import com.hybridauth.api.AccountType;
import com.hybridauth.api.IdentityResolution;
import com.hybridauth.api.IdentityResolver;
import com.hybridauth.api.ResolvedIdentity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class HybridIdentityResolver implements IdentityResolver {
    private final MojangApiClient mojang;

    public HybridIdentityResolver(MojangApiClient mojang) {
        this.mojang = mojang;
    }

    @Override
    public CompletableFuture<IdentityResolution> resolvePremium(String submittedName) {
        String name = normalize(submittedName);
        if (!MinecraftNames.isValid(name)) {
            return CompletableFuture.completedFuture(IdentityResolution.failed(IdentityResolution.Status.INVALID_NAME));
        }
        return mojang.checkPremium(name).thenApply(result -> switch (result.status()) {
            case PREMIUM -> IdentityResolution.resolved(new ResolvedIdentity(
                    name,
                    result.canonicalName(),
                    result.premiumUuid(),
                    AccountType.PREMIUM,
                    ResolvedIdentity.ResolutionSource.MOJANG));
            case NOT_PREMIUM -> IdentityResolution.failed(IdentityResolution.Status.NOT_PREMIUM);
            case API_UNAVAILABLE -> IdentityResolution.failed(IdentityResolution.Status.API_UNAVAILABLE);
        });
    }

    @Override
    public IdentityResolution resolveCracked(String submittedName) {
        String name = normalize(submittedName);
        if (!MinecraftNames.isValid(name)) {
            return IdentityResolution.failed(IdentityResolution.Status.INVALID_NAME);
        }
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return IdentityResolution.resolved(new ResolvedIdentity(
                name,
                name,
                uuid,
                AccountType.CRACKED,
                ResolvedIdentity.ResolutionSource.OFFLINE));
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim();
    }
}
