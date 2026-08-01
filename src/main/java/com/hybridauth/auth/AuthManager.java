package com.hybridauth.auth;

import com.hybridauth.audit.AuthAuditLogger;
import com.hybridauth.config.ModConfig;
import com.hybridauth.storage.PlayerData;
import com.hybridauth.storage.PlayerStorage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AuthManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("HybridAuth");

    private final PlayerStorage storage;
    private final SessionManager sessionManager;
    private final AuthAuditLogger auditLogger;
    private final Map<UUID, Boolean> authenticatedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final LoginRateLimiter loginRateLimiter = new LoginRateLimiter(300, 300);

    public AuthManager(PlayerStorage storage, SessionManager sessionManager, AuthAuditLogger auditLogger) {
        this.storage = storage;
        this.sessionManager = sessionManager;
        this.auditLogger = auditLogger;
        scheduler.scheduleAtFixedRate(loginRateLimiter::cleanup, 5, 5, TimeUnit.MINUTES);
    }

    public void reloadConfig() {
        sessionManager.setSessionDurationMinutes(ModConfig.SERVER.sessionDurationMinutes.get());
        sessionManager.setEnabled(ModConfig.SERVER.enableIpSession.get());
        loginRateLimiter.configure(
                ModConfig.SERVER.loginAttemptWindowSeconds.get(),
                ModConfig.SERVER.loginLockoutSeconds.get());
    }

    public boolean isAuthenticated(UUID uuid) {
        if (!ModConfig.SERVER.enabled.get()) {
            return true;
        }
        return authenticatedPlayers.getOrDefault(uuid, false);
    }

    public boolean isPremiumPlayer(ServerPlayer player) {
        PlayerData data = storage.load(player.getUUID()).orElse(null);
        return data != null && data.isPremium() && player.getUUID().equals(data.getUuid());
    }

    public void handlePlayerJoin(ServerPlayer player) {
        if (!ModConfig.SERVER.enabled.get()) {
            return;
        }

        UUID uuid = player.getUUID();
        String username = player.getScoreboardName();
        String ip = getRemoteIp(player);
        authenticatedPlayers.put(uuid, false);

        PlayerData data = storage.load(uuid).orElseGet(() -> storage.loadByExactUsername(username).orElse(null));
        audit(player, "CONNECTION", "registered=" + (data != null));

        if (data != null && data.isPremium()) {
            if (uuid.equals(data.getUuid())) {
                authenticate(player, false, "PREMIUM");
            } else {
                audit(player, "PREMIUM_UUID_MISMATCH", "stored_uuid=" + data.getUuid());
                player.connection.disconnect(Component.literal(colorize(ModConfig.SERVER.msgPremiumKick.get())));
            }
            return;
        }

        if (data != null && sessionManager.hasValidSession(username, uuid, ip)) {
            authenticate(player, true, "SESSION");
            return;
        }

        startAuthTimeout(player);
    }

    public void handlePlayerQuit(ServerPlayer player) {
        boolean wasAuthenticated = authenticatedPlayers.getOrDefault(player.getUUID(), false);
        authenticatedPlayers.remove(player.getUUID());
        cancelTimeoutTask(player.getUUID());
        audit(player, "DISCONNECT", "authenticated=" + wasAuthenticated);
    }

    private void startAuthTimeout(ServerPlayer player) {
        int timeoutSeconds = ModConfig.SERVER.authTimeoutSeconds.get();
        if (timeoutSeconds <= 0) {
            return;
        }

        cancelTimeoutTask(player.getUUID());
        ScheduledFuture<?> task = scheduler.schedule(() -> {
            if (!isAuthenticated(player.getUUID())) {
                player.server.execute(() -> {
                    if (!isAuthenticated(player.getUUID())) {
                        audit(player, "AUTH_TIMEOUT", "timeout_seconds=" + timeoutSeconds);
                        player.connection.disconnect(Component.literal(colorize(ModConfig.SERVER.msgAuthTimeout.get())));
                    }
                });
            }
        }, timeoutSeconds, TimeUnit.SECONDS);
        timeoutTasks.put(player.getUUID(), task);
    }

    private void cancelTimeoutTask(UUID uuid) {
        ScheduledFuture<?> task = timeoutTasks.remove(uuid);
        if (task != null) {
            task.cancel(false);
        }
    }

    public void authenticate(ServerPlayer player, boolean isSilent, String method) {
        UUID uuid = player.getUUID();
        authenticatedPlayers.put(uuid, true);
        cancelTimeoutTask(uuid);

        String username = player.getScoreboardName();
        String ip = getRemoteIp(player);
        loginRateLimiter.clear(uuid, username, ip);

        PlayerData data = storage.load(uuid).orElse(null);
        if (data == null) {
            PlayerData exactName = storage.loadByExactUsername(username).orElse(null);
            if (exactName != null && !uuid.equals(exactName.getUuid())) {
                audit(player, "IDENTITY_UUID_CONFLICT", "stored_uuid=" + exactName.getUuid());
                player.connection.disconnect(Component.literal(colorize(ModConfig.SERVER.msgPremiumKick.get())));
                return;
            }
            data = exactName != null
                    ? exactName
                    : new PlayerData(uuid, username, PlayerData.PlayerType.CRACKED);
        }
        data.setUsername(username);
        data.setLastLoginAt(Instant.now());
        data.setLastLoginIp(ip);
        storage.save(data);

        sessionManager.createSession(username, uuid, ip);
        auditLogger.log(
                "LOGIN_SUCCESS",
                username,
                uuid,
                ip,
                "method=" + (method == null ? "UNKNOWN" : method));

        if (!isSilent) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgLoginSuccess.get())));
        }

        LOGGER.info("[HybridAuth] Player {} ({}) authenticated.", username, data.getType());
    }

    public void audit(ServerPlayer player, String event, String details) {
        auditLogger.log(
                event,
                player.getScoreboardName(),
                player.getUUID(),
                getRemoteIp(player),
                details);
    }

    public void audit(String event, String username, UUID uuid, String ipAddress, String details) {
        auditLogger.log(event, username, uuid, ipAddress, details);
    }

    private String getRemoteIp(ServerPlayer player) {
        SocketAddress remoteAddress = player.connection.getRemoteAddress();
        if (remoteAddress instanceof InetSocketAddress inetSocketAddress) {
            InetAddress address = inetSocketAddress.getAddress();
            if (address != null) {
                return address.getHostAddress();
            }
            return inetSocketAddress.getHostString();
        }

        return normalizeRemoteAddress(remoteAddress == null ? "" : remoteAddress.toString());
    }

    private String normalizeRemoteAddress(String remoteAddress) {
        String value = remoteAddress == null ? "" : remoteAddress.trim();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }

        if (value.startsWith("[") && value.contains("]")) {
            return value.substring(1, value.indexOf(']'));
        }

        int lastColon = value.lastIndexOf(':');
        if (lastColon > -1 && value.indexOf(':') == lastColon) {
            return value.substring(0, lastColon);
        }

        return value;
    }

    private String colorize(String message) {
        return message.replace("&", "\u00A7");
    }

    public PlayerStorage getStorage() {
        return storage;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public LoginRateLimiter.Result loginAttemptStatus(ServerPlayer player) {
        return loginRateLimiter.status(
                player.getUUID(),
                player.getScoreboardName(),
                getRemoteIp(player),
                ModConfig.SERVER.maxLoginAttempts.get());
    }

    public LoginRateLimiter.Result recordFailedAttempt(ServerPlayer player) {
        return loginRateLimiter.recordFailure(
                player.getUUID(),
                player.getScoreboardName(),
                getRemoteIp(player),
                ModConfig.SERVER.maxLoginAttempts.get());
    }

    public void clearFailedAttempts(ServerPlayer player) {
        loginRateLimiter.clear(player.getUUID(), player.getScoreboardName(), getRemoteIp(player));
    }

    public void shutdown() {
        scheduler.shutdown();
        sessionManager.clear();
        storage.close();
        auditLogger.close();
    }
}
