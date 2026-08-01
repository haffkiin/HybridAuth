package com.hybridauth.auth;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MojangApiClient using a local embedded HTTP server.
 * Tests all documented outcomes: PREMIUM, NOT_PREMIUM, API_UNAVAILABLE,
 * hasJoined success and failure.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class MojangApiClientTest {

    private HttpServer server;
    private MojangApiClient client;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        port = server.getAddress().getPort();
        client = new MojangApiClient(
                "http://localhost:" + port + "/users/profiles/minecraft/%s",
                "http://localhost:" + port + "/session/minecraft/hasJoined?username=%s&serverId=%s");
        client.setTimeoutMs(3000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ──────────────────────────────────────────────────────────────────────
    // checkPremium
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void checkPremium_200_returnsPremiumWithUuid() throws Exception {
        UUID expected = UUID.fromString("7566a7ba-3341-4b19-81e0-e5c5c9c4a7f1");
        String mojangId = "7566a7ba33414b1981e0e5c5c9c4a7f1"; // no dashes
        String body = "{\"id\":\"" + mojangId + "\",\"name\":\"TestPlayer\"}";

        server.createContext("/users/profiles/minecraft/TestPlayer", exchange -> {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });

        PremiumLookupResult result = client.checkPremium("TestPlayer").get(5, TimeUnit.SECONDS);
        assertEquals(PremiumLookupResult.Status.PREMIUM, result.status());
        assertEquals(expected, result.premiumUuid());
        assertEquals("TestPlayer", result.canonicalName());
    }

    @Test
    void checkPremium_204_returnsNotPremium() throws Exception {
        server.createContext("/users/profiles/minecraft/Cracked", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
        });

        PremiumLookupResult result = client.checkPremium("Cracked").get(5, TimeUnit.SECONDS);
        assertEquals(PremiumLookupResult.Status.NOT_PREMIUM, result.status());
        assertNull(result.premiumUuid());
    }

    @Test
    void checkPremium_404_returnsNotPremium() throws Exception {
        server.createContext("/users/profiles/minecraft/Unknown", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.getResponseBody().close();
        });

        PremiumLookupResult result = client.checkPremium("Unknown").get(5, TimeUnit.SECONDS);
        assertEquals(PremiumLookupResult.Status.NOT_PREMIUM, result.status());
    }

    @Test
    void checkPremium_500_returnsApiUnavailable() throws Exception {
        server.createContext("/users/profiles/minecraft/Error500", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.getResponseBody().close();
        });

        PremiumLookupResult result = client.checkPremium("Error500").get(5, TimeUnit.SECONDS);
        assertEquals(PremiumLookupResult.Status.API_UNAVAILABLE, result.status());
    }

    @Test
    void checkPremium_connectionRefused_returnsApiUnavailable() throws Exception {
        // Point to a port that is not listening
        MojangApiClient badClient = new MojangApiClient(
                "http://localhost:1/users/profiles/minecraft/%s",
                "http://localhost:1/session/minecraft/hasJoined?username=%s&serverId=%s");
        badClient.setTimeoutMs(1000);

        PremiumLookupResult result = badClient.checkPremium("AnyPlayer").get(5, TimeUnit.SECONDS);
        assertEquals(PremiumLookupResult.Status.API_UNAVAILABLE, result.status());
    }

    @Test
    void checkPremium_timeout_returnsApiUnavailable() throws Exception {
        server.createContext("/users/profiles/minecraft/SlowPlayer", exchange -> {
            try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
            exchange.sendResponseHeaders(200, -1);
            exchange.getResponseBody().close();
        });

        client.setTimeoutMs(500);
        PremiumLookupResult result = client.checkPremium("SlowPlayer").get(8, TimeUnit.SECONDS);
        assertEquals(PremiumLookupResult.Status.API_UNAVAILABLE, result.status());
    }

    @Test
    void checkPremium_malformedJson_returnsApiUnavailable() throws Exception {
        byte[] body = "{broken json".getBytes();
        server.createContext("/users/profiles/minecraft/Broken", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });

        PremiumLookupResult result = client.checkPremium("Broken").get(5, TimeUnit.SECONDS);
        assertEquals(PremiumLookupResult.Status.API_UNAVAILABLE, result.status());
    }

    // ──────────────────────────────────────────────────────────────────────
    // hasJoined
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void hasJoined_200_returnsProfile() throws Exception {
        String mojangId = "7566a7ba33414b1981e0e5c5c9c4a7f1";
        String body = "{\"id\":\"" + mojangId + "\",\"name\":\"RealPlayer\",\"properties\":[]}";

        server.createContext("/session/minecraft/hasJoined", exchange -> {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });

        var profileOpt = client.hasJoined("RealPlayer", "serverHash").get(5, TimeUnit.SECONDS);
        assertTrue(profileOpt.isPresent(), "Should get a profile on 200 response");
        assertEquals("RealPlayer", profileOpt.get().getName(), "Profile name should match the response");
    }

    @Test
    void hasJoined_204_returnsEmpty() throws Exception {
        server.createContext("/session/minecraft/hasJoined", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
        });

        var profileOpt = client.hasJoined("FakePlayer", "serverHash").get(5, TimeUnit.SECONDS);
        assertFalse(profileOpt.isPresent());
    }

    @Test
    void hasJoined_500_completesExceptionally() throws Exception {
        server.createContext("/session/minecraft/hasJoined", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.getResponseBody().close();
        });

        var future = client.hasJoined("Player", "serverHash");
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void hasJoined_connectionError_completesExceptionally() throws Exception {
        MojangApiClient badClient = new MojangApiClient(
                "http://localhost:1/users/profiles/minecraft/%s",
                "http://localhost:1/session/minecraft/hasJoined?username=%s&serverId=%s");
        badClient.setTimeoutMs(1000);

        var future = badClient.hasJoined("Player", "serverHash");
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Scenario tests matching the plan
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void kickScenario_apiUnavailable_noKnownPremium_allowCrackedFallback() throws Exception {
        // Simulate ALLOW_CRACKED: unknown player, API down → should get API_UNAVAILABLE
        // so the auth manager can decide based on the config.
        MojangApiClient offlineClient = new MojangApiClient(
                "http://localhost:1/users/profiles/minecraft/%s",
                "http://localhost:1/session/minecraft/hasJoined?username=%s&serverId=%s");
        offlineClient.setTimeoutMs(500);

        PremiumLookupResult result = offlineClient.checkPremium("NewbieCracked").get(8, TimeUnit.SECONDS);
        // Must be API_UNAVAILABLE so ALLOW_CRACKED handling can trigger, not falsely NOT_PREMIUM
        assertEquals(PremiumLookupResult.Status.API_UNAVAILABLE, result.status());
    }

    @Test
    void premiumUuidParsedCorrectly_noDashes() throws Exception {
        // Ensure Mojang's no-dashes UUID format is correctly converted
        String compactId = "550e8400e29b41d4a716446655440000";
        UUID expected = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String body = "{\"id\":\"" + compactId + "\",\"name\":\"UuidCheck\"}";

        server.createContext("/users/profiles/minecraft/UuidCheck", exchange -> {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });

        PremiumLookupResult result = client.checkPremium("UuidCheck").get(5, TimeUnit.SECONDS);
        assertEquals(PremiumLookupResult.Status.PREMIUM, result.status());
        assertEquals(expected, result.premiumUuid());
    }

    @Test
    void multipleSimultaneousRequests_allComplete() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        server.createContext("/users/profiles/minecraft/Concurrent", exchange -> {
            count.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
        });

        var f1 = client.checkPremium("Concurrent");
        var f2 = client.checkPremium("Concurrent");
        var f3 = client.checkPremium("Concurrent");

        assertEquals(PremiumLookupResult.Status.NOT_PREMIUM, f1.get(5, TimeUnit.SECONDS).status());
        assertEquals(PremiumLookupResult.Status.NOT_PREMIUM, f2.get(5, TimeUnit.SECONDS).status());
        assertEquals(PremiumLookupResult.Status.NOT_PREMIUM, f3.get(5, TimeUnit.SECONDS).status());
    }
}
