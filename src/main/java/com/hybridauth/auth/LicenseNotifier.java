package com.hybridauth.auth;

import com.hybridauth.HybridAuthMod;
import com.hybridauth.config.ModConfig;
import com.hybridauth.storage.PlayerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Уведомления о лицензионности ника. Видимость важна в любом лаунчере,
 * поэтому каждое уведомление дублируется: чат + title/actionbar на экране.
 */
public final class LicenseNotifier {

    private LicenseNotifier() {
    }

    /**
     * Вызывается при входе игрока: премиум-игроку подтверждаем лицензионный вход,
     * владельцу cracked-аккаунта с ником, лицензированным в Mojang, показываем предупреждение.
     */
    public static void onPlayerJoin(ServerPlayer player) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        if (authManager == null || !ModConfig.SERVER.enabled.get()) {
            return;
        }

        if (authManager.isPremiumPlayer(player)) {
            notifyPremiumVerified(player);
            return;
        }

        PlayerData data = authManager.getStorage().load(player.getUUID())
                .orElseGet(() -> authManager.getStorage().loadByExactUsername(player.getScoreboardName()).orElse(null));
        if (data != null && data.isCracked()) {
            warnIfNickLicensed(player);
        }
    }

    /** Подтверждение премиум-входа: чат + титул на экране. */
    public static void notifyPremiumVerified(ServerPlayer player) {
        String message = ModConfig.SERVER.msgPremiumLoginNotice.get();
        player.sendSystemMessage(Component.literal(colorize(message)));
        sendTitle(player, colorize(message));
    }

    /**
     * Проверяет ник через Mojang (результат кэшируется, поэтому частые входы
     * не создают нагрузки на API) и предупреждает, если ник лицензионный.
     */
    private static void warnIfNickLicensed(ServerPlayer player) {
        String username = player.getScoreboardName();
        HybridAuthMod.getMojangClient().checkPremium(username).thenAccept(result -> {
            MinecraftServer server = HybridAuthMod.getServer();
            if (server == null) {
                return;
            }
            server.execute(() -> {
                if (!player.connection.isAcceptingMessages()
                        || result.status() != com.hybridauth.auth.PremiumLookupResult.Status.PREMIUM) {
                    return;
                }
                // Точное совпадение с лицензионным ником — конфликт; иначе это разрешённая пара (remure / ReMure)
                String message = result.canonicalName() != null && result.canonicalName().equals(username)
                        ? ModConfig.SERVER.msgLicensedNameOccupied.get()
                        : ModConfig.SERVER.msgCrackedPremiumNickWarning.get();
                player.sendSystemMessage(Component.literal(colorize(message)));
                player.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal(colorize(message.replace("\n", " ")))));
            });
        }).exceptionally(e -> {
            HybridAuthMod.getLogger()
                    .error("[HybridAuth] Ошибка проверки ника {} для предупреждения о лицензии", username, e);
            return null;
        });
    }

    private static void sendTitle(ServerPlayer player, String text) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(text)));
    }

    private static String colorize(String message) {
        // \n \u0432 \u0442\u0435\u043A\u0441\u0442\u0435 Minecraft \u0440\u0438\u0441\u0443\u0435\u0442 \u043A\u0430\u043A \u0437\u043D\u0430\u0447\u043E\u043A [CR], \u043F\u043E\u044D\u0442\u043E\u043C\u0443 \u043F\u0435\u0440\u0435\u043D\u043E\u0441\u044B \u0437\u0430\u043C\u0435\u043D\u044F\u0435\u043C \u043F\u0440\u043E\u0431\u0435\u043B\u043E\u043C
        return message.replace("&", "\u00A7").replace("\r", "").replace("\n", " ");
    }
}
