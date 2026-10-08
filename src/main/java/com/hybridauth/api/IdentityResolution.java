package com.hybridauth.api;

public record IdentityResolution(Status status, ResolvedIdentity identity) {
    public enum Status {
        RESOLVED,
        INVALID_NAME,
        NOT_PREMIUM,
        API_UNAVAILABLE
    }

    public static IdentityResolution resolved(ResolvedIdentity identity) {
        return new IdentityResolution(Status.RESOLVED, identity);
    }

    public static IdentityResolution failed(Status status) {
        if (status == Status.RESOLVED) {
            throw new IllegalArgumentException("Для статуса RESOLVED нужен профиль игрока");
        }
        return new IdentityResolution(status, null);
    }

    public boolean isResolved() {
        return status == Status.RESOLVED && identity != null;
    }
}
