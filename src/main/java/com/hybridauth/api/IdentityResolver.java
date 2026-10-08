package com.hybridauth.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface IdentityResolver {
    CompletableFuture<IdentityResolution> resolvePremium(String submittedName);

    IdentityResolution resolveCracked(String submittedName);

    /**
     * Предупреждение перед добавлением кракнутого аккаунта: ник точно совпадает
     * с лицензионным ником Mojang. Нужно вызывать до {@code whitelist().add} для кракнутых ников.
     *
     * @return текст предупреждения или пусто
     */
    default CompletableFuture<Optional<String>> licensedNickWarning(String submittedName) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
