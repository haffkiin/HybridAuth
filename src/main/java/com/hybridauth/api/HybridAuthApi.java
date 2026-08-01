package com.hybridauth.api;

import java.util.Objects;
import java.util.Optional;

public final class HybridAuthApi {
    private static volatile HybridAuthApi instance;

    private final IdentityResolver identityResolver;
    private final WhitelistGateway whitelistGateway;

    public HybridAuthApi(IdentityResolver identityResolver, WhitelistGateway whitelistGateway) {
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.whitelistGateway = Objects.requireNonNull(whitelistGateway, "whitelistGateway");
    }

    public static Optional<HybridAuthApi> get() {
        return Optional.ofNullable(instance);
    }

    public static void install(HybridAuthApi api) {
        instance = Objects.requireNonNull(api, "api");
    }

    public static void clear() {
        instance = null;
    }

    public IdentityResolver identities() {
        return identityResolver;
    }

    public WhitelistGateway whitelist() {
        return whitelistGateway;
    }
}
