package com.hybridauth.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Клиент для работы с Mojang API.
 * Выполняет асинхронные HTTP-запросы для проверки лицензии игроков.
 */
public class MojangApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("HybridAuth");
    private static final String DEFAULT_PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/%s";
    private static final String DEFAULT_HAS_JOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s";

    private final HttpClient httpClient;
    private final String profileUrl;
    private final String hasJoinedUrl;
    private final Map<String, CachedLookup> profileCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<PremiumLookupResult>> inFlightLookups = new ConcurrentHashMap<>();
    private volatile int timeoutMs = 5000;
    private volatile int cacheExpirationMinutes = 10;

    public MojangApiClient() {
        this(DEFAULT_PROFILE_URL, DEFAULT_HAS_JOINED_URL);
    }

    /** Package-private constructor for tests — allows injecting a local server URL. */
    MojangApiClient(String profileUrl, String hasJoinedUrl) {
        this.profileUrl = profileUrl;
        this.hasJoinedUrl = hasJoinedUrl;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofMillis(5000))
                .build();
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public void setCacheExpirationMinutes(int cacheExpirationMinutes) {
        this.cacheExpirationMinutes = Math.max(1, cacheExpirationMinutes);
    }

    /**
     * Этап 1: Проверка существования ника в базе Mojang.
     * Возвращает Optional<UUID> если ник принадлежит премиум-аккаунту.
     */
    public CompletableFuture<PremiumLookupResult> checkPremium(String username) {
        String cacheKey = username.toLowerCase(Locale.ROOT);
        CachedLookup cached = profileCache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return CompletableFuture.completedFuture(cached.result());
        }
        if (cached != null) {
            profileCache.remove(cacheKey, cached);
        }

        CompletableFuture<PremiumLookupResult> lookup = inFlightLookups.computeIfAbsent(
                cacheKey,
                ignored -> requestPremium(username));
        lookup.whenComplete((result, failure) -> {
            inFlightLookups.remove(cacheKey, lookup);
            if (failure == null && result != null && result.status() != PremiumLookupResult.Status.API_UNAVAILABLE) {
                profileCache.put(cacheKey, new CachedLookup(
                        result,
                        Instant.now().plusSeconds(cacheExpirationMinutes * 60L)));
                trimCache();
            }
        });
        return lookup;
    }

    private CompletableFuture<PremiumLookupResult> requestPremium(String username) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(profileUrl, username)))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        try {
                            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                            String id = json.get("id").getAsString();
                            String canonicalName = json.get("name").getAsString();
                            // Mojang API возвращает UUID без тире, нужно отформатировать
                            UUID uuid = parseUUIDWithoutDashes(id);
                            return PremiumLookupResult.premium(uuid, canonicalName);
                        } catch (Exception e) {
                            LOGGER.error("[HybridAuth] Ошибка парсинга ответа Mojang API для {}", username, e);
                            return PremiumLookupResult.apiUnavailable();
                        }
                    } else if (response.statusCode() == 204 || response.statusCode() == 404) {
                        // Ник не найден - пират
                        return PremiumLookupResult.notPremium();
                    } else {
                        LOGGER.warn("[HybridAuth] Неожиданный код ответа Mojang API: {} для {}", response.statusCode(), username);
                        return PremiumLookupResult.apiUnavailable();
                    }
                })
                .exceptionally(error -> {
                    LOGGER.error("[HybridAuth] Mojang API request failed for {}", username, error);
                    return PremiumLookupResult.apiUnavailable();
                });
    }

    private void trimCache() {
        if (profileCache.size() <= 4096) {
            return;
        }
        Instant now = Instant.now();
        profileCache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        if (profileCache.size() > 4096) {
            profileCache.clear();
        }
    }

    /**
     * Этап 2: Верификация сессии клиента (hasJoined).
     * Используется для подтверждения, что подключающийся клиент действительно владелец аккаунта.
     */
    public CompletableFuture<Optional<GameProfile>> hasJoined(String username, String serverIdHash) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(hasJoinedUrl, username, serverIdHash)))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        try {
                            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                            String id = json.get("id").getAsString();
                            String name = json.get("name").getAsString();
                            UUID uuid = parseUUIDWithoutDashes(id);
                            
                            GameProfile profile = new GameProfile(uuid, name);
                            
                            // Извлечение properties (скины, плащи)
                            if (json.has("properties")) {
                                JsonArray properties = json.getAsJsonArray("properties");
                                for (JsonElement elem : properties) {
                                    JsonObject propObj = elem.getAsJsonObject();
                                    String propName = propObj.get("name").getAsString();
                                    String propValue = propObj.get("value").getAsString();
                                    String propSignature = propObj.has("signature") ? propObj.get("signature").getAsString() : null;
                                    profile.getProperties().put(propName, new Property(propName, propValue, propSignature));
                                }
                            }
                            
                            return Optional.of(profile);
                        } catch (Exception e) {
                            LOGGER.error("[HybridAuth] Ошибка парсинга ответа hasJoined для {}", username, e);
                            return Optional.empty();
                        }
                    } else if (response.statusCode() == 204) {
                        // Сессия не подтверждена
                        return Optional.empty();
                    } else {
                        LOGGER.warn("[HybridAuth] Неожиданный код ответа hasJoined: {} для {}", response.statusCode(), username);
                        throw new RuntimeException("hasJoined error: " + response.statusCode());
                    }
                });
    }

    private UUID parseUUIDWithoutDashes(String id) {
        return UUID.fromString(id.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                "$1-$2-$3-$4-$5"
        ));
    }

    private record CachedLookup(PremiumLookupResult result, Instant expiresAt) {
    }
}
