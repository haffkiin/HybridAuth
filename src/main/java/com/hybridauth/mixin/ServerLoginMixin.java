package com.hybridauth.mixin;

import com.hybridauth.HybridAuthMod;
import com.hybridauth.config.ModConfig;
import com.hybridauth.auth.OfflineUuid;
import com.hybridauth.storage.PlayerData;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.util.Crypt;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.util.UUID;

/**
 * Mixin для перехвата фазы логина.
 *
 * ВАЖНО: Инжектируемся в HEAD handleHello с cancellable=true, чтобы предотвратить
 * вызов ванильного startClientVerification при online-mode=false.
 * Сами управляем всем потоком авторизации.
 *
 * Реальные значения State в NeoForge 21.1.x:
 * HELLO, KEY, AUTHENTICATING, NEGOTIATING, VERIFYING,
 * WAITING_FOR_DUPE_DISCONNECT, PROTOCOL_SWITCHING, ACCEPTED
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginMixin {

    @Shadow @Final public Connection connection;
    @Shadow private GameProfile authenticatedProfile;
    @Shadow private byte[] challenge;

    @Mutable
    @Shadow private ServerLoginPacketListenerImpl.State state;

    @Mutable
    @Shadow private String requestedUsername;

    @Shadow public abstract void disconnect(Component reason);

    @Shadow
    abstract void startClientVerification(GameProfile profile);

    /**
     * Флаг: является ли ник игрока премиум-ником (зарегистрирован в Mojang).
     * null = проверка ещё не завершена.
     * Используется в onHandleKey чтобы различать пиратов и лицушников с плохой сессией.
     */
    @Unique
    private volatile Boolean hybridAuth_isPremiumUsername = null;

    /**
     * Перехватываем handleHello в самом начале (HEAD) и отменяем ванильную логику.
     * Это нужно потому что при online-mode=false ванила сразу вызывает startClientVerification,
     * что ломает наш flow управления состоянием.
     *
     * Наш flow:
     * 1. Отменяем ванилу
     * 2. Устанавливаем state = KEY (ждём серверный пакет ключа)
     * 3. Асинхронно проверяем ник у Mojang (checkPremium)
     * 4. Если премиум — шлём ClientboundHelloPacket (запрос шифрования)
     * 5. Если пират — сразу вызываем startClientVerification с оффлайн-профилем
     */
    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void onHandleHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (!ModConfig.SERVER.enabled.get() || !ModConfig.SERVER.enablePremiumAutologin.get()) {
            // Мод выключен — пусть ванила обрабатывает
            return;
        }

        // Отменяем ванильный handleHello — сами управляем всем
        ci.cancel();

        String username = packet.name();
        // Устанавливаем requestedUsername сами (ванила это не делает, т.к. мы отменили её)
        this.requestedUsername = username;

        // Устанавливаем KEY-состояние пока ждём ответа от Mojang API
        state = ServerLoginPacketListenerImpl.State.KEY;

        // An exact-case cracked registration intentionally takes precedence over
        // Mojang's case-insensitive profile lookup. This is the configured policy
        // that allows ReMure (premium) and remure (cracked) to coexist safely.
        com.hybridauth.storage.PlayerData exactCracked = HybridAuthMod.getAuthManager().getStorage()
                .loadByExactUsername(username)
                .filter(com.hybridauth.storage.PlayerData::isCracked)
                .orElse(null);
        if (exactCracked != null) {
            hybridAuth_isPremiumUsername = false;
            startClientVerification(new GameProfile(exactCracked.getUuid(), username));
            return;
        }

        HybridAuthMod.getMojangClient().checkPremium(username).thenAccept(result -> {
            HybridAuthMod.getServer().execute(() -> {
                if (result.status() == com.hybridauth.auth.PremiumLookupResult.Status.API_UNAVAILABLE) {
                    hybridAuth_isPremiumUsername = false;
                    if (ModConfig.SERVER.onMojangApiFailure.get() == ModConfig.ApiFailureAction.KICK) {
                        disconnect(Component.literal(ModConfig.SERVER.msgMojangApiError.get().replace("&", "\u00A7")));
                        return;
                    }

                    hybridAuth_allowKnownCrackedOnApiFailure(username, "API_FAILURE_ALLOW_CRACKED");
                    return;
                }

                if (result.status() == com.hybridauth.auth.PremiumLookupResult.Status.PREMIUM) {
                    // Ник зарегистрирован в Mojang — запрашиваем шифрование для проверки сессии
                    hybridAuth_isPremiumUsername = true;

                    if (challenge == null || challenge.length == 0) {
                        challenge = new byte[4];
                        new java.security.SecureRandom().nextBytes(challenge);
                    }

                    connection.send(new ClientboundHelloPacket(
                            "",
                            HybridAuthMod.getServer().getKeyPair().getPublic().getEncoded(),
                            challenge,
                            true
                    ));
                    // Остаёмся в KEY — клиент пришлёт ServerboundKeyPacket → onHandleKey
                } else {
                    // Не премиум ник — пускаем как оффлайн-игрока
                    hybridAuth_isPremiumUsername = false;
                    startClientVerification(new GameProfile(
                            OfflineUuid.forName(username),
                            username
                    ));
                }
            });
        }).exceptionally(e -> {
            HybridAuthMod.getServer().execute(() -> {
                HybridAuthMod.getLogger().error("[HybridAuth] Ошибка проверки Mojang API для {}", username, e);
                hybridAuth_isPremiumUsername = false;

                if (ModConfig.SERVER.onMojangApiFailure.get() == ModConfig.ApiFailureAction.KICK) {
                    disconnect(Component.literal(ModConfig.SERVER.msgMojangApiError.get().replace("&", "§")));
                } else {
                    // ALLOW_CRACKED: при ошибке API — пускаем как оффлайн (с предупреждением в audit log)
                    hybridAuth_allowKnownCrackedOnApiFailure(username, "API_EXCEPTION_ALLOW_CRACKED");
                }
            });
            return null;
        });
    }

    /**
     * During an API outage, only accounts already known as cracked may use the
     * availability fallback. Unknown names must fail closed because the server
     * cannot determine whether they belong to a premium account.
     */
    @Unique
    private void hybridAuth_allowKnownCrackedOnApiFailure(String username, String auditEvent) {
        PlayerData known = HybridAuthMod.getAuthManager().getStorage()
                .loadByExactUsername(username)
                .orElse(null);
        if (known == null || !known.isCracked()) {
            HybridAuthMod.getAuthManager().audit(
                    auditEvent + "_BLOCKED",
                    username,
                    known == null ? null : known.getUuid(),
                    "-",
                    "reason=unknown_or_premium_account");
            disconnect(Component.literal(ModConfig.SERVER.msgMojangApiError.get().replace("&", "\u00A7")));
            return;
        }

        UUID offlineUuid = OfflineUuid.forName(username);
        HybridAuthMod.getAuthManager().audit(
                auditEvent,
                username,
                offlineUuid,
                "-",
                "known_cracked=true");
                    startClientVerification(new GameProfile(offlineUuid, username));
    }

    /**
     * Перехватываем handleKey — этот метод вызывается только если мы послали ClientboundHelloPacket,
     * т.е. только для игроков с премиум-ником (hybridAuth_isPremiumUsername == true).
     *
     * Проверяем сессию у Mojang (hasJoined).
     * Если сессия валидна — лицушник, пускаем с реальным профилем.
     * Если невалидна — пытается войти под чужим ником, кик.
     */
    @Inject(method = "handleKey", at = @At("HEAD"), cancellable = true)
    private void onHandleKey(ServerboundKeyPacket packet, CallbackInfo ci) {
        if (!ModConfig.SERVER.enabled.get() || !ModConfig.SERVER.enablePremiumAutologin.get()) {
            return;
        }

        PrivateKey privateKey = HybridAuthMod.getServer().getKeyPair().getPrivate();

        try {
            if (!packet.isChallengeValid(challenge, privateKey)) {
                throw new IllegalStateException("Protocol error");
            }

            javax.crypto.SecretKey sharedSecret = packet.getSecretKey(privateKey);
            java.security.PublicKey publicKey = HybridAuthMod.getServer().getKeyPair().getPublic();
            String serverIdHash = new BigInteger(Crypt.digestData("", publicKey, sharedSecret)).toString(16);

            String username = requestedUsername;

            // Включаем шифрование (до проверки сессии, как делает ванила)
            javax.crypto.Cipher encCipher = Crypt.getCipher(2, sharedSecret);
            javax.crypto.Cipher decCipher = Crypt.getCipher(1, sharedSecret);
            connection.setEncryptionKey(encCipher, decCipher);

            state = ServerLoginPacketListenerImpl.State.AUTHENTICATING;

            // Асинхронно проверяем сессию у Mojang
            HybridAuthMod.getMojangClient().hasJoined(username, serverIdHash).thenAccept(profileOpt -> {
                HybridAuthMod.getServer().execute(() -> {
                    if (profileOpt.isPresent()) {
                        // Лицензионный игрок — сохраняем как PREMIUM и пускаем
                        GameProfile profile = profileOpt.get();
                        HybridAuthMod.getLogger().info("[HybridAuth] Лицензионный игрок {} прошёл проверку.", username);

                        PlayerData data = HybridAuthMod.getAuthManager().getStorage()
                                .load(profile.getId())
                                .orElse(new PlayerData(profile.getId(), profile.getName(), PlayerData.PlayerType.PREMIUM));
                        data.setType(PlayerData.PlayerType.PREMIUM);
                        data.setUsername(profile.getName());
                        HybridAuthMod.getAuthManager().getStorage().save(data);

                        startClientVerification(profile);
                    } else {
                        // hasJoined провалился — кто-то пытается зайти под премиум-ником без лицензии
                        HybridAuthMod.getLogger().warn("[HybridAuth] Игрок {} попытался войти под премиум-ником без лицензии — кик.", username);
                        disconnect(Component.literal(ModConfig.SERVER.msgPremiumKick.get().replace("&", "§")));
                    }
                });
            }).exceptionally(e -> {
                HybridAuthMod.getServer().execute(() -> {
                    HybridAuthMod.getLogger().error("[HybridAuth] Ошибка hasJoined для {}", username, e);
                    disconnect(Component.literal("Auth server error"));
                });
                return null;
            });

            ci.cancel(); // Отменяем ванильную обработку handleKey

        } catch (Exception e) {
            HybridAuthMod.getLogger().error("[HybridAuth] Ошибка при обработке ключа шифрования", e);
            disconnect(Component.literal("Invalid encryption key"));
            ci.cancel();
        }
    }
}
