package com.hybridauth.events;

import com.hybridauth.HybridAuthMod;
import com.hybridauth.auth.AuthManager;
import com.hybridauth.auth.LicenseNotifier;
import com.hybridauth.config.ModConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthEventHandler {

    private static final double POSITION_EPSILON_SQ = 1.0E-12D;

    /** Команды, доступные неавторизованному игроку. */
    private static final Set<String> AUTH_COMMANDS = Set.of("login", "l", "register", "reg", "recover");

    private final Map<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final Map<UUID, AuthLock> authLocks = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AuthManager authManager = HybridAuthMod.getAuthManager();
            if (authManager == null) {
                return;
            }

            authManager.handlePlayerJoin(player);
            if (ModConfig.SERVER.premiumSpawnProtection.get() && authManager.isPremiumPlayer(player)) {
                HybridAuthMod.getPremiumSpawnProtection().arm(player);
            } else {
                HybridAuthMod.getPremiumSpawnProtection().clear(player);
            }
            LicenseNotifier.onPlayerJoin(player);
            if (needsAuthProtection(player)) {
                authLocks.put(player.getUUID(), AuthLock.capture(player));
                protectUnauthenticatedPlayer(player);
            } else {
                authLocks.remove(player.getUUID());
            }
        }
    }

    @SubscribeEvent
    public void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AuthManager authManager = HybridAuthMod.getAuthManager();
            if (authManager != null) {
                authManager.handlePlayerQuit(player);
            }
            lastMessageTime.remove(player.getUUID());
            authLocks.remove(player.getUUID());
            HybridAuthMod.getPremiumSpawnProtection().clear(player);
        }
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            HybridAuthMod.getPremiumSpawnProtection().expireIfNeeded(player);
            if (!needsAuthProtection(player)) {
                authLocks.remove(player.getUUID());
                return;
            }

            protectUnauthenticatedPlayer(player);

            long now = System.currentTimeMillis();
            long lastMsg = lastMessageTime.getOrDefault(player.getUUID(), 0L);
            if (now - lastMsg > 10000) {
                sendAuthPrompt(player);
                lastMessageTime.put(player.getUUID(), now);
            }
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && needsAuthProtection(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && needsAuthProtection(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onPlayerRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player && needsAuthProtection(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onPlayerRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && needsAuthProtection(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onPlayerLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && needsAuthProtection(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player && needsAuthProtection(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && needsAuthProtection(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && needsDamageProtection(player)) {
            event.setCanceled(true);
            event.setAmount(0.0F);
            if (needsAuthProtection(player)) {
                protectUnauthenticatedPlayer(player);
            }
            return;
        }

        if (event.getSource().getEntity() instanceof ServerPlayer player && needsAuthProtection(player)) {
            event.setCanceled(true);
            event.setAmount(0.0F);
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && needsDamageProtection(player)) {
            event.setCanceled(true);
            if (player.getHealth() <= 0.0F) {
                player.setHealth(1.0F);
            }
            if (needsAuthProtection(player)) {
                protectUnauthenticatedPlayer(player);
            }
        }
    }

    @SubscribeEvent
    public void onItemDrop(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && needsAuthProtection(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onItemPickup(ItemEntityPickupEvent.Pre event) {
        if (event.getPlayer() instanceof ServerPlayer player && needsAuthProtection(player)) {
            event.setCanPickup(TriState.FALSE);
        }
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (needsAuthProtection(player)) {
            event.setCanceled(true);
            sendAuthPrompt(player);
        }
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ignored) {
            return;
        }

        if (!needsAuthProtection(player)) {
            return;
        }

        String command = event.getParseResults().getReader().getString().trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        String rootCommand = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!AUTH_COMMANDS.contains(rootCommand)) {
            event.setCanceled(true);
            sendAuthPrompt(player);
        }
    }

    private boolean needsAuthProtection(ServerPlayer player) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        return ModConfig.SERVER.enabled.get()
                && authManager != null
                && !authManager.isAuthenticated(player.getUUID());
    }

    private boolean needsDamageProtection(ServerPlayer player) {
        return needsAuthProtection(player) || HybridAuthMod.getPremiumSpawnProtection().isProtected(player);
    }

    private void protectUnauthenticatedPlayer(ServerPlayer player) {
        UUID uuid = player.getUUID();
        AuthLock lock = authLocks.computeIfAbsent(uuid, ignored -> AuthLock.capture(player));

        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.resetFallDistance();
        player.clearFire();
        player.setAirSupply(player.getMaxAirSupply());
        player.stopRiding();

        if (player.position().distanceToSqr(lock.position()) > POSITION_EPSILON_SQ) {
            Vec3 position = lock.position();
            player.teleportTo(position.x, position.y, position.z);
            player.setYRot(lock.yRot());
            player.setXRot(lock.xRot());
            player.setYHeadRot(lock.yRot());
        }
    }

    private void sendAuthPrompt(ServerPlayer player) {
        AuthManager authManager = HybridAuthMod.getAuthManager();
        if (authManager == null) {
            return;
        }

        boolean registered = authManager.getStorage().load(player.getUUID()).isPresent()
                || authManager.getStorage().loadByExactUsername(player.getScoreboardName()).isPresent();
        String message = registered ? ModConfig.SERVER.msgLoginPrompt.get() : ModConfig.SERVER.msgRegisterPrompt.get();
        player.sendSystemMessage(Component.literal(colorize(message)));
    }

    private String colorize(String message) {
        return message.replace("&", "\u00A7");
    }

    private record AuthLock(Vec3 position, float yRot, float xRot) {
        private static AuthLock capture(ServerPlayer player) {
            return new AuthLock(player.position(), player.getYRot(), player.getXRot());
        }
    }
}
