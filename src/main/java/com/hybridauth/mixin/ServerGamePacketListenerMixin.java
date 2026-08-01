package com.hybridauth.mixin;

import com.hybridauth.HybridAuthMod;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {

    @Shadow @Final public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void hybridAuth$releaseSpawnProtectionOnMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        HybridAuthMod.getPremiumSpawnProtection().releaseOnClientMovement(player, packet);
    }
}
