package com.hybridauth.skin;

import com.hybridauth.mixin.ChunkMapAccessor;
import com.hybridauth.mixin.TrackedEntityAccessor;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.server.players.PlayerList;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Показывает новый скин игроку, который уже на сервере: скрывает и снова показывает его
 * у наблюдателей, обновляет запись в списке игроков и пересылает самому игроку состояние мира.
 *
 * Последовательность пакетов адаптирована из SkinRestorer
 * (https://github.com/Suiranoil/SkinRestorer, MIT License, © Lionarius); см. THIRD_PARTY_NOTICES.md.
 */
final class SkinRefresher {

    private SkinRefresher() {
    }

    static void refresh(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        PlayerList playerList = level.getServer().getPlayerList();
        ChunkMap chunkMap = level.getChunkSource().chunkMap;

        // Запись в списке игроков с новым профилем
        playerList.broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
        playerList.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(
                Collections.singleton(player)));

        // Сущность игрока: убрать и показать заново каждому наблюдателю
        TrackedEntityAccessor tracked = ((ChunkMapAccessor) chunkMap).hybridauth$getEntityMap().get(player.getId());
        if (tracked != null) {
            Set<ServerPlayerConnection> observers = Set.copyOf(tracked.hybridauth$getSeenBy());
            for (ServerPlayerConnection connection : observers) {
                ServerPlayer observer = connection.getPlayer();
                tracked.hybridauth$removePlayer(observer);

                TrackedEntityAccessor observerTracked =
                        ((ChunkMapAccessor) chunkMap).hybridauth$getEntityMap().get(observer.getId());
                if (observerTracked != null) {
                    observerTracked.hybridauth$removePlayer(player);
                    observerTracked.hybridauth$updatePlayer(player);
                }
                tracked.hybridauth$updatePlayer(observer);
            }
        }

        // Самому игроку: повторный «респавн» без потери данных, чтобы клиент перечитал свой профиль
        if (!player.isDeadOrDying()) {
            player.connection.send(new ClientboundBundlePacket(List.of(
                    new ClientboundRespawnPacket(player.createCommonSpawnInfo(level),
                            ClientboundRespawnPacket.KEEP_ALL_DATA),
                    new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0))));
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
            var vehicle = player.getVehicle();
            if (vehicle != null) {
                player.connection.send(new ClientboundSetPassengersPacket(vehicle));
            }
            if (!player.getPassengers().isEmpty()) {
                player.connection.send(new ClientboundSetPassengersPacket(player));
            }

            player.onUpdateAbilities();
            player.giveExperiencePoints(0);
            playerList.sendPlayerPermissionLevel(player);
            playerList.sendLevelInfo(player, level);
            playerList.sendAllPlayerInfo(player);
            for (var effect : player.getActiveEffects()) {
                player.connection.send(new ClientboundUpdateMobEffectPacket(player.getId(), effect, false));
            }
        }
    }
}
