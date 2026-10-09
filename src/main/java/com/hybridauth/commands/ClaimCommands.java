package com.hybridauth.commands;

import com.hybridauth.HybridAuthMod;
import com.hybridauth.auth.AuthManager;
import com.hybridauth.auth.PremiumLookupResult;
import com.hybridauth.config.ModConfig;
import com.hybridauth.storage.PlayerData;
import com.hybridauth.transfer.AccountTransferService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

/**
 * {@code /claim} показывает, что будет перенесено, а {@code /claim confirm} подаёт заявку и отключает игрока.
 * Дальше перенос выполняется при входе с лицензионного клиента (см. {@code ClaimService}).
 */
public final class ClaimCommands {

    private ClaimCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("claim")
                .executes(context -> handle(context.getSource().getPlayerOrException(), false))
                .then(Commands.literal("confirm")
                        .executes(context -> handle(context.getSource().getPlayerOrException(), true))));
    }

    /** Ветка {@code /hybridauth claim create|cancel|list} для поддержки. */
    public static LiteralArgumentBuilder<CommandSourceStack> buildAdmin() {
        return Commands.literal("claim")
                .then(Commands.literal("create")
                        .then(Commands.argument("nick", StringArgumentType.word())
                                .executes(context -> adminCreate(context.getSource(),
                                        StringArgumentType.getString(context, "nick")))))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("nick", StringArgumentType.word())
                                .executes(context -> {
                                    HybridAuthMod.getClaimRegistry().remove(StringArgumentType.getString(context, "nick"));
                                    context.getSource().sendSuccess(() -> Component.literal("Заявка отменена."), true);
                                    return 1;
                                })))
                .then(Commands.literal("list").executes(context -> {
                    var claims = HybridAuthMod.getClaimRegistry().snapshot(System.currentTimeMillis());
                    context.getSource().sendSuccess(() -> Component.literal(
                            claims.isEmpty() ? "Заявок на перенос нет."
                                    : "Заявки на перенос: " + claims.stream()
                                    .map(claim -> claim.nick() + " (" + Math.max(1,
                                            (claim.expiresAtMillis() - System.currentTimeMillis()) / 60_000) + " мин.)")
                                    .reduce((a, b) -> a + ", " + b).orElse("")), false);
                    return 1;
                }));
    }

    /** Поддержка оформляет заявку вместо игрока: после неё владелец заходит с лицензии, и данные переезжают. */
    private static int adminCreate(CommandSourceStack source, String nick) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        PlayerData data = authManager.getStorage().loadByExactUsername(nick).filter(PlayerData::isCracked).orElse(null);
        if (data == null) {
            source.sendFailure(Component.literal("Пиратский аккаунт с точным ником " + nick + " не найден."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        HybridAuthMod.getMojangClient().checkPremium(data.getUsername()).whenComplete((result, failure) ->
                server.execute(() -> {
                    PremiumLookupResult lookup = failure == null ? result : PremiumLookupResult.apiUnavailable();
                    if (lookup.status() != PremiumLookupResult.Status.PREMIUM || lookup.canonicalName() == null
                            || !data.getUsername().equalsIgnoreCase(lookup.canonicalName())) {
                        source.sendFailure(Component.literal("Ник " + data.getUsername()
                                + " не лицензионный в Mojang (или Mojang не ответил): заявка не создана."));
                        return;
                    }
                    AccountTransferService.Report check = AccountTransferService.runClaim(
                            server, FMLPaths.CONFIGDIR.get(), data,
                            lookup.premiumUuid(), lookup.canonicalName(), false, false, source.getTextName());
                    if (!check.success()) {
                        source.sendFailure(Component.literal("Заявка не создана: " + String.join(" ", check.lines())));
                        return;
                    }
                    int minutes = ModConfig.SERVER.claimMinutes.get();
                    HybridAuthMod.getClaimRegistry().create(
                            data.getUsername(), data.getUuid(), System.currentTimeMillis(), minutes * 60_000L);
                    authManager.audit("CLAIM_CREATED", data.getUsername(), data.getUuid(), "-",
                            "premium_uuid=" + lookup.premiumUuid() + ";minutes=" + minutes + ";by=" + source.getTextName());
                    source.sendSuccess(() -> Component.literal("Заявка создана на " + minutes + " мин.: владелец заходит с лицензии под ником "
                            + data.getUsername() + ", данные перенесутся автоматически."), true);
                }));
        return 1;
    }

    private static int handle(ServerPlayer player, boolean confirm) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        if (!ModConfig.SERVER.claimEnabled.get()) {
            send(player, ModConfig.SERVER.msgClaimDisabled.get());
            return 0;
        }
        if (!authManager.isAuthenticated(player.getUUID())) {
            send(player, ModConfig.SERVER.msgLoginRequired.get());
            return 0;
        }
        PlayerData data = authManager.getStorage().load(player.getUUID())
                .orElseGet(() -> authManager.getStorage().loadByExactUsername(player.getScoreboardName()).orElse(null));
        if (data == null || !data.isCracked()) {
            send(player, ModConfig.SERVER.msgClaimOnlyCracked.get());
            return 0;
        }

        MinecraftServer server = player.server;
        HybridAuthMod.getMojangClient().checkPremium(data.getUsername()).whenComplete((result, failure) ->
                server.execute(() -> finish(player, data, confirm,
                        failure == null ? result : PremiumLookupResult.apiUnavailable())));
        return 1;
    }

    /** Выполняется на потоке сервера, когда известен ответ Mojang. */
    private static void finish(ServerPlayer player, PlayerData data, boolean confirm, PremiumLookupResult lookup) {
        if (!player.connection.isAcceptingMessages()) {
            return;
        }
        String nick = data.getUsername();
        if (lookup.status() == PremiumLookupResult.Status.API_UNAVAILABLE) {
            send(player, ModConfig.SERVER.msgMojangApiError.get());
            return;
        }
        if (lookup.status() != PremiumLookupResult.Status.PREMIUM
                || lookup.canonicalName() == null || !nick.equalsIgnoreCase(lookup.canonicalName())) {
            send(player, ModConfig.SERVER.msgClaimNotLicensed.get().replace("%nick%", nick));
            return;
        }

        // Проверка заранее, чтобы не отключать игрока зря: пират ещё на сервере, это допустимо
        AccountTransferService.Report check = AccountTransferService.runClaim(
                player.server, FMLPaths.CONFIGDIR.get(), data,
                lookup.premiumUuid(), lookup.canonicalName(), false, true, player.getScoreboardName());
        if (!check.success()) {
            send(player, ModConfig.SERVER.msgClaimFailed.get().replace("%reason%", String.join(" ", check.lines())));
            return;
        }

        int minutes = ModConfig.SERVER.claimMinutes.get();
        if (!confirm) {
            send(player, ModConfig.SERVER.msgClaimExplain.get()
                    .replace("%nick%", nick)
                    .replace("%minutes%", String.valueOf(minutes)));
            return;
        }

        HybridAuthMod.getClaimRegistry().create(nick, data.getUuid(), System.currentTimeMillis(), minutes * 60_000L);
        HybridAuthMod.getAuthManager().audit(player, "CLAIM_CREATED",
                "premium_uuid=" + lookup.premiumUuid() + ";minutes=" + minutes);
        player.connection.disconnect(Component.literal(colorize(ModConfig.SERVER.msgClaimCreated.get()
                .replace("%nick%", nick)
                .replace("%minutes%", String.valueOf(minutes)))));
    }

    private static void send(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(colorize(message)));
    }

    private static String colorize(String message) {
        return message.replace("&", "§").replace("\r", "").replace("\n", " ");
    }
}
