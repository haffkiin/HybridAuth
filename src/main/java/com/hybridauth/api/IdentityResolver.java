package com.hybridauth.api;

import java.util.concurrent.CompletableFuture;

public interface IdentityResolver {
    CompletableFuture<IdentityResolution> resolvePremium(String submittedName);

    IdentityResolution resolveCracked(String submittedName);
}
