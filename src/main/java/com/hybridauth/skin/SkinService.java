package com.hybridauth.skin;

import com.hybridauth.auth.MinecraftNames;
import com.hybridauth.auth.MojangApiClient;
import com.hybridauth.auth.PremiumLookupResult;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Скины игроков: получение из Mojang или по ссылке, хранение, применение к профилю.
 *
 * Получение идёт в фоне, применение — на потоке сервера. Лимиты на частоту и «один запрос за раз»
 * защищают Mojang и MineSkin от перегрузки.
 */
public final class SkinService {

    private static final Logger LOGGER = LoggerFactory.getLogger("HybridAuth");
    private static final String TEXTURES = "textures";

    private final SkinStorage storage;
    private final MojangApiClient mojangApi;
    private final MojangSkinFetcher mojangSkins;
    private final MineSkinClient mineSkin;

    /** Исходная текстура игрока в этом сеансе (у лицензионных — от Mojang), чтобы вернуть её при сбросе. */
    private final Map<UUID, Property> originals = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequestAt = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    /** Скины ников из галереи /skin gallery: загружаются заранее, чтобы в меню были головы с настоящими скинами. */
    private final Map<String, SkinEntry> gallery = new ConcurrentHashMap<>();
    private final Set<String> galleryLoading = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService galleryLoader = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HybridAuth-SkinGallery");
        thread.setDaemon(true);
        return thread;
    });

    public SkinService(SkinStorage storage, MojangApiClient mojangApi, MojangSkinFetcher mojangSkins,
                       MineSkinClient mineSkin) {
        this.storage = storage;
        this.mojangApi = mojangApi;
        this.mojangSkins = mojangSkins;
        this.mineSkin = mineSkin;
    }

    public SkinStorage storage() {
        return storage;
    }

    public MineSkinClient mineSkin() {
        return mineSkin;
    }

    public MojangSkinFetcher mojangSkins() {
        return mojangSkins;
    }

    // ─── галерея ─────────────────────────────────────────────────────────────

    /**
     * Загружает скины ников галереи в фоне, не чаще одного запроса в 400 мс, чтобы не упереться
     * в лимиты Mojang. Уже загруженные и загружающиеся ники пропускаются.
     */
    public void prefetchGallery(List<? extends String> nicks) {
        long delay = 0;
        for (String nick : nicks) {
            String key = nick.toLowerCase(Locale.ROOT);
            if (gallery.containsKey(key) || !galleryLoading.add(key)) {
                continue;
            }
            galleryLoader.schedule(() -> {
                try {
                    fetchByNick(nick).whenComplete((entry, failure) -> {
                        galleryLoading.remove(key);
                        if (entry != null) {
                            gallery.put(key, entry);
                        }
                    });
                } catch (RuntimeException e) {
                    galleryLoading.remove(key);
                }
            }, delay, TimeUnit.MILLISECONDS);
            delay += 400;
        }
    }

    public Optional<SkinEntry> galleryEntry(String nick) {
        return Optional.ofNullable(gallery.get(nick.toLowerCase(Locale.ROOT)));
    }

    public void shutdown() {
        galleryLoader.shutdownNow();
        mineSkin.shutdown();
    }

    // ─── вход и выход игрока ─────────────────────────────────────────────────

    /** Вызывается в начале {@code placeNewPlayer}: профиль игрока ещё не разослан. */
    public void onPlayerJoining(ServerPlayer player) {
        try {
            GameProfile profile = player.getGameProfile();
            profile.getProperties().get(TEXTURES).stream().findFirst()
                    .ifPresent(original -> originals.put(player.getUUID(), original));
            storage.get(player.getUUID()).ifPresent(entry -> setTextures(profile, entry.property()));
        } catch (RuntimeException e) {
            // Скин не должен ломать вход
            LOGGER.error("[HybridAuth] Не удалось применить скин игрока {}", player.getScoreboardName(), e);
        }
    }

    public void onPlayerQuit(ServerPlayer player) {
        originals.remove(player.getUUID());
        busy.remove(player.getUUID());
    }

    // ─── ограничения запросов ────────────────────────────────────────────────

    /**
     * Резервирует запрос игрока.
     *
     * @return пусто, если можно начинать; иначе число секунд ожидания или {@code -1}, если предыдущий запрос ещё идёт
     */
    public Optional<Integer> tryBegin(UUID id, int cooldownSeconds) {
        if (!busy.add(id)) {
            return Optional.of(-1);
        }
        int wait = SkinRequestRules.cooldownRemainingSeconds(
                lastRequestAt.getOrDefault(id, 0L), System.currentTimeMillis(), cooldownSeconds);
        if (wait > 0) {
            busy.remove(id);
            return Optional.of(wait);
        }
        if (cooldownSeconds > 0) {
            lastRequestAt.put(id, System.currentTimeMillis());
        }
        return Optional.empty();
    }

    /** Неудачный запрос не должен задерживать следующую попытку: пауза отсчитывается только от успешной смены. */
    public void refund(UUID id) {
        lastRequestAt.remove(id);
    }

    public void end(UUID id) {
        busy.remove(id);
    }

    // ─── получение скина ─────────────────────────────────────────────────────

    /** Скин лицензионного аккаунта с этим ником. Ошибка завершается {@link SkinException}. */
    public CompletableFuture<SkinEntry> fetchByNick(String nick) {
        if (!MinecraftNames.isValid(nick)) {
            return CompletableFuture.failedFuture(new SkinException(SkinException.Reason.NOT_FOUND, "некорректный ник"));
        }
        return mojangApi.checkPremium(nick).thenCompose((PremiumLookupResult lookup) -> {
            if (lookup.status() == PremiumLookupResult.Status.PREMIUM) {
                String name = lookup.canonicalName() == null || lookup.canonicalName().isEmpty()
                        ? nick
                        : lookup.canonicalName();
                return mojangSkins.fetch(lookup.premiumUuid()).thenApply(property -> new SkinEntry(
                        SkinEntry.Source.NICK,
                        name,
                        SkinTextures.inspect(property.value()).map(SkinTextures.Info::variant)
                                .orElse(SkinVariant.CLASSIC),
                        property,
                        System.currentTimeMillis()));
            }
            SkinException.Reason reason = lookup.status() == PremiumLookupResult.Status.NOT_PREMIUM
                    ? SkinException.Reason.NOT_FOUND
                    : SkinException.Reason.UNAVAILABLE;
            return CompletableFuture.<SkinEntry>failedFuture(new SkinException(reason, "ник не найден в Mojang"));
        });
    }

    /** Скин по ссылке на PNG через MineSkin. Ссылка должна быть проверена {@link SkinRequestRules}. */
    public CompletableFuture<SkinEntry> fetchByUrl(String url, SkinVariant requested) {
        return mineSkin.generate(url, requested).thenApply(property -> new SkinEntry(
                SkinEntry.Source.URL,
                url,
                requested != SkinVariant.AUTO
                        ? requested
                        : SkinTextures.inspect(property.value()).map(SkinTextures.Info::variant)
                                .orElse(SkinVariant.CLASSIC),
                property,
                System.currentTimeMillis()));
    }

    // ─── применение (поток сервера) ──────────────────────────────────────────

    /**
     * Сохраняет скин и, если игрок на сервере, показывает его сразу.
     *
     * @return false, если запись не удалось сохранить на диск (скин при этом действует до выхода)
     */
    public boolean assign(MinecraftServer server, UUID id, SkinEntry entry) {
        boolean saved = storage.put(id, entry);
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) {
            setTextures(player.getGameProfile(), entry.property());
            refreshSafely(player);
        }
        return saved;
    }

    /** Убирает выбранный скин: у лицензионного игрока возвращается его скин Mojang, у остальных стандартный. */
    public boolean clear(MinecraftServer server, UUID id) {
        boolean existed = storage.remove(id);
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null && existed) {
            GameProfile profile = player.getGameProfile();
            profile.getProperties().removeAll(TEXTURES);
            Property original = originals.get(id);
            if (original != null) {
                profile.getProperties().put(TEXTURES, original);
            }
            refreshSafely(player);
        }
        return existed;
    }

    private static void setTextures(GameProfile profile, SkinProperty property) {
        profile.getProperties().removeAll(TEXTURES);
        profile.getProperties().put(TEXTURES, new Property(TEXTURES, property.value(), property.signature()));
    }

    private static void refreshSafely(ServerPlayer player) {
        if (player.connection == null) {
            return;
        }
        try {
            SkinRefresher.refresh(player);
        } catch (RuntimeException e) {
            LOGGER.error("[HybridAuth] Скин игрока {} выбран, но обновить его на экране не удалось: нужен перезаход.",
                    player.getScoreboardName(), e);
        }
    }
}
