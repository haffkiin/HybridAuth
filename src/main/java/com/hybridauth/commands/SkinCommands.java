package com.hybridauth.commands;

import com.hybridauth.HybridAuthMod;
import com.hybridauth.auth.AuthManager;
import com.hybridauth.config.ModConfig;
import com.hybridauth.skin.SkinEntry;
import com.hybridauth.skin.SkinException;
import com.hybridauth.skin.SkinRequestRules;
import com.hybridauth.skin.SkinService;
import com.hybridauth.skin.SkinVariant;
import com.hybridauth.storage.PlayerData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Скины. Игрок: {@code /skin nick <ник>}, {@code /skin url <ссылка> [classic|slim]}, {@code /skin reset},
 * {@code /skin info}. Администратор: {@code /hybridauth skin <аккаунт> reset|info|nick|url ...}.
 */
public final class SkinCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger("HybridAuth");

    private SkinCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("skin")
                .executes(context -> {
                    reply(context.getSource(), ModConfig.SERVER.msgSkinUsage.get());
                    return 1;
                })
                .then(Commands.literal("nick")
                        .then(Commands.argument("nick", StringArgumentType.word())
                                .executes(context -> selfNick(context.getSource(),
                                        StringArgumentType.getString(context, "nick")))))
                .then(Commands.literal("url")
                        .then(Commands.argument("link", StringArgumentType.greedyString())
                                .executes(context -> selfUrl(context.getSource(),
                                        StringArgumentType.getString(context, "link")))))
                .then(Commands.literal("reset").executes(context -> selfReset(context.getSource())))
                .then(Commands.literal("info").executes(context -> selfInfo(context.getSource())))
                // Короткая форма: /skin <ник>
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> selfNick(context.getSource(),
                                StringArgumentType.getString(context, "name")))));
    }

    /** Ветка {@code /hybridauth skin <аккаунт> ...} для поддержки. */
    public static LiteralArgumentBuilder<CommandSourceStack> buildAdmin() {
        return Commands.literal("skin")
                .then(Commands.argument("account", StringArgumentType.word())
                        .then(Commands.literal("reset").executes(context -> adminReset(context.getSource(),
                                StringArgumentType.getString(context, "account"))))
                        .then(Commands.literal("info").executes(context -> adminInfo(context.getSource(),
                                StringArgumentType.getString(context, "account"))))
                        .then(Commands.literal("nick")
                                .then(Commands.argument("nick", StringArgumentType.word())
                                        .executes(context -> adminNick(context.getSource(),
                                                StringArgumentType.getString(context, "account"),
                                                StringArgumentType.getString(context, "nick")))))
                        .then(Commands.literal("url")
                                .then(Commands.argument("link", StringArgumentType.greedyString())
                                        .executes(context -> adminUrl(context.getSource(),
                                                StringArgumentType.getString(context, "account"),
                                                StringArgumentType.getString(context, "link"))))));
    }

    // ─── игрок ───────────────────────────────────────────────────────────────

    private static int selfNick(CommandSourceStack source, String nick) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = authenticatedPlayer(source);
        return player == null ? 0 : requestNick(source, player.getUUID(), nick, false);
    }

    private static int selfUrl(CommandSourceStack source, String text) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = authenticatedPlayer(source);
        return player == null ? 0 : requestUrl(source, player.getUUID(), text, false);
    }

    private static int selfReset(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = authenticatedPlayer(source);
        return player == null ? 0 : requestReset(source, player.getUUID(), false);
    }

    private static int selfInfo(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = authenticatedPlayer(source);
        return player == null ? 0 : showInfo(source, player.getUUID(), false);
    }

    /** Игрок, прошедший авторизацию; иначе отвечаем и возвращаем null. */
    private static ServerPlayer authenticatedPlayer(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        AuthManager authManager = HybridAuthMod.getAuthManager();
        if (authManager == null || !authManager.isAuthenticated(player.getUUID())) {
            reply(source, ModConfig.SERVER.msgLoginRequired.get());
            return null;
        }
        return player;
    }

    // ─── администратор ───────────────────────────────────────────────────────

    private static int adminNick(CommandSourceStack source, String account, String nick) {
        Optional<PlayerData> data = findAccount(source, account);
        return data.isEmpty() ? 0 : requestNick(source, data.get().getUuid(), nick, true);
    }

    private static int adminUrl(CommandSourceStack source, String account, String text) {
        Optional<PlayerData> data = findAccount(source, account);
        return data.isEmpty() ? 0 : requestUrl(source, data.get().getUuid(), text, true);
    }

    private static int adminReset(CommandSourceStack source, String account) {
        Optional<PlayerData> data = findAccount(source, account);
        return data.isEmpty() ? 0 : requestReset(source, data.get().getUuid(), true);
    }

    private static int adminInfo(CommandSourceStack source, String account) {
        Optional<PlayerData> data = findAccount(source, account);
        return data.isEmpty() ? 0 : showInfo(source, data.get().getUuid(), true);
    }

    private static Optional<PlayerData> findAccount(CommandSourceStack source, String account) {
        Optional<PlayerData> data = HybridAuthMod.getAuthManager().getStorage().loadByExactUsername(account);
        if (data.isEmpty()) {
            reply(source, ModConfig.SERVER.msgAdminAccountNotFound.get().replace("%username%", account));
        }
        return data;
    }

    // ─── общая логика ────────────────────────────────────────────────────────

    private static int requestNick(CommandSourceStack source, UUID target, String nick, boolean admin) {
        if (!ModConfig.SERVER.skinsEnabled.get()) {
            reply(source, ModConfig.SERVER.msgSkinDisabled.get());
            return 0;
        }
        if (!ModConfig.SERVER.skinsNickEnabled.get()) {
            reply(source, ModConfig.SERVER.msgSkinDisabled.get());
            return 0;
        }
        SkinService skins = HybridAuthMod.getSkinService();
        return runFetch(source, target, admin, ModConfig.SERVER.skinsCooldownSeconds.get(), false,
                nick, () -> skins.fetchByNick(nick));
    }

    private static int requestUrl(CommandSourceStack source, UUID target, String text, boolean admin) {
        if (!ModConfig.SERVER.skinsEnabled.get()) {
            reply(source, ModConfig.SERVER.msgSkinDisabled.get());
            return 0;
        }
        SkinService skins = HybridAuthMod.getSkinService();
        if (!ModConfig.SERVER.skinsUrlEnabled.get() || !skins.mineSkin().isConfigured()) {
            reply(source, ModConfig.SERVER.msgSkinUrlUnavailable.get());
            return 0;
        }

        SkinRequestRules.UrlArgs args = SkinRequestRules.parseUrlArgs(text);
        Optional<SkinRequestRules.UrlProblem> problem =
                SkinRequestRules.checkUrl(args.url(), ModConfig.SERVER.skinsUrlAllowedDomains.get());
        if (problem.isPresent()) {
            reply(source, problem.get() == SkinRequestRules.UrlProblem.DOMAIN_NOT_ALLOWED
                    ? ModConfig.SERVER.msgSkinUrlDomain.get()
                    : ModConfig.SERVER.msgSkinUrlInvalid.get());
            return 0;
        }
        return runFetch(source, target, admin, ModConfig.SERVER.skinsUrlCooldownSeconds.get(), true,
                args.url(), () -> skins.fetchByUrl(args.url(), args.variant()));
    }

    /** Резервирует запрос, получает скин в фоне и применяет его на потоке сервера. */
    private static int runFetch(CommandSourceStack source, UUID target, boolean admin, int cooldownSeconds,
                                boolean slow, String argument, Supplier<CompletableFuture<SkinEntry>> fetch) {
        SkinService skins = HybridAuthMod.getSkinService();
        Optional<Integer> wait = skins.tryBegin(target, admin ? 0 : cooldownSeconds);
        if (wait.isPresent()) {
            reply(source, wait.get() < 0
                    ? ModConfig.SERVER.msgSkinBusy.get()
                    : ModConfig.SERVER.msgSkinCooldown.get().replace("%seconds%", String.valueOf(wait.get())));
            return 0;
        }
        if (slow) {
            reply(source, ModConfig.SERVER.msgSkinFetching.get());
        }

        MinecraftServer server = source.getServer();
        String actor = source.getTextName();
        CompletableFuture<SkinEntry> future;
        try {
            future = fetch.get();
        } catch (RuntimeException e) {
            skins.end(target);
            throw e;
        }
        future.whenComplete((entry, failure) -> server.execute(() -> {
            skins.end(target);
            if (failure != null) {
                reply(source, failureMessage(failure, argument));
                return;
            }
            boolean saved = skins.assign(server, target, entry);
            audit(target, "SKIN_SET", "source=" + entry.source().name().toLowerCase(Locale.ROOT)
                    + ";argument=" + abbreviate(entry.argument()) + ";by=" + actor);
            reply(source, ModConfig.SERVER.msgSkinSet.get().replace("%source%", describe(entry)));
            if (!saved) {
                reply(source, ModConfig.SERVER.msgSkinSaveFailed.get());
            }
        }));
        return 1;
    }

    private static int requestReset(CommandSourceStack source, UUID target, boolean admin) {
        SkinService skins = HybridAuthMod.getSkinService();
        Optional<Integer> wait = skins.tryBegin(target, admin ? 0 : ModConfig.SERVER.skinsCooldownSeconds.get());
        if (wait.isPresent()) {
            reply(source, wait.get() < 0
                    ? ModConfig.SERVER.msgSkinBusy.get()
                    : ModConfig.SERVER.msgSkinCooldown.get().replace("%seconds%", String.valueOf(wait.get())));
            return 0;
        }
        try {
            boolean existed = skins.clear(source.getServer(), target);
            if (existed) {
                audit(target, "SKIN_RESET", "by=" + source.getTextName());
            }
            reply(source, existed ? ModConfig.SERVER.msgSkinReset.get() : ModConfig.SERVER.msgSkinNothingToReset.get());
        } finally {
            skins.end(target);
        }
        return 1;
    }

    private static int showInfo(CommandSourceStack source, UUID target, boolean admin) {
        Optional<SkinEntry> entry = HybridAuthMod.getSkinService().storage().get(target);
        if (entry.isEmpty()) {
            reply(source, ModConfig.SERVER.msgSkinNone.get());
            return 1;
        }
        String line = ModConfig.SERVER.msgSkinInfo.get()
                .replace("%source%", describe(entry.get()))
                .replace("%variant%", entry.get().variant().name().toLowerCase(Locale.ROOT));
        reply(source, line);
        return 1;
    }

    private static String describe(SkinEntry entry) {
        return entry.source() == SkinEntry.Source.NICK
                ? "скин игрока " + entry.argument()
                : "своя картинка";
    }

    private static String failureMessage(Throwable failure, String argument) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        if (cause instanceof SkinException skinException) {
            return switch (skinException.reason()) {
                case NOT_FOUND -> ModConfig.SERVER.msgSkinNickNotFound.get().replace("%nick%", argument);
                case RATE_LIMITED -> ModConfig.SERVER.msgSkinRateLimited.get();
                case UNAVAILABLE -> ModConfig.SERVER.msgSkinUnavailable.get();
                case REJECTED -> ModConfig.SERVER.msgSkinRejected.get().replace("%reason%", skinException.getMessage());
                case NOT_CONFIGURED -> ModConfig.SERVER.msgSkinUrlUnavailable.get();
            };
        }
        LOGGER.error("[HybridAuth] Непредвиденная ошибка при получении скина", cause);
        return ModConfig.SERVER.msgSkinUnavailable.get();
    }

    private static void audit(UUID target, String event, String details) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        String name = authManager.getStorage().load(target).map(PlayerData::getUsername).orElse("-");
        authManager.audit(event, name, target, "-", details);
    }

    private static String abbreviate(String text) {
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }

    private static void reply(CommandSourceStack source, String message) {
        source.sendSystemMessage(Component.literal(colorize(message)));
    }

    private static String colorize(String message) {
        return message.replace("&", "§").replace("\r", "").replace("\n", " ");
    }
}
