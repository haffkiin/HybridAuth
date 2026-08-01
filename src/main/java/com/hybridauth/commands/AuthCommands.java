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

public class AuthCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerRegisterCommand(dispatcher, "register");
        registerRegisterCommand(dispatcher, "reg");
        registerLoginCommand(dispatcher, "login");
        registerLoginCommand(dispatcher, "l");
        registerRecoveryCommands(dispatcher);

        dispatcher.register(Commands.literal("hybridauth")
                .requires(source -> source.hasPermission(3))
                .then(Commands.literal("reload").executes(context -> {
                    HybridAuthMod.getAuthManager().reloadConfig();
                    context.getSource().sendSuccess(
                            () -> Component.literal(colorize("&aHybridAuth config reloaded.")), true);
                    return 1;
                }))
                .then(Commands.literal("backup").executes(context -> {
                    boolean created = HybridAuthMod.getAuthManager().getStorage().createBackup();
                    if (created) {
                        context.getSource().sendSuccess(
                                () -> Component.literal(colorize("&aHybridAuth backup created.")), true);
                        return 1;
                    }
                    context.getSource().sendFailure(
                            Component.literal(colorize("&cCould not create HybridAuth backup. Check the server log.")));
                    return 0;
                }))
                .then(Commands.literal("recovery")
                        .then(Commands.argument("username", StringArgumentType.word())
                                .executes(context -> handleAdminRecoveryCode(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "username"))))));
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

        if (!authManager.loginAttemptStatus(player).allowed()) {
            player.connection.disconnect(Component.literal(colorize(ModConfig.SERVER.msgTooManyAttempts.get())));
            return 0;
        }

        if (authManager.isAuthenticated(player.getUUID())) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgAlreadyLoggedIn.get())));
            return 0;
        }

        PlayerData data = findPlayerData(authManager, player);
        if (data == null) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgNotRegistered.get())));
            authManager.audit(player, "LOGIN_FAILURE", "reason=not_registered");
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
            authManager.clearFailedAttempts(player);
            authManager.authenticate(player, false, "PASSWORD");
            return 1;
        }

        return recordFailedAttempt(player, "LOGIN_FAILURE", "reason=wrong_password");
    }

    private static int handleNewRecoveryCode(ServerPlayer player) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        if (!authManager.isAuthenticated(player.getUUID())) {
            player.sendSystemMessage(Component.literal(colorize("&cYou must log in first.")));
            return 0;
        }

        PlayerData data = findPlayerData(authManager, player);
        if (data == null || data.isPremium()) {
            player.sendSystemMessage(Component.literal(colorize("&cRecovery codes are only used for password accounts.")));
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
        if (!authManager.loginAttemptStatus(player).allowed()) {
            player.connection.disconnect(Component.literal(colorize(ModConfig.SERVER.msgTooManyAttempts.get())));
            return 0;
        }
        if (authManager.isAuthenticated(player.getUUID())) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgAlreadyLoggedIn.get())));
            return 0;
        }

        PlayerData data = findPlayerData(authManager, player);
        if (data == null || data.isPremium() || data.getRecoveryCodeHash() == null) {
            player.sendSystemMessage(Component.literal(colorize(
                    "&cNo recovery code is configured. Ask an administrator to run /hybridauth recovery "
                            + player.getScoreboardName())));
            authManager.audit(player, "RECOVERY_FAILURE", "reason=not_configured");
            return 0;
        }

        String normalizedCode = RecoveryCodeGenerator.normalize(code);
        if (normalizedCode.length() > 64) {
            return recordFailedAttempt(player, "RECOVERY_FAILURE", "reason=code_too_long");
        }
        if (!PasswordHasher.verify(normalizedCode, data.getRecoveryCodeHash())) {
            player.sendSystemMessage(Component.literal(colorize("&cInvalid recovery code.")));
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
        player.sendSystemMessage(Component.literal(colorize("&aPassword changed. The old recovery code is now invalid.")));
        sendRecoveryCode(player, nextRecoveryCode);
        return 1;
    }

    private static int handleAdminRecoveryCode(CommandSourceStack source, String username) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        PlayerData data = authManager.getStorage().loadByExactUsername(username).orElse(null);
        if (data == null) {
            source.sendFailure(Component.literal(colorize("&cAccount not found: " + username)));
            return 0;
        }
        if (data.isPremium()) {
            source.sendFailure(Component.literal(colorize("&cPremium accounts do not use recovery codes.")));
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

        source.sendSuccess(() -> Component.literal(colorize(
                "&eOne-time recovery code for &f" + data.getUsername() + "&e: &f" + recoveryCode)), false);
        source.sendSuccess(() -> Component.literal(colorize(
                "&7The player must use /recover <code> <new password> <repeat password>.")), false);
        return 1;
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
            player.sendSystemMessage(Component.literal(colorize(
                    "&cPassword is too long! Maximum %max% characters."
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
        AuthManager authManager = HybridAuthMod.getAuthManager();
        int maxAttempts = ModConfig.SERVER.maxLoginAttempts.get();
        var rateLimit = authManager.recordFailedAttempt(player);
        int attempts = rateLimit.attempts();
        authManager.audit(player, event, details + ";attempt=" + attempts + ";max=" + maxAttempts);

        if (!rateLimit.allowed()) {
            player.connection.disconnect(Component.literal(colorize(ModConfig.SERVER.msgTooManyAttempts.get())));
        } else if (event.equals("LOGIN_FAILURE")) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgWrongPassword.get()
                    .replace("%attempt%", String.valueOf(attempts))
                    .replace("%max%", String.valueOf(maxAttempts)))));
        }
        return 0;
    }

    private static void sendRecoveryCode(ServerPlayer player, String recoveryCode) {
        player.sendSystemMessage(Component.literal(colorize(
                "&eRecovery code: &f" + recoveryCode)));
        player.sendSystemMessage(Component.literal(colorize(
                "&cStore this code safely. It is shown only once and replaces any previous code.")));
    }

    private static PlayerData findPlayerData(AuthManager authManager, ServerPlayer player) {
        return authManager.getStorage().load(player.getUUID())
                .orElseGet(() -> authManager.getStorage().loadByExactUsername(player.getScoreboardName()).orElse(null));
    }

    private static String colorize(String message) {
        return message.replace("&", "\u00A7");
    }
}
