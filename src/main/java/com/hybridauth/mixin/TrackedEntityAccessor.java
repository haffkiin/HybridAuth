package com.hybridauth.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

/** Внутренний класс {@code ChunkMap$TrackedEntity}: список наблюдателей и показ/скрытие сущности. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccessor {
    @Accessor("seenBy")
    Set<ServerPlayerConnection> hybridauth$getSeenBy();

    @Invoker("removePlayer")
    void hybridauth$removePlayer(ServerPlayer player);

    @Invoker("updatePlayer")
    void hybridauth$updatePlayer(ServerPlayer player);
}
