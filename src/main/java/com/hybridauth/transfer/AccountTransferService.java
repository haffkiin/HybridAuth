package com.hybridauth.transfer;

import com.hybridauth.HybridAuthMod;
import com.hybridauth.api.AccountTransferHandler;
import com.hybridauth.api.AccountTransferPlan;
import com.hybridauth.api.AccountTransfers;
import com.hybridauth.auth.AuthManager;
import com.hybridauth.auth.LicensedNameRules;
import com.hybridauth.auth.MinecraftNames;
import com.hybridauth.auth.OfflineUuid;
import com.hybridauth.auth.PremiumLookupResult;
import com.hybridauth.mixin.StoredUserEntryAccessor;
import com.hybridauth.storage.PlayerData;
import com.hybridauth.storage.PlayerStorage;
import com.hybridauth.transfer.AccountTransferRules.Rejection;
import com.hybridauth.transfer.AccountTransferRules.Request;
import com.hybridauth.transfer.AccountTransferRules.SourceKind;
import com.hybridauth.transfer.AccountTransferRules.TargetLicense;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.ServerOpList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.StoredUserList;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Перенос кракнутого аккаунта на новый ник: файлы мира, запись HybridAuth, whitelist, op, бан-лист
 * и обработчики других модов. Выполняется на потоке сервера, только когда оба аккаунта вне сервера.
 *
 * Каждый шаг регистрирует откат. Если шаг падает, откатываются все уже выполненные шаги
 * в обратном порядке. Бэкап создаётся до первого изменения и остаётся на диске.
 */
public final class AccountTransferService {

    private static final Logger LOGGER = LoggerFactory.getLogger("HybridAuth");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * @param success true, если перенос выполнен (или, для предпросмотра, может быть выполнен)
     * @param lines   строки для администратора
     */
    public record Report(boolean success, List<String> lines) {
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private AccountTransferService() {
    }

    /**
     * Лицензия нового ника по ответу Mojang. Нет ответа или API недоступен — UNKNOWN,
     * и перенос не выполняется (закрытое состояние).
     */
    public static TargetLicense licenseOf(String toName, PremiumLookupResult result) {
        if (result == null || result.status() == PremiumLookupResult.Status.API_UNAVAILABLE) {
            return TargetLicense.UNKNOWN;
        }
        return LicensedNameRules.isLicensedExactConflict(toName, result.status(), result.canonicalName())
                ? TargetLicense.LICENSED
                : TargetLicense.FREE;
    }

    /**
     * Проверяет перенос и при {@code execute = true} выполняет его.
     * Без {@code execute} данные не меняются: выводится план.
     */
    public static Report run(MinecraftServer server, Path configDir, String fromName, String toName,
                             TargetLicense license, boolean execute, String actor) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        PlayerStorage storage = authManager.getStorage();
        PlayerData source = storage.loadByExactUsername(fromName).orElse(null);
        SourceKind kind = source == null
                ? SourceKind.MISSING
                : source.isPremium() ? SourceKind.PREMIUM : SourceKind.CRACKED;

        UUID toId = OfflineUuid.forName(toName);
        PlayerFileTransfer files = files(server);
        boolean sourceOnline = server.getPlayerList().getPlayerByName(fromName) != null
                || (source != null && server.getPlayerList().getPlayer(source.getUuid()) != null);
        boolean targetOnline = server.getPlayerList().getPlayerByName(toName) != null
                || server.getPlayerList().getPlayer(toId) != null;

        Optional<Rejection> rejection = AccountTransferRules.firstRejection(new Request(
                kind,
                sourceOnline,
                fromName.equals(toName),
                MinecraftNames.isValid(toName),
                targetOnline,
                storage.load(toId).isPresent() || storage.loadByExactUsername(toName).isPresent(),
                files.hasWorldData(toId),
                listsConflict(server, toName, toId),
                license));
        if (rejection.isPresent()) {
            return new Report(false, List.of(rejection.get().message()));
        }

        if (!execute) {
            return new Report(true, previewLines(server, files, source, fromName, toName));
        }
        return execute(server, configDir, source, fromName, toName, toId, actor, files);
    }

    private static List<String> previewLines(MinecraftServer server, PlayerFileTransfer files,
                                             PlayerData source, String fromName, String toName) {
        UUID fromId = source.getUuid();
        UserWhiteList whitelist = server.getPlayerList().getWhiteList();
        ServerOpListEntry op = findEntry(server.getPlayerList().getOps().getEntries(), fromId);
        UserBanListEntry ban = findEntry(server.getPlayerList().getBans().getEntries(), fromId);

        List<String> lines = new ArrayList<>();
        lines.add("Перенос: " + fromName + " → " + toName);
        lines.add("Файлы мира (playerdata, stats, advancements): "
                + (files.hasWorldData(fromId) ? "будут перенесены" : "нет"));
        lines.add("Запись HybridAuth (пароль, код восстановления, даты входа): будет перенесена");
        lines.add("Whitelist: " + (findEntry(whitelist.getEntries(), fromId) != null ? "будет перенесён" : "нет"));
        lines.add("Операторы: " + (op != null ? "уровень " + op.getLevel() + ", будет перенесён" : "нет"));
        lines.add("Бан-лист: " + (ban != null ? "будет перенесён" : "нет"));
        List<String> handlers = handlerNames();
        lines.add("Обработчики других модов: " + (handlers.isEmpty() ? "нет" : String.join(", ", handlers)));
        lines.add("Для выполнения: /hybridauth transfer " + fromName + " " + toName + " confirm");
        return lines;
    }

    private static Report execute(MinecraftServer server, Path configDir, PlayerData source,
                                  String fromName, String toName, UUID toId, String actor, PlayerFileTransfer files) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        PlayerStorage storage = authManager.getStorage();
        UUID fromId = source.getUuid();
        UserWhiteList whitelist = server.getPlayerList().getWhiteList();
        ServerOpList ops = server.getPlayerList().getOps();
        UserBanList bans = server.getPlayerList().getBans();
        AccountTransferPlan plan = new AccountTransferPlan(fromId, fromName, toId, toName);

        Path backupDir = configDir.resolve("hybridauth").resolve("backups")
                .resolve("transfer-" + LocalDateTime.now().format(STAMP) + "-" + fromName + "-to-" + toName);
        Deque<Step> undo = new ArrayDeque<>();

        try {
            // 1. Бэкапы до первого изменения
            Files.createDirectories(backupDir);
            files.backup(fromId, backupDir);
            copyIfExists(whitelist.getFile().toPath(), backupDir.resolve(whitelist.getFile().getName()));
            copyIfExists(ops.getFile().toPath(), backupDir.resolve(ops.getFile().getName()));
            copyIfExists(bans.getFile().toPath(), backupDir.resolve(bans.getFile().getName()));
            if (!storage.createBackup()) {
                throw new IOException("не удалось создать бэкап players.json");
            }

            // 2. Файлы мира: NBT с новым UUID, stats и advancements переименовываются
            undo.push(() -> files.restore(fromId, toId, backupDir));
            files.move(fromId, toId);

            // 3. Запись авторизации: новый ник получает пароль и код восстановления
            undo.push(() -> {
                storage.save(source);
                storage.delete(toId);
            });
            storage.save(copyRecord(source, toId, toName));
            storage.delete(fromId);
            authManager.getSessionManager().endSession(fromName, fromId);

            // 4. Списки: whitelist, op, бан-лист
            undo.push(() -> restoreList(whitelist, backupDir));
            moveWhitelist(whitelist, fromId, toId, toName);
            undo.push(() -> restoreList(ops, backupDir));
            moveOps(ops, fromId, toId, toName);
            undo.push(() -> restoreList(bans, backupDir));
            moveBans(bans, fromId, toId, toName);

            // 5. Данные других модов (SMPIdentity, питомцы и т.д.)
            List<String> applied = new ArrayList<>();
            for (AccountTransferHandler handler : AccountTransfers.handlers()) {
                Runnable handlerUndo = handler.transfer(plan);
                undo.push(handlerUndo::run);
                applied.add(handler.name());
            }

            storage.flush();
            authManager.audit("ACCOUNT_TRANSFERRED", fromName, fromId, "-",
                    "to=" + toName + ";to_uuid=" + toId + ";by=" + actor
                            + ";backup=" + backupDir.getFileName() + ";handlers=" + applied);
            LOGGER.warn("[HybridAuth] Аккаунт {} перенесён на ник {} ({}).", fromName, toName, actor);

            List<String> lines = new ArrayList<>();
            lines.add("Перенос выполнен: " + fromName + " → " + toName);
            lines.add("Бэкап: " + backupDir);
            lines.add("Игрок заходит под новым ником " + toName + " и вводит прежний пароль.");
            return new Report(true, lines);
        } catch (Exception exception) {
            LOGGER.error("[HybridAuth] Перенос {} → {} не выполнен, откат.", fromName, toName, exception);
            rollback(undo);
            return new Report(false, List.of(
                    "Перенос отменён, исходные данные восстановлены: " + exception.getMessage(),
                    "Бэкап: " + backupDir));
        }
    }

    private static void rollback(Deque<Step> undo) {
        while (!undo.isEmpty()) {
            Step step = undo.pop();
            try {
                step.run();
            } catch (Exception exception) {
                LOGGER.error("[HybridAuth] Шаг отката не выполнен — восстановите данные из бэкапа.", exception);
            }
        }
    }

    private static void moveWhitelist(UserWhiteList whitelist, UUID fromId, UUID toId, String toName) throws IOException {
        UserWhiteListEntry entry = findEntry(whitelist.getEntries(), fromId);
        if (entry == null) {
            return;
        }
        whitelist.remove(entry);
        GameProfile target = new GameProfile(toId, toName);
        if (!whitelist.isWhiteListed(target)) {
            whitelist.add(new UserWhiteListEntry(target));
        }
        whitelist.save();
    }

    private static void moveOps(ServerOpList ops, UUID fromId, UUID toId, String toName) throws IOException {
        ServerOpListEntry entry = findEntry(ops.getEntries(), fromId);
        if (entry == null) {
            return;
        }
        ops.remove(entry);
        ops.add(new ServerOpListEntry(new GameProfile(toId, toName), entry.getLevel(), entry.getBypassesPlayerLimit()));
        ops.save();
    }

    private static void moveBans(UserBanList bans, UUID fromId, UUID toId, String toName) throws IOException {
        UserBanListEntry entry = findEntry(bans.getEntries(), fromId);
        if (entry == null) {
            return;
        }
        bans.remove(entry);
        bans.add(new UserBanListEntry(
                new GameProfile(toId, toName),
                entry.getCreated(),
                entry.getSource(),
                entry.getExpires(),
                entry.getReason()));
        bans.save();
    }

    /** Откат списка: файл из бэкапа (или удаление, если его не было) и перечитывание в память. */
    private static void restoreList(StoredUserList<?, ?> list, Path backupDir) throws IOException {
        Path file = list.getFile().toPath();
        Path backup = backupDir.resolve(file.getFileName().toString());
        if (Files.isRegularFile(backup)) {
            Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(file);
        }
        list.load();
    }

    /** Конфликт: в whitelist есть другой UUID с этим ником, либо на новом UUID уже есть op или бан. */
    private static boolean listsConflict(MinecraftServer server, String toName, UUID toId) {
        for (UserWhiteListEntry entry : server.getPlayerList().getWhiteList().getEntries()) {
            GameProfile profile = profileOf(entry);
            if (profile != null && toName.equals(profile.getName()) && !toId.equals(profile.getId())) {
                return true;
            }
        }
        return findEntry(server.getPlayerList().getOps().getEntries(), toId) != null
                || findEntry(server.getPlayerList().getBans().getEntries(), toId) != null;
    }

    private static PlayerData copyRecord(PlayerData source, UUID toId, String toName) {
        PlayerData copy = new PlayerData(toId, toName, PlayerData.PlayerType.CRACKED);
        copy.setPasswordHash(source.getPasswordHash());
        copy.setRecoveryCodeHash(source.getRecoveryCodeHash());
        copy.setRegisteredAt(source.getRegisteredAt());
        copy.setLastLoginAt(source.getLastLoginAt());
        copy.setLastLoginIp(source.getLastLoginIp());
        return copy;
    }

    private static PlayerFileTransfer files(MinecraftServer server) {
        return new PlayerFileTransfer(
                server.getWorldPath(LevelResource.PLAYER_DATA_DIR),
                server.getWorldPath(LevelResource.PLAYER_STATS_DIR),
                server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR));
    }

    private static List<String> handlerNames() {
        List<String> names = new ArrayList<>();
        for (AccountTransferHandler handler : AccountTransfers.handlers()) {
            names.add(handler.name());
        }
        return names;
    }

    private static void copyIfExists(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Запись списка, относящаяся к UUID; сопоставляем по UUID, а не по ключу списка. */
    private static <T extends StoredUserEntry<GameProfile>> T findEntry(Iterable<T> entries, UUID id) {
        for (T entry : entries) {
            GameProfile profile = profileOf(entry);
            if (profile != null && id.equals(profile.getId())) {
                return entry;
            }
        }
        return null;
    }

    private static GameProfile profileOf(StoredUserEntry<GameProfile> entry) {
        return (GameProfile) ((StoredUserEntryAccessor) entry).hybridauth$getUser();
    }
}
