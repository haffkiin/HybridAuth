package com.hybridauth.whitelist;

import com.hybridauth.api.ResolvedIdentity;
import com.hybridauth.api.WhitelistGateway;
import com.hybridauth.audit.AuthAuditLogger;
import com.hybridauth.storage.PlayerData;
import com.hybridauth.storage.PlayerStorage;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class WhitelistGatewayImpl implements WhitelistGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger("HybridAuth");

    private final PlayerStorage storage;
    private final AuthAuditLogger auditLogger;

    public WhitelistGatewayImpl(PlayerStorage storage, AuthAuditLogger auditLogger) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger");
    }

    @Override
    public AddOutcome add(MinecraftServer server, ResolvedIdentity identity) {
        requireServerThread(server);
        UserWhiteList whitelist = server.getPlayerList().getWhiteList();
        List<UserWhiteListEntry> entries = new ArrayList<>(whitelist.getEntries());

        for (UserWhiteListEntry entry : entries) {
            GameProfile existing = WhitelistEntries.profile(entry);
            if (existing == null) {
                continue;
            }
            if (existing.getId().equals(identity.uuid())) {
                return new AddOutcome(AddStatus.ALREADY_PRESENT, existing.getName(), existing.getId());
            }
            if (existing.getName().equals(identity.name())) {
                return new AddOutcome(AddStatus.EXACT_NAME_UUID_CONFLICT, existing.getName(), existing.getId());
            }
            if (identity.accountType() == com.hybridauth.api.AccountType.PREMIUM
                    && existing.getName().equalsIgnoreCase(identity.name())) {
                PlayerData known = storage.loadByExactUsername(existing.getName()).orElse(null);
                if (known == null || known.isPremium()) {
                    return new AddOutcome(AddStatus.PREMIUM_NAME_CONFLICT, existing.getName(), existing.getId());
                }
            }
        }

        String warning = identity.accountType() == com.hybridauth.api.AccountType.CRACKED
                ? crackedNameWarning(identity.name())
                : null;

        GameProfile profile = new GameProfile(identity.uuid(), identity.name());
        whitelist.add(new UserWhiteListEntry(profile));
        if (!whitelist.isWhiteListed(profile)) {
            return new AddOutcome(AddStatus.SAVE_FAILED, identity.name(), identity.uuid());
        }
        try {
            whitelist.save();
        } catch (IOException exception) {
            return new AddOutcome(AddStatus.SAVE_FAILED, identity.name(), identity.uuid());
        }
        if (warning != null) {
            LOGGER.warn("[HybridAuth] {}", warning);
        }
        return new AddOutcome(AddStatus.ADDED, identity.name(), identity.uuid(), warning);
    }

    /**
     * Кракнутый offline-UUID для ника, который уже принадлежит лицензионной записи в базе,
     * не даст владельцу войти. Добавление не отменяем: решение за модератором.
     */
    private String crackedNameWarning(String name) {
        return storage.loadByExactUsername(name)
                .filter(PlayerData::isPremium)
                .map(premium -> "Ник " + name + " уже принадлежит лицензионному аккаунту "
                        + premium.getUuid() + ". Кракнутая запись с этим ником конфликтует с владельцем лицензии.")
                .orElse(null);
    }

    @Override
    public RemovalOutcome remove(MinecraftServer server, String exactName) {
        requireServerThread(server);
        UserWhiteList whitelist = server.getPlayerList().getWhiteList();
        List<UserWhiteListEntry> matches = new ArrayList<>();
        boolean caseMismatch = false;
        for (UserWhiteListEntry entry : whitelist.getEntries()) {
            GameProfile profile = WhitelistEntries.profile(entry);
            if (profile == null) {
                continue;
            }
            if (profile.getName().equals(exactName)) {
                matches.add(entry);
            } else if (profile.getName().equalsIgnoreCase(exactName)) {
                caseMismatch = true;
            }
        }
        if (matches.isEmpty()) {
            return new RemovalOutcome(
                    caseMismatch ? RemovalStatus.CASE_MISMATCH : RemovalStatus.NOT_FOUND,
                    caseMismatch ? exactName : null,
                    0);
        }

        for (UserWhiteListEntry match : matches) {
            whitelist.remove(match);
        }
        try {
            whitelist.save();
        } catch (IOException exception) {
            return new RemovalOutcome(RemovalStatus.SAVE_FAILED, exactName, matches.size());
        }
        return new RemovalOutcome(RemovalStatus.REMOVED, exactName, matches.size());
    }

    @Override
    public void audit(WhitelistAuditEvent event) {
        String details = String.join(
                ",",
                "action=" + safe(event.action()),
                "actor_id=" + safe(event.actorId()),
                "actor_name=" + safe(event.actorName()),
                "submitted=" + safe(event.submittedName()),
                "resolved=" + safe(event.resolvedName()),
                "account_type=" + safe(event.accountType()),
                "source=" + safe(event.resolutionSource()),
                "result=" + safe(event.result()),
                "reason=" + safe(event.reason()));
        auditLogger.log("WHITELIST_" + safe(event.action()), event.resolvedName(), event.uuid(), "discord", details);
    }

    private static void requireServerThread(MinecraftServer server) {
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("Whitelist operations must run on the Minecraft server thread");
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
