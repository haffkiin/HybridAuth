package com.hybridauth.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Скин лицензионного аккаунта: профиль с подписанными текстурами с сервера сессий Mojang.
 * Сервер Mojang ограничивает частоту запросов по одному UUID, поэтому удачные ответы кэшируются.
 */
public final class MojangSkinFetcher {

    private static final String DEFAULT_PROFILE_URL =
            "https://sessionserver.mojang.com/session/minecraft/profile/%s?unsigned=false";
    private static final int CACHE_LIMIT = 512;

    private record Cached(SkinProperty property, Instant expiresAt) {
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String profileUrl;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    private volatile int timeoutMs = 5000;
    private volatile int cacheMinutes = 10;

    public MojangSkinFetcher() {
        this(DEFAULT_PROFILE_URL);
    }

    /** Для тестов: адрес локального сервера с одним {@code %s} под UUID без тире. */
    MojangSkinFetcher(String profileUrl) {
        this.profileUrl = profileUrl;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = Math.max(1000, timeoutMs);
    }

    public void setCacheMinutes(int cacheMinutes) {
        this.cacheMinutes = Math.max(1, cacheMinutes);
    }

    /** Текстуры скина аккаунта; ошибка завершается {@link SkinException}. */
    public CompletableFuture<SkinProperty> fetch(UUID mojangUuid) {
        Cached cached = cache.get(mojangUuid);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return CompletableFuture.completedFuture(cached.property());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(profileUrl, mojangUuid.toString().replace("-", ""))))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    switch (response.statusCode()) {
                        case 200 -> {
                            SkinProperty property = parseTextures(response.body()).orElseThrow(() ->
                                    new SkinException(SkinException.Reason.NOT_FOUND, "у профиля нет текстур"));
                            remember(mojangUuid, property);
                            return property;
                        }
                        case 204, 404 -> throw new SkinException(SkinException.Reason.NOT_FOUND, "профиль не найден");
                        case 429 -> throw new SkinException(SkinException.Reason.RATE_LIMITED, "Mojang просит подождать");
                        default -> throw new SkinException(SkinException.Reason.UNAVAILABLE,
                                "код ответа Mojang " + response.statusCode());
                    }
                })
                .exceptionally(error -> {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null
                            ? error.getCause()
                            : error;
                    if (cause instanceof SkinException skinException) {
                        throw skinException;
                    }
                    throw new SkinException(SkinException.Reason.UNAVAILABLE, "Mojang недоступен", cause);
                });
    }

    private void remember(UUID id, SkinProperty property) {
        if (cache.size() >= CACHE_LIMIT) {
            Instant now = Instant.now();
            cache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
            if (cache.size() >= CACHE_LIMIT) {
                cache.clear();
            }
        }
        cache.put(id, new Cached(property, Instant.now().plusSeconds(cacheMinutes * 60L)));
    }

    /** Свойство {@code textures} из ответа {@code session/minecraft/profile/<uuid>}. */
    static Optional<SkinProperty> parseTextures(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("properties")) {
                return Optional.empty();
            }
            JsonArray properties = root.getAsJsonArray("properties");
            for (JsonElement element : properties) {
                JsonObject property = element.getAsJsonObject();
                if ("textures".equals(property.get("name").getAsString())) {
                    String signature = property.has("signature") && !property.get("signature").isJsonNull()
                            ? property.get("signature").getAsString()
                            : null;
                    SkinProperty result = new SkinProperty(property.get("value").getAsString(), signature);
                    // Профиль без SKIN (например только плащ) для нас не скин
                    return SkinTextures.inspect(result.value()).isPresent() ? Optional.of(result) : Optional.empty();
                }
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
