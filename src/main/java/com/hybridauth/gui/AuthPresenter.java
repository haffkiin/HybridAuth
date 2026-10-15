package com.hybridauth.gui;

import com.hybridauth.config.ModConfig;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/**
 * Серверный «экран входа»: у ванильного клиента нет окна ввода пароля, поэтому подсказка
 * показывается крупным заголовком и боссбаром с обратным отсчётом до кика по таймауту.
 */
public final class AuthPresenter {

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    private record Session(ServerBossEvent bar, long startedAtMillis, boolean registered) {
    }

    public boolean enabled() {
        return ModConfig.SERVER.authVisuals.get();
    }

    /** Показывает заголовок и боссбар неавторизованному игроку. */
    public void begin(ServerPlayer player, boolean registered) {
        if (!enabled()) {
            return;
        }

        finish(player);
        ServerBossEvent bar = new ServerBossEvent(
                Component.literal(barText(registered, -1)),
                BossEvent.BossBarColor.YELLOW,
                BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(1.0F);
        bar.addPlayer(player);
        sessions.put(player.getUUID(), new Session(bar, System.currentTimeMillis(), registered));
        showTitle(player, registered ? ModConfig.SERVER.authTitleLogin.get() : ModConfig.SERVER.authTitleRegister.get(),
                registered ? ModConfig.SERVER.authSubtitleLogin.get() : ModConfig.SERVER.authSubtitleRegister.get(), 10, 80, 20);
    }

    /** Раз в секунду обновляет боссбар. */
    public void tick(ServerPlayer player) {
        Session session = sessions.get(player.getUUID());
        if (session == null || player.tickCount % 20 != 0) {
            return;
        }

        int timeout = ModConfig.SERVER.authTimeoutSeconds.get();
        if (timeout <= 0) {
            return;
        }

        long elapsed = (System.currentTimeMillis() - session.startedAtMillis()) / 1000L;
        int left = (int) Math.max(0L, timeout - elapsed);
        session.bar().setProgress(Math.min(1.0F, left / (float) timeout));
        session.bar().setName(Component.literal(barText(session.registered(), left)));
        session.bar().setColor(left <= 10 ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.YELLOW);
    }

    /** Убирает боссбар и сбрасывает заголовок экрана входа. */
    public void finish(ServerPlayer player) {
        Session session = sessions.remove(player.getUUID());
        if (session != null) {
            session.bar().removeAllPlayers();
        }
    }

    public void success(ServerPlayer player, boolean registration) {
        if (!enabled()) {
            return;
        }

        finish(player);
        showTitle(player,
                registration ? ModConfig.SERVER.authTitleRegistered.get() : ModConfig.SERVER.authTitleWelcome.get(),
                player.getScoreboardName(), 5, 40, 20);
    }

    private static String barText(boolean registered, int secondsLeft) {
        String base = registered ? ModConfig.SERVER.authBarLogin.get() : ModConfig.SERVER.authBarRegister.get();
        return colorize(secondsLeft < 0 ? base.replace("%seconds%", "—") : base.replace("%seconds%", Integer.toString(secondsLeft)));
    }

    private static void showTitle(ServerPlayer player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(colorize(subtitle))));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(colorize(title))));
    }

    private static String colorize(String message) {
        return message.replace("&", "§");
    }
}
