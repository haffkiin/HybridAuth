package com.hybridauth.skin;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Клиент MineSkin API v2: по ссылке на PNG получает скин, подписанный Mojang.
 * Нужен потому, что клиент принимает текстуры только с доменов Mojang, а подпись получить можно
 * лишь через загрузку скина на аккаунт Mojang, что и делает MineSkin.
 *
 * Поток: {@code POST /v2/queue} → при необходимости опрос {@code GET /v2/queue/{id}}
 * (не чаще раза в {@value #POLL_INTERVAL_MS} мс) → {@code GET /v2/skins/{uuid}}, если текстур нет в ответе.
 */
public final class MineSkinClient {

    private static final String DEFAULT_BASE_URL = "https://api.mineskin.org";
    private static final long POLL_INTERVAL_MS = 2000;
    private static final String SKIN_NAME = "hybridauth";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HybridAuth-MineSkin");
        thread.setDaemon(true);
        return thread;
    });
    private final String baseUrl;
    private final String userAgent;
    private volatile String apiKey = "";
    private volatile int timeoutSeconds = 45;

    public MineSkinClient(String modVersion) {
        this(DEFAULT_BASE_URL, modVersion);
    }

    /** Для тестов: адрес локального сервера. */
    MineSkinClient(String baseUrl, String modVersion) {
        this.baseUrl = baseUrl;
        this.userAgent = "HybridAuth/" + modVersion;
    }

    public void configure(String apiKey, int timeoutSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeoutSeconds = Math.max(10, timeoutSeconds);
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * Создаёт подписанный скин из картинки по ссылке. Ссылка уходит в MineSkin: тот сам скачивает
     * картинку. Результат: текстуры; ошибка завершает будущее {@link SkinException}.
     */
    public CompletableFuture<SkinProperty> generate(String url, SkinVariant variant) {
        if (!isConfigured()) {
            return CompletableFuture.failedFuture(new SkinException(
                    SkinException.Reason.NOT_CONFIGURED, "ключ MineSkin не задан"));
        }

        JsonObject body = new JsonObject();
        body.addProperty("url", url);
        body.addProperty("variant", variant.apiName());
        body.addProperty("name", SKIN_NAME);
        body.addProperty("visibility", "unlisted");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        HttpRequest request = requestBuilder("/v2/queue")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return handle(send(request), deadline).exceptionally(MineSkinClient::rethrowAsSkinException);
    }

    private CompletableFuture<SkinProperty> handle(CompletableFuture<MineSkinResponse> response, long deadline) {
        return response.thenCompose(parsed -> switch (parsed.kind()) {
            case COMPLETED -> CompletableFuture.completedFuture(parsed.property());
            case PENDING -> poll(parsed.jobId(), deadline);
            case SKIN_LOOKUP -> handle(send(requestBuilder("/v2/skins/" + parsed.skinUuid()).GET().build()), deadline);
            case RATE_LIMITED -> CompletableFuture.failedFuture(
                    new SkinException(SkinException.Reason.RATE_LIMITED, parsed.message()));
            case AUTH_ERROR -> CompletableFuture.failedFuture(
                    new SkinException(SkinException.Reason.NOT_CONFIGURED, parsed.message()));
            case FAILED -> CompletableFuture.failedFuture(
                    new SkinException(SkinException.Reason.REJECTED, parsed.message()));
        });
    }

    private CompletableFuture<SkinProperty> poll(String jobId, long deadline) {
        if (System.nanoTime() > deadline) {
            return CompletableFuture.failedFuture(new SkinException(
                    SkinException.Reason.UNAVAILABLE, "MineSkin не успел создать скин"));
        }
        CompletableFuture<MineSkinResponse> next = new CompletableFuture<>();
        scheduler.schedule(() -> send(requestBuilder("/v2/queue/" + jobId).GET().build())
                .whenComplete((value, failure) -> {
                    if (failure != null) {
                        next.completeExceptionally(failure);
                    } else {
                        next.complete(value);
                    }
                }), POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        return handle(next, deadline);
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey);
    }

    private CompletableFuture<MineSkinResponse> send(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> MineSkinResponse.parse(response.statusCode(), response.body()));
    }

    private static SkinProperty rethrowAsSkinException(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof SkinException skinException) {
            throw skinException;
        }
        throw new SkinException(SkinException.Reason.UNAVAILABLE, "MineSkin недоступен", cause);
    }
}
