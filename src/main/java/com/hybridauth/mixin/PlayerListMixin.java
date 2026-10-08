package com.hybridauth.mixin;

import com.hybridauth.HybridAuthMod;
import com.hybridauth.skin.SkinService;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Подставляет выбранный скин в профиль игрока до того, как сервер разошлёт данные о нём
 * (список игроков, пакеты появления сущности). Так скин виден сразу при входе, без перезахода.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void hybridauth$applySkin(Connection connection, ServerPlayer player,
                                      CommonListenerCookie cookie, CallbackInfo ci) {
        SkinService skins = HybridAuthMod.getSkinService();
        if (skins != null) {
            skins.onPlayerJoining(player);
        }
    }
}
