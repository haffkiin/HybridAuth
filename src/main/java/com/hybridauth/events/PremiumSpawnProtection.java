package com.hybridauth.events;

import com.hybridauth.HybridAuthMod;
import com.hybridauth.config.ModConfig;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Protects a premium player from damage until the server receives their first
 * actual position change after joining.
 */
public final class PremiumSpawnProtection {

    private static final double MOVEMENT_EPSILON_SQ = 1.0E-12D;

    private final Map<UUID, Protection> protections = new ConcurrentHashMap<>();

    public void arm(ServerPlayer player) {
        long durationNanos = TimeUnit.SECONDS.toNanos(ModConfig.SERVER.premiumSpawnProtectionSeconds.get());
        protections.put(player.getUUID(), new Protection(player.position(), System.nanoTime() + durationNanos));
    }

    public boolean isProtected(ServerPlayer player) {
        if (!ModConfig.SERVER.enabled.get() || !ModConfig.SERVER.premiumSpawnProtection.get()) {
            return false;
        }
        expireIfNeeded(player);
        return protections.containsKey(player.getUUID());
    }

    public void clear(ServerPlayer player) {
        protections.remove(player.getUUID());
    }

    public void expireIfNeeded(ServerPlayer player) {
        Protection protection = protections.get(player.getUUID());
        if (protection == null || System.nanoTime() - protection.expiresAtNanos() < 0) {
            return;
        }
        if (protections.remove(player.getUUID(), protection)) {
            HybridAuthMod.getAuthManager().audit(player, "PREMIUM_SPAWN_PROTECTION_RELEASED", "reason=timeout");
        }
    }

    /**
     * Called from the server movement packet handler before vanilla applies the packet.
     * A look-only packet does not remove protection.
     */
    public void releaseOnClientMovement(ServerPlayer player, ServerboundMovePlayerPacket packet) {
        expireIfNeeded(player);
        Protection protection = protections.get(player.getUUID());
        if (protection == null || !packet.hasPosition()) {
            return;
        }

        Vec3 initialPosition = protection.initialPosition();
        double x = packet.getX(initialPosition.x);
        double y = packet.getY(initialPosition.y);
        double z = packet.getZ(initialPosition.z);
        if (initialPosition.distanceToSqr(x, y, z) <= MOVEMENT_EPSILON_SQ) {
            return;
        }

        if (protections.remove(player.getUUID(), protection)) {
            HybridAuthMod.getAuthManager().audit(player, "PREMIUM_SPAWN_PROTECTION_RELEASED", "reason=client_movement");
        }
    }

    private record Protection(Vec3 initialPosition, long expiresAtNanos) {
    }
}
