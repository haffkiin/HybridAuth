package com.hybridauth.api;

import java.util.Objects;
import java.util.UUID;

public record ResolvedIdentity(
        String submittedName,
        String name,
        UUID uuid,
        AccountType accountType,
        ResolutionSource source) {

    public ResolvedIdentity {
        Objects.requireNonNull(submittedName, "submittedName");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(accountType, "accountType");
        Objects.requireNonNull(source, "source");
    }

    public enum ResolutionSource {
        MOJANG,
        OFFLINE
    }
}
