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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class AuthManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("HybridAuth");

    private final PlayerStorage storage;
    private final SessionManager sessionManager;
    private final AuthAuditLogger auditLogger;
    private final Map<UUID, Boolean> authenticatedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    /** PBKDF2 (310k итераций) работает здесь, а не на потоке сервера. */
    private final ExecutorService passwordExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "hybridauth-password");
        thread.setDaemon(true);
        return thread;
    });
    /** Игроки, у которых сейчас идёт проверка/хеширование пароля: одна операция за раз. */
    private final Set<UUID> passwordOperations = ConcurrentHashMap.newKeySet();
    /**
     * Кракнутые записи, ник которых занят лицензионным аккаунтом в Mojang.
     * Их владельцы входят по паролю, а владелец лицензии получает сообщение о техподдержке.
     */
    private final Set<UUID> licensedNameConflicts = ConcurrentHashMap.newKeySet();
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

    /** Количество онлайн-игроков, прошедших авторизацию (для /hybridauth status). */
    public int countAuthenticated() {
        int count = 0;
        for (Boolean authenticated : authenticatedPlayers.values()) {
            if (authenticated) {
                count++;
            }
        }
        return count;
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
        licensedNameConflicts.remove(player.getUUID());
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
                        String kickMessage = isLicensedNameConflict(player.getUUID())
                                ? ModConfig.SERVER.msgLicensedNameOccupied.get()
                                : ModConfig.SERVER.msgAuthTimeout.get();
                        player.connection.disconnect(Component.literal(colorize(kickMessage)));
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

        // Вход по сессии не продлевает её: срок отсчитывается от входа по паролю/лицензии.
        if (!"SESSION".equals(method)) {
            sessionManager.createSession(username, uuid, ip);
        }
        auditLogger.log(
                "LOGIN_SUCCESS",
                username,
                uuid,
                ip,
                "method=" + (method == null ? "UNKNOWN" : method));

        if (!isSilent) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgLoginSuccess.get())));
        }

        if ("PREMIUM".equals(method)) {
            LicenseNotifier.notifyPremiumVerified(player);
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
        return ipFromSocketAddress(remoteAddress);
    }

    /** Извлекает IP из адреса соединения; работает и для фазы логина (до создания ServerPlayer). */
    public static String ipFromSocketAddress(SocketAddress remoteAddress) {
        if (remoteAddress instanceof InetSocketAddress inetSocketAddress) {
            InetAddress address = inetSocketAddress.getAddress();
            if (address != null) {
                return address.getHostAddress();
            }
            return inetSocketAddress.getHostString();
        }

        return normalizeRemoteAddressStatic(remoteAddress == null ? "" : remoteAddress.toString());
    }

    private static String normalizeRemoteAddressStatic(String remoteAddress) {
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
        // \n \u0432 \u0442\u0435\u043A\u0441\u0442\u0435 Minecraft \u0440\u0438\u0441\u0443\u0435\u0442 \u043A\u0430\u043A \u0437\u043D\u0430\u0447\u043E\u043A [CR], \u043F\u043E\u044D\u0442\u043E\u043C\u0443 \u043F\u0435\u0440\u0435\u043D\u043E\u0441\u044B \u0437\u0430\u043C\u0435\u043D\u044F\u0435\u043C \u043F\u0440\u043E\u0431\u0435\u043B\u043E\u043C
        return message.replace("&", "\u00A7").replace("\n", " ");
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
                ModConfig.SERVER.maxLoginAttempts.get(),
                isLastSuccessfulAddress(player));
    }

    public LoginRateLimiter.Result recordFailedAttempt(ServerPlayer player) {
        return loginRateLimiter.recordFailure(
                player.getUUID(),
                player.getScoreboardName(),
                getRemoteIp(player),
                ModConfig.SERVER.maxLoginAttempts.get(),
                isLastSuccessfulAddress(player));
    }

    public void clearFailedAttempts(ServerPlayer player) {
        loginRateLimiter.clear(player.getUUID(), player.getScoreboardName(), getRemoteIp(player));
    }

    /**
     * IP, с которого аккаунт успешно входил последним, не блокируется общим локом по нику:
     * иначе чужой перебор закрывал бы владельцу доступ с его собственного адреса.
     */
    private boolean isLastSuccessfulAddress(ServerPlayer player) {
        PlayerData data = storage.load(player.getUUID())
                .orElseGet(() -> storage.loadByExactUsername(player.getScoreboardName()).orElse(null));
        return data != null && getRemoteIp(player).equals(data.getLastLoginIp());
    }

    /** Ставит операцию с паролем в очередь на пул хеширования. */
    public <T> CompletableFuture<T> supplyPasswordTask(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, passwordExecutor);
    }

    /**
     * Вызывается при каждом входе кракнутой записи: true, если её ник занят лицензионным аккаунтом.
     */
    public void setLicensedNameConflict(UUID uuid, boolean conflict) {
        if (conflict) {
            licensedNameConflicts.add(uuid);
        } else {
            licensedNameConflicts.remove(uuid);
        }
    }

    public boolean isLicensedNameConflict(UUID uuid) {
        return licensedNameConflicts.contains(uuid);
    }

    /** @return false, если для этого игрока уже выполняется операция с паролем */
    public boolean beginPasswordOperation(UUID uuid) {
        return passwordOperations.add(uuid);
    }

    public void endPasswordOperation(UUID uuid) {
        passwordOperations.remove(uuid);
    }

    public void shutdown() {
        scheduler.shutdown();
        passwordExecutor.shutdownNow();
        sessionManager.clear();
        storage.close();
        auditLogger.close();
    }
}
