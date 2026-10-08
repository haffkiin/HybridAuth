package com.hybridauth.skin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class MineSkinClientTest {

    private static final String SKIN = "{\"uuid\":\"u1\",\"texture\":{\"data\":{\"value\":\"VAL\",\"signature\":\"SIG\"}}}";

    private HttpServer server;
    private MineSkinClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        client = new MineSkinClient("http://localhost:" + server.getAddress().getPort(), "test");
        client.configure("secret-key", 30);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        client.shutdown();
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void notConfiguredFailsWithoutRequest() {
        MineSkinClient unconfigured = new MineSkinClient("http://localhost:1", "test");
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> unconfigured.generate("https://example.com/a.png", SkinVariant.AUTO).join());
        assertEquals(SkinException.Reason.NOT_CONFIGURED, ((SkinException) thrown.getCause()).reason());
        assertTrue(!unconfigured.isConfigured());
        unconfigured.shutdown();
    }

    @Test
    void immediateCompletion_sendsKeyAndBody() {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> agent = new AtomicReference<>();
        AtomicReference<JsonObject> body = new AtomicReference<>();
        server.createContext("/v2/queue", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            agent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            body.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject());
            send(exchange, 200, "{\"success\":true,\"job\":{\"id\":\"j\",\"status\":\"completed\"},\"skin\":" + SKIN + "}");
        });

        SkinProperty property = client.generate("https://example.com/a.png", SkinVariant.SLIM).join();

        assertEquals("VAL", property.value());
        assertEquals("SIG", property.signature());
        assertEquals("Bearer secret-key", auth.get());
        assertEquals("HybridAuth/test", agent.get());
        assertEquals("https://example.com/a.png", body.get().get("url").getAsString());
        assertEquals("slim", body.get().get("variant").getAsString());
        assertEquals("unlisted", body.get().get("visibility").getAsString());
    }

    @Test
    void queuedJobIsPolledUntilCompleted() {
        AtomicInteger polls = new AtomicInteger();
        server.createContext("/v2/queue", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                send(exchange, 202, "{\"success\":true,\"job\":{\"id\":\"job-1\",\"status\":\"waiting\"}}");
            } else if (exchange.getRequestURI().getPath().equals("/v2/queue/job-1")) {
                int n = polls.incrementAndGet();
                if (n < 2) {
                    send(exchange, 200, "{\"job\":{\"id\":\"job-1\",\"status\":\"active\"}}");
                } else {
                    send(exchange, 200, "{\"job\":{\"id\":\"job-1\",\"status\":\"completed\",\"result\":\"u1\"}}");
                }
            } else {
                send(exchange, 404, "{}");
            }
        });
        server.createContext("/v2/skins/u1", exchange -> send(exchange, 200, "{\"success\":true,\"skin\":" + SKIN + "}"));

        SkinProperty property = client.generate("https://example.com/a.png", SkinVariant.AUTO).join();

        assertEquals("VAL", property.value());
        assertEquals(2, polls.get());
    }

    @Test
    void rateLimitMapsToReason() {
        server.createContext("/v2/queue", exchange -> send(exchange, 429, "{\"success\":false}"));
        assertReason(SkinException.Reason.RATE_LIMITED);
    }

    @Test
    void invalidKeyMapsToNotConfigured() {
        server.createContext("/v2/queue", exchange -> send(exchange, 401, "{\"success\":false}"));
        assertReason(SkinException.Reason.NOT_CONFIGURED);
    }

    @Test
    void rejectedImageMapsToRejectedWithMessage() {
        server.createContext("/v2/queue", exchange -> send(exchange, 400,
                "{\"success\":false,\"errors\":[{\"code\":\"invalid_image\",\"message\":\"Invalid image size\"}]}"));
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> client.generate("https://example.com/a.png", SkinVariant.AUTO).join());
        SkinException cause = (SkinException) thrown.getCause();
        assertEquals(SkinException.Reason.REJECTED, cause.reason());
        assertEquals("Invalid image size", cause.getMessage());
    }

    @Test
    void unreachableServiceIsUnavailable() {
        server.stop(0);
        assertReason(SkinException.Reason.UNAVAILABLE);
    }

    private void assertReason(SkinException.Reason expected) {
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> client.generate("https://example.com/a.png", SkinVariant.AUTO).join());
        assertEquals(expected, ((SkinException) thrown.getCause()).reason());
    }
}
