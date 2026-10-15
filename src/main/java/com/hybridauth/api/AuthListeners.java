package com.hybridauth.api;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Реестр слушателей успешной авторизации. Соседние моды (например SMPIdentity) подписываются,
 * чтобы показать игроку приветствие, почту и уведомления сразу после входа, а не до него.
 *
 * <p>Слушатель вызывается на серверном потоке; {@code method} — одно из PREMIUM, SESSION,
 * REGISTER, PASSWORD, RECOVERY. Исключения слушателей перехватываются и не ломают вход.</p>
 */
public final class AuthListeners {

    private static final List<BiConsumer<ServerPlayer, String>> LISTENERS = new CopyOnWriteArrayList<>();

    private AuthListeners() {
    }

    public static void register(BiConsumer<ServerPlayer, String> listener) {
        LISTENERS.add(Objects.requireNonNull(listener, "listener"));
    }

    public static void fireAuthenticated(ServerPlayer player, String method) {
        for (BiConsumer<ServerPlayer, String> listener : LISTENERS) {
            try {
                listener.accept(player, method);
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger("HybridAuth").warn("[HybridAuth] Слушатель авторизации упал: {}", failure.toString());
            }
        }
    }
}
