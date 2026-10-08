package com.hybridauth.api;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

public interface WhitelistGateway {
    AddOutcome add(MinecraftServer server, ResolvedIdentity identity);

    RemovalOutcome remove(MinecraftServer server, String exactName);

    void audit(WhitelistAuditEvent event);

    enum AddStatus {
        ADDED,
        ALREADY_PRESENT,
        EXACT_NAME_UUID_CONFLICT,
        PREMIUM_NAME_CONFLICT,
        SAVE_FAILED
    }

    /**
     * @param warning предупреждение для модератора (например, кракнутый ник совпадает
     *                с лицензионным аккаунтом) либо null. Добавление при этом выполнено.
     */
    record AddOutcome(AddStatus status, String name, UUID uuid, String warning) {

        public AddOutcome(AddStatus status, String name, UUID uuid) {
            this(status, name, uuid, null);
        }
    }

    enum RemovalStatus {
        REMOVED,
        NOT_FOUND,
        CASE_MISMATCH,
        SAVE_FAILED
    }

    record RemovalOutcome(RemovalStatus status, String matchedName, int removedCount) {
    }

    record WhitelistAuditEvent(
            String action,
            String actorId,
            String actorName,
            String submittedName,
            String resolvedName,
            UUID uuid,
            String accountType,
            String resolutionSource,
            String result,
            String reason) {
    }
}
