package com.hybridauth.skin;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class MojangSkinFetcherTest {

    private static final UUID ID = UUID.fromString("7566a7ba-3341-4b19-81e0-e5c5c9c4a7f1");
    private static final String PATH = "/profile/7566a7ba33414b1981e0e5c5c9c4a7f1";

    private HttpServer server;
    private MojangSkinFetcher fetcher;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        fetcher = new MojangSkinFetcher("http://localhost:" + server.getAddress().getPort() + "/profile/%s");
        fetcher.setTimeoutMs(3000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(String path, int status, String body, AtomicInteger counter) {
        server.createContext(path, exchange -> {
            counter.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
    }

    private static String profileJson(String textureValue) {
        return "{\"id\":\"7566a7ba33414b1981e0e5c5c9c4a7f1\",\"name\":\"Test\",\"properties\":"
                + "[{\"name\":\"textures\",\"value\":\"" + textureValue + "\",\"signature\":\"SIG\"}]}";
    }

    @Test
    void fetch_returnsSignedTextures() {
        respond(PATH, 200, profileJson(SkinTestData.validValue()), new AtomicInteger());
        SkinProperty property = fetcher.fetch(ID).join();
        assertEquals(SkinTestData.validValue(), property.value());
        assertEquals("SIG", property.signature());
    }

    @Test
    void fetch_cachesSuccess() {
        AtomicInteger calls = new AtomicInteger();
        respond(PATH, 200, profileJson(SkinTestData.validValue()), calls);
        fetcher.fetch(ID).join();
        fetcher.fetch(ID).join();
        assertEquals(1, calls.get());
    }

    @Test
    void fetch_notFound() {
        respond(PATH, 204, "", new AtomicInteger());
        assertReason(SkinException.Reason.NOT_FOUND);
    }

    @Test
    void fetch_rateLimited() {
        respond(PATH, 429, "{}", new AtomicInteger());
        assertReason(SkinException.Reason.RATE_LIMITED);
    }

    @Test
    void fetch_serverError() {
        respond(PATH, 500, "{}", new AtomicInteger());
        assertReason(SkinException.Reason.UNAVAILABLE);
    }

    @Test
    void fetch_profileWithoutSkinTextureIsNotFound() {
        String capeOnly = java.util.Base64.getEncoder().encodeToString(
                "{\"textures\":{\"CAPE\":{\"url\":\"http://textures.minecraft.net/x\"}}}".getBytes(StandardCharsets.UTF_8));
        respond(PATH, 200, profileJson(capeOnly), new AtomicInteger());
        assertReason(SkinException.Reason.NOT_FOUND);
    }

    @Test
    void fetch_unreachableServerIsUnavailable() {
        server.stop(0);
        assertReason(SkinException.Reason.UNAVAILABLE);
    }

    @Test
    void parseTextures_rejectsForeignHostAndGarbage() {
        String foreign = SkinTestData.texturesValue("http://evil.example.com/a", null);
        assertTrue(MojangSkinFetcher.parseTextures(profileJson(foreign)).isEmpty());
        assertTrue(MojangSkinFetcher.parseTextures("not json").isEmpty());
        assertTrue(MojangSkinFetcher.parseTextures("{\"id\":\"x\"}").isEmpty());
    }

    private void assertReason(SkinException.Reason expected) {
        CompletionException thrown = assertThrows(CompletionException.class, () -> fetcher.fetch(ID).join());
        SkinException cause = (SkinException) thrown.getCause();
        assertEquals(expected, cause.reason());
    }
}
