package com.hybridauth.whitelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hybridauth.auth.OfflineUuid;
import com.hybridauth.storage.PlayerData;
import com.hybridauth.storage.PlayerStorage;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class WhitelistRepairService {
    private static final Logger LOGGER = LoggerFactory.getLogger("HybridAuth");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private WhitelistRepairService() {
    }

    public static RepairReport repairVerifiedPremiumEntries(
            MinecraftServer server,
            PlayerStorage storage,
            Path configDir) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Whitelist repair must run on the Minecraft server thread");
        }

        UserWhiteList whitelist = server.getPlayerList().getWhiteList();
        List<Change> changes = new ArrayList<>();
        List<RepairAction> actions = new ArrayList<>();
        List<UserWhiteListEntry> entries = new ArrayList<>(whitelist.getEntries());
        List<PlayerData> players = storage.listAll();

        for (PlayerData premium : players) {
            if (!premium.isPremium() || premium.getUuid() == null || premium.getUsername() == null) {
                continue;
            }

            List<UserWhiteListEntry> stale = entries.stream()
                    .filter(entry -> WhitelistEntries.profile(entry) != null)
                    .filter(entry -> WhitelistEntries.profile(entry).getName().equalsIgnoreCase(premium.getUsername()))
                    .filter(entry -> !premium.getUuid().equals(WhitelistEntries.profile(entry).getId()))
                    .filter(entry -> OfflineUuid.forName(WhitelistEntries.profile(entry).getName()).equals(WhitelistEntries.profile(entry).getId()))
                    .filter(entry -> storage.loadByExactUsername(WhitelistEntries.profile(entry).getName())
                            .map(PlayerData::isCracked).orElse(false) == false)
                    .toList();

            if (stale.isEmpty()) {
                continue;
            }

            boolean hasCorrect = entries.stream()
                    .filter(entry -> WhitelistEntries.profile(entry) != null)
                    .anyMatch(entry -> premium.getUuid().equals(WhitelistEntries.profile(entry).getId()));
            if (!hasCorrect) {
                actions.add(RepairAction.add(new GameProfile(premium.getUuid(), premium.getUsername())));
                changes.add(new Change("ADD_CORRECT_PREMIUM", premium.getUsername(), null, premium.getUuid()));
            }
            for (UserWhiteListEntry entry : stale) {
                GameProfile profile = WhitelistEntries.profile(entry);
                actions.add(RepairAction.remove(entry));
                changes.add(new Change("REMOVE_STALE_OFFLINE", profile.getName(), profile.getId(), premium.getUuid()));
            }
        }

        if (changes.isEmpty()) {
            LOGGER.info("[HybridAuth] Verified whitelist repair found no safe changes.");
            return new RepairReport(false, null, null, List.of());
        }

        Path whitelistFile = whitelist.getFile().toPath();
        Path backup = createBackup(whitelistFile, configDir);
        try {
            for (RepairAction action : actions) {
                if (action.add()) {
                    whitelist.add(new UserWhiteListEntry(action.profile()));
                } else {
                    whitelist.remove(action.entry());
                }
            }
            whitelist.save();
        } catch (IOException exception) {
            LOGGER.error("[HybridAuth] Could not persist repaired whitelist; backup remains at {}", backup, exception);
            return new RepairReport(true, backup.toString(), null, changes);
        }

        Path report = configDir.resolve("hybridauth").resolve(
                "whitelist-repair-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                        .format(java.time.LocalDateTime.now()) + ".json");
        RepairReport result = new RepairReport(true, backup.toString(), report.toString(), changes);
        try {
            Files.createDirectories(report.getParent());
            Files.writeString(report, GSON.toJson(result), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.error("[HybridAuth] Repaired whitelist but could not write report", exception);
        }
        LOGGER.warn("[HybridAuth] Repaired {} verified whitelist entries; backup: {}", changes.size(), backup);
        return result;
    }

    private static Path createBackup(Path whitelistFile, Path configDir) {
        try {
            Path directory = configDir.resolve("hybridauth").resolve("backups");
            Files.createDirectories(directory);
            Path backup = directory.resolve("whitelist-" + Instant.now().toEpochMilli() + ".json");
            Files.copy(whitelistFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
            return backup;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not back up whitelist before repair", exception);
        }
    }

    public record RepairReport(boolean changed, String backupFile, String reportFile, List<Change> changes) {
    }

    public record Change(String action, String name, UUID oldUuid, UUID newUuid) {
    }

    private record RepairAction(boolean add, GameProfile profile, UserWhiteListEntry entry) {
        static RepairAction add(GameProfile profile) {
            return new RepairAction(true, profile, null);
        }

        static RepairAction remove(UserWhiteListEntry entry) {
            return new RepairAction(false, null, entry);
        }
    }
}
