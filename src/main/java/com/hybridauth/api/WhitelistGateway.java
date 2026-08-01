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

    record AddOutcome(AddStatus status, String name, UUID uuid) {
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
