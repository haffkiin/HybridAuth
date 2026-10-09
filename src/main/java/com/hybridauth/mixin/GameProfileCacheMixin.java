package com.hybridauth.mixin;

import com.hybridauth.HybridAuthMod;
import com.hybridauth.auth.AuthManager;
import com.hybridauth.auth.NameLookupRules;
import com.hybridauth.storage.PlayerData;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.players.GameProfileCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

/**
 * Исправляет поиск профиля по нику для команд {@code /op}, {@code /whitelist}, {@code /ban} и т. п.
 *
 * <ol>
 *   <li>Если ник есть в базе HybridAuth, берём оттуда UUID и ник с настоящим регистром.</li>
 *   <li>Иначе (игрок ещё не заходил) офлайн-UUID считается по нику в том регистре, в котором его ввёл
 *       администратор, а не по нику в нижнем регистре, как в ванильном коде.</li>
 * </ol>
 */
@Mixin(GameProfileCache.class)
public abstract class GameProfileCacheMixin {

    /** Ник в том виде, в котором его передали в {@code get(String)}; вызов идёт в одном потоке. */
    @Unique
    private static final ThreadLocal<String> hybridauth$typedName = new ThreadLocal<>();

    @Inject(method = "get(Ljava/lang/String;)Ljava/util/Optional;", at = @At("HEAD"), cancellable = true)
    private void hybridauth$resolveFromDatabase(String name, CallbackInfoReturnable<Optional<GameProfile>> cir) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        if (authManager != null && name != null) {
            List<NameLookupRules.Candidate> candidates = authManager.getStorage().listAll().stream()
                    .filter(data -> data.getUsername() != null && data.getUsername().equalsIgnoreCase(name))
                    .map((PlayerData data) -> new NameLookupRules.Candidate(data.getUsername(), data.getUuid()))
                    .toList();
            Optional<NameLookupRules.Candidate> found = NameLookupRules.pick(name, candidates);
            if (found.isPresent()) {
                // Ответ из базы: ванильный поиск не выполняется, запоминать ник незачем
                cir.setReturnValue(Optional.of(new GameProfile(found.get().uuid(), found.get().username())));
                return;
            }
        }
        // Дальше выполняется ванильный поиск; @ModifyArg возьмёт ник отсюда, а RETURN очистит значение
        hybridauth$typedName.set(name);
    }

    @Inject(method = "get(Ljava/lang/String;)Ljava/util/Optional;", at = @At("RETURN"))
    private void hybridauth$forget(String name, CallbackInfoReturnable<Optional<GameProfile>> cir) {
        hybridauth$typedName.remove();
    }

    /** Не даёт передать в поиск ник, приведённый к нижнему регистру: офлайн-UUID зависит от регистра. */
    @ModifyArg(
            method = "get(Ljava/lang/String;)Ljava/util/Optional;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/players/GameProfileCache;lookupGameProfile("
                            + "Lcom/mojang/authlib/GameProfileRepository;Ljava/lang/String;)Ljava/util/Optional;"),
            index = 1)
    private String hybridauth$keepTypedCase(String lowered) {
        return NameLookupRules.keepTypedCase(lowered, hybridauth$typedName.get());
    }
}
