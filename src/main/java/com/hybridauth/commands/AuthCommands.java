package com.hybridauth.commands;

import com.hybridauth.HybridAuthMod;
import com.hybridauth.auth.AuthManager;
import com.hybridauth.auth.PasswordHasher;
import com.hybridauth.auth.RecoveryCodeGenerator;
import com.hybridauth.config.ModConfig;
import com.hybridauth.storage.PlayerData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

public class AuthCommands {

    private static final int ADMIN_LIST_LIMIT = 30;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerRegisterCommand(dispatcher, "register");
        registerRegisterCommand(dispatcher, "reg");
        registerLoginCommand(dispatcher, "login");
        registerLoginCommand(dispatcher, "l");
        registerRecoveryCommands(dispatcher);
        registerChangePasswordCommand(dispatcher);

        dispatcher.register(Commands.literal("hybridauth")
                .requires(source -> source.hasPermission(3))
                .then(Commands.literal("reload").executes(context -> {
                    HybridAuthMod.getAuthManager().reloadConfig();
                    HybridAuthMod.getMojangClient().setTimeoutMs(ModConfig.SERVER.mojangApiTimeoutMs.get());
                    HybridAuthMod.getMojangClient().setCacheExpirationMinutes(ModConfig.SERVER.cacheExpirationMinutes.get());
                    context.getSource().sendSuccess(
                            () -> Component.literal(colorize(ModConfig.SERVER.msgAdminReloaded.get())), true);
                    return 1;
                }))
                .then(Commands.literal("backup").executes(context -> {
                    boolean created = HybridAuthMod.getAuthManager().getStorage().createBackup();
                    if (created) {
                        context.getSource().sendSuccess(
                                () -> Component.literal(colorize(ModConfig.SERVER.msgAdminBackupCreated.get())), true);
                        return 1;
                    }
                    context.getSource().sendFailure(
                            Component.literal(colorize(ModConfig.SERVER.msgAdminBackupFailed.get())));
                    return 0;
                }))
                .then(Commands.literal("recovery")
                        .then(Commands.argument("username", StringArgumentType.word())
                                .executes(context -> handleAdminRecoveryCode(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "username")))))
                .then(Commands.literal("info")
                        .then(Commands.argument("username", StringArgumentType.word())
                                .executes(context -> handleAdminInfo(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "username")))))
                .then(Commands.literal("unregister")
                        .then(Commands.argument("username", StringArgumentType.word())
                                .executes(context -> handleAdminUnregister(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "username")))))
                .then(Commands.literal("list").executes(context -> handleAdminList(context.getSource())))
                .then(Commands.literal("status").executes(context -> handleAdminStatus(context.getSource()))));
    }

    private static void registerRegisterCommand(CommandDispatcher<CommandSourceStack> dispatcher, String command) {
        dispatcher.register(Commands.literal(command)
                .then(Commands.argument("password", StringArgumentType.string())
                        .then(Commands.argument("confirm", StringArgumentType.string())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    String pass = StringArgumentType.getString(context, "password");
                                    String confirm = StringArgumentType.getString(context, "confirm");
                                    return handleRegister(player, pass, confirm);
                                }))));
    }

    private static void registerLoginCommand(CommandDispatcher<CommandSourceStack> dispatcher, String command) {
        dispatcher.register(Commands.literal(command)
                .then(Commands.argument("password", StringArgumentType.greedyString())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            String pass = StringArgumentType.getString(context, "password");
                            return handleLogin(player, pass);
                        })));
    }

    private static void registerRecoveryCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("recoverycode")
                .executes(context -> handleNewRecoveryCode(context.getSource().getPlayerOrException())));

        dispatcher.register(Commands.literal("recover")
                .then(Commands.argument("code", StringArgumentType.word())
                        .then(Commands.argument("password", StringArgumentType.string())
                                .then(Commands.argument("confirm", StringArgumentType.string())
                                        .executes(context -> handleRecover(
                                                context.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(context, "code"),
                                                StringArgumentType.getString(context, "password"),
                                                StringArgumentType.getString(context, "confirm")))))));
    }

    private static void registerChangePasswordCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("changepassword")
                .then(Commands.argument("oldPassword", StringArgumentType.string())
                        .then(Commands.argument("newPassword", StringArgumentType.string())
                                .then(Commands.argument("confirm", StringArgumentType.string())
                                        .executes(context -> handleChangePassword(
                                                context.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(context, "oldPassword"),
                                                StringArgumentType.getString(context, "newPassword"),
                                                StringArgumentType.getString(context, "confirm")))))));
    }

    private static int handleRegister(ServerPlayer player, String password, String confirm) {
        AuthManager authManager = HybridAuthMod.getAuthManager();

        if (authManager.isAuthenticated(player.getUUID())) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgAlreadyLoggedIn.get())));
            return 0;
        }

        if (findPlayerData(authManager, player) != null) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgAlreadyRegistered.get())));
            return 0;
        }

        if (!validateNewPassword(player, password, confirm)) {
            return 0;
        }

        String recoveryCode = RecoveryCodeGenerator.generate();
        PlayerData data = new PlayerData(player.getUUID(), player.getScoreboardName(), PlayerData.PlayerType.CRACKED);
        data.setPasswordHash(PasswordHasher.hash(password));
        data.setRecoveryCodeHash(PasswordHasher.hash(RecoveryCodeGenerator.normalize(recoveryCode)));
        authManager.getStorage().save(data);

        player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgRegisterSuccess.get())));
        authManager.authenticate(player, true, "REGISTER");
        sendRecoveryCode(player, recoveryCode);
        return 1;
    }

    private static int handleLogin(ServerPlayer player, String password) {
        AuthManager authManager = HybridAuthMod.getAuthManager();

        if (authManager.isAuthenticated(player.getUUID())) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgAlreadyLoggedIn.get())));
            return 0;
        }

        if (!authManager.loginAttemptStatus(player).allowed()) {
            player.connection.disconnect(Component.literal(colorize(ModConfig.SERVER.msgTooManyAttempts.get())));
            return 0;
        }

        PlayerData data = findPlayerData(authManager, player);
        if (data == null) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgNotRegistered.get())));
            // Учитываем попытки и для незарегистрированных ников — иначе перебор ников не лимитируется
            recordFailedAttempt(player, "LOGIN_FAILURE", "reason=not_registered", false);
            return 0;
        }

        if (data.isPremium()) {
            if (player.getUUID().equals(data.getUuid())) {
                authManager.authenticate(player, false, "PREMIUM");
                return 1;
            }
            authManager.audit(player, "PREMIUM_UUID_MISMATCH", "stored_uuid=" + data.getUuid());
            player.connection.disconnect(Component.literal(colorize(ModConfig.SERVER.msgPremiumKick.get())));
            return 0;
        }

        if (password.length() > ModConfig.SERVER.maxPasswordLength.get()) {
            return recordFailedAttempt(player, "LOGIN_FAILURE", "reason=password_too_long");
        }

        if (PasswordHasher.verify(password, data.getPasswordHash())) {
            // Прозрачное перехеширование старых записей (PBKDF2 с меньшим числом итераций)
            if (PasswordHasher.needsRehash(data.getPasswordHash())) {
                data.setPasswordHash(PasswordHasher.hash(password));
            }
            authManager.clearFailedAttempts(player);
            authManager.authenticate(player, false, "PASSWORD");
            return 1;
        }

        return recordFailedAttempt(player, "LOGIN_FAILURE", "reason=wrong_password");
    }

    private static int handleChangePassword(ServerPlayer player, String oldPassword, String newPassword, String confirm) {
        AuthManager authManager = HybridAuthMod.getAuthManager();

        if (!authManager.isAuthenticated(player.getUUID())) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgLoginRequired.get())));
            return 0;
        }

        PlayerData data = findPlayerData(authManager, player);
        if (data == null || data.isPremium() || data.getPasswordHash() == null) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgPasswordAuthOnly.get())));
            return 0;
        }

        if (!authManager.loginAttemptStatus(player).allowed()) {
            player.connection.disconnect(Component.literal(colorize(ModConfig.SERVER.msgTooManyAttempts.get())));
            return 0;
        }

        if (oldPassword.length() <= ModConfig.SERVER.maxPasswordLength.get()
                && PasswordHasher.verify(oldPassword, data.getPasswordHash())) {
            if (!validateNewPassword(player, newPassword, confirm)) {
                return 0;
            }
            data.setPasswordHash(PasswordHasher.hash(newPassword));
            authManager.getStorage().save(data);
            authManager.getSessionManager().endSession(data.getUsername(), data.getUuid());
            authManager.audit(player, "PASSWORD_CHANGED", null);
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgPasswordChanged.get())));
            return 1;
        }

        return recordFailedAttempt(player, "PASSWORD_CHANGE_FAILURE", "reason=wrong_old_password");
    }

    private static int handleNewRecoveryCode(ServerPlayer player) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        if (!authManager.isAuthenticated(player.getUUID())) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgLoginRequired.get())));
            return 0;
        }

        PlayerData data = findPlayerData(authManager, player);
        if (data == null || data.isPremium()) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgRecoveryOnlyPassword.get())));
            return 0;
        }

        String recoveryCode = RecoveryCodeGenerator.generate();
        data.setRecoveryCodeHash(PasswordHasher.hash(RecoveryCodeGenerator.normalize(recoveryCode)));
        authManager.getStorage().save(data);
        authManager.audit(player, "RECOVERY_CODE_ISSUED", "issuer=player");
        sendRecoveryCode(player, recoveryCode);
        return 1;
    }

    private static int handleRecover(ServerPlayer player, String code, String password, String confirm) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        if (authManager.isAuthenticated(player.getUUID())) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgAlreadyLoggedIn.get())));
            return 0;
        }
        if (!authManager.loginAttemptStatus(player).allowed()) {
            player.connection.disconnect(Component.literal(colorize(ModConfig.SERVER.msgTooManyAttempts.get())));
            return 0;
        }

        PlayerData data = findPlayerData(authManager, player);
        if (data == null || data.isPremium() || data.getRecoveryCodeHash() == null) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgRecoveryNotConfigured.get()
                    .replace("%username%", player.getScoreboardName()))));
            authManager.audit(player, "RECOVERY_FAILURE", "reason=not_configured");
            return 0;
        }

        String normalizedCode = RecoveryCodeGenerator.normalize(code);
        if (normalizedCode.length() > 64) {
            return recordFailedAttempt(player, "RECOVERY_FAILURE", "reason=code_too_long");
        }
        if (!PasswordHasher.verify(normalizedCode, data.getRecoveryCodeHash())) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgInvalidRecoveryCode.get())));
            return recordFailedAttempt(player, "RECOVERY_FAILURE", "reason=invalid_code");
        }

        if (!validateNewPassword(player, password, confirm)) {
            return 0;
        }

        String nextRecoveryCode = RecoveryCodeGenerator.generate();
        data.setPasswordHash(PasswordHasher.hash(password));
        data.setRecoveryCodeHash(PasswordHasher.hash(RecoveryCodeGenerator.normalize(nextRecoveryCode)));
        authManager.getSessionManager().endSession(data.getUsername(), data.getUuid());
        authManager.getStorage().save(data);
        authManager.clearFailedAttempts(player);

        authManager.authenticate(player, false, "RECOVERY");
        player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgRecoverySuccess.get())));
        sendRecoveryCode(player, nextRecoveryCode);
        return 1;
    }

    private static int handleAdminRecoveryCode(CommandSourceStack source, String username) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        PlayerData data = authManager.getStorage().loadByExactUsername(username).orElse(null);
        if (data == null) {
            source.sendFailure(Component.literal(colorize(ModConfig.SERVER.msgAdminAccountNotFound.get()
                    .replace("%username%", username))));
            return 0;
        }
        if (data.isPremium()) {
            source.sendFailure(Component.literal(colorize(ModConfig.SERVER.msgAdminPremiumNoRecovery.get())));
            return 0;
        }

        String recoveryCode = RecoveryCodeGenerator.generate();
        data.setRecoveryCodeHash(PasswordHasher.hash(RecoveryCodeGenerator.normalize(recoveryCode)));
        authManager.getSessionManager().endSession(data.getUsername(), data.getUuid());
        authManager.getStorage().save(data);
        authManager.audit(
                "RECOVERY_CODE_ISSUED",
                data.getUsername(),
                data.getUuid(),
                "-",
                "issuer=" + source.getTextName());

        source.sendSuccess(() -> Component.literal(colorize(ModConfig.SERVER.msgAdminRecoveryCode.get()
                .replace("%username%", data.getUsername())
                .replace("%code%", recoveryCode))), false);
        source.sendSuccess(() -> Component.literal(colorize(ModConfig.SERVER.msgAdminRecoveryUsage.get())), false);
        return 1;
    }

    private static int handleAdminInfo(CommandSourceStack source, String username) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        PlayerData data = authManager.getStorage().loadByUsername(username)
                .orElseGet(() -> authManager.getStorage().loadByExactUsername(username).orElse(null));
        if (data == null) {
            source.sendFailure(Component.literal(colorize(ModConfig.SERVER.msgAdminAccountNotFound.get()
                    .replace("%username%", username))));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(colorize("&eАккаунт: &f" + data.getUsername())), false);
        source.sendSuccess(() -> Component.literal(colorize("&7Тип: &f" + typeLabel(data))), false);
        source.sendSuccess(() -> Component.literal(colorize("&7UUID: &f" + data.getUuid())), false);
        source.sendSuccess(() -> Component.literal(colorize("&7Зарегистрирован: &f" + formatDate(data.getRegisteredAt()))), false);
        source.sendSuccess(() -> Component.literal(colorize("&7Последний вход: &f" + formatDate(data.getLastLoginAt())
                + "&7, IP: &f" + (data.getLastLoginIp() == null ? "-" : data.getLastLoginIp()))), false);
        source.sendSuccess(() -> Component.literal(colorize("&7Код восстановления: &f"
                + (data.getRecoveryCodeHash() == null ? "не настроен" : "настроен"))), false);
        return 1;
    }

    private static int handleAdminUnregister(CommandSourceStack source, String username) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        PlayerData data = authManager.getStorage().loadByExactUsername(username)
                .orElseGet(() -> authManager.getStorage().loadByUsername(username).orElse(null));
        if (data == null) {
            source.sendFailure(Component.literal(colorize(ModConfig.SERVER.msgAdminAccountNotFound.get()
                    .replace("%username%", username))));
            return 0;
        }

        // Удаление необратимо для игрока (пароль пропадает) — перед удалением создаём бэкап
        boolean backupCreated = authManager.getStorage().createBackup();
        boolean deleted = authManager.getStorage().delete(data.getUuid());
        if (!deleted) {
            source.sendFailure(Component.literal(colorize("&cНе удалось удалить аккаунт " + data.getUsername() + ".")));
            return 0;
        }

        authManager.getSessionManager().endSession(data.getUsername(), data.getUuid());
        authManager.audit(
                "ACCOUNT_DELETED",
                data.getUsername(),
                data.getUuid(),
                "-",
                "deleted_by=" + source.getTextName() + ";backup=" + backupCreated);

        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(data.getUsername());
        if (online != null) {
            online.connection.disconnect(Component.literal(colorize(ModConfig.SERVER.msgAccountDeleted.get())));
        }

        source.sendSuccess(() -> Component.literal(colorize(
                "&aАккаунт &f" + data.getUsername() + "&a удалён." + (backupCreated ? " Бэкап создан." : ""))), true);
        return 1;
    }

    private static int handleAdminList(CommandSourceStack source) {
        List<PlayerData> all = HybridAuthMod.getAuthManager().getStorage().listAll().stream()
                .sorted(Comparator.comparing(PlayerData::getUsername, String.CASE_INSENSITIVE_ORDER))
                .toList();

        source.sendSuccess(() -> Component.literal(colorize(
                "&eАккаунты (&f" + all.size() + "&e):")), false);
        List<PlayerData> shown = all.subList(0, Math.min(all.size(), ADMIN_LIST_LIMIT));
        for (PlayerData data : shown) {
            source.sendSuccess(() -> Component.literal(colorize(
                    "&7- &f" + data.getUsername() + " &7(" + typeLabel(data) + ")")), false);
        }
        if (all.size() > shown.size()) {
            source.sendSuccess(() -> Component.literal(colorize(
                    "&7... и ещё " + (all.size() - shown.size()))), false);
        }
        return 1;
    }

    private static int handleAdminStatus(CommandSourceStack source) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        List<PlayerData> all = authManager.getStorage().listAll();
        long premium = all.stream().filter(PlayerData::isPremium).count();

        source.sendSuccess(() -> Component.literal(colorize(
                "&eHybridAuth: &f" + all.size() + " &eаккаунтов (&f" + premium + "&e лицензионных, &f"
                        + (all.size() - premium) + "&e парольных)")), false);
        source.sendSuccess(() -> Component.literal(colorize(
                "&eАктивных IP-сессий: &f" + authManager.getSessionManager().activeSessionCount())), false);
        source.sendSuccess(() -> Component.literal(colorize(
                "&eОнлайн авторизовано: &f" + authManager.countAuthenticated())), false);
        return 1;
    }

    private static String typeLabel(PlayerData data) {
        return data.isPremium() ? "лицензионный" : "парольный";
    }

    private static String formatDate(Instant instant) {
        if (instant == null) {
            return "-";
        }
        try {
            return DATE_FORMAT.withZone(ZoneId.systemDefault()).format(instant);
        } catch (DateTimeException e) {
            return instant.toString();
        }
    }

    private static boolean validateNewPassword(ServerPlayer player, String password, String confirm) {
        if (!password.equals(confirm)) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgPasswordsDontMatch.get())));
            return false;
        }

        int minLen = ModConfig.SERVER.minPasswordLength.get();
        if (password.length() < minLen) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgPasswordTooShort.get()
                    .replace("%min%", String.valueOf(minLen)))));
            return false;
        }

        int maxLen = ModConfig.SERVER.maxPasswordLength.get();
        if (password.length() > maxLen) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgPasswordTooLong.get()
                    .replace("%max%", String.valueOf(maxLen)))));
            return false;
        }

        if (password.equalsIgnoreCase(player.getScoreboardName())) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgPasswordEqualsUsername.get())));
            return false;
        }
        return true;
    }

    private static int recordFailedAttempt(ServerPlayer player, String event, String details) {
        return recordFailedAttempt(player, event, details, true);
    }

    private static int recordFailedAttempt(ServerPlayer player, String event, String details, boolean sendFeedback) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        int maxAttempts = ModConfig.SERVER.maxLoginAttempts.get();
        var rateLimit = authManager.recordFailedAttempt(player);
        int attempts = rateLimit.attempts();
        authManager.audit(player, event, details + ";attempt=" + attempts + ";max=" + maxAttempts);

        if (!rateLimit.allowed()) {
            player.connection.disconnect(Component.literal(colorize(ModConfig.SERVER.msgTooManyAttempts.get())));
        } else if (sendFeedback && event.equals("LOGIN_FAILURE")) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgWrongPassword.get()
                    .replace("%attempt%", String.valueOf(attempts))
                    .replace("%max%", String.valueOf(maxAttempts)))));
        }
        return 0;
    }

    private static void sendRecoveryCode(ServerPlayer player, String recoveryCode) {
        player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgRecoveryCode.get()
                .replace("%code%", recoveryCode))));
        player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgRecoveryCodeWarning.get())));
    }

    private static PlayerData findPlayerData(AuthManager authManager, ServerPlayer player) {
        return authManager.getStorage().load(player.getUUID())
                .orElseGet(() -> authManager.getStorage().loadByExactUsername(player.getScoreboardName()).orElse(null));
    }

    private static String colorize(String message) {
        return message.replace("&", "\u00A7");
    }
}
