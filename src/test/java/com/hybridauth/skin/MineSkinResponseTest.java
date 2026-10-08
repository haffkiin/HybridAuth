package com.hybridauth.skin;

import com.hybridauth.skin.MineSkinResponse.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineSkinResponseTest {

    private static String skinJson() {
        return "{\"uuid\":\"u1\",\"texture\":{\"data\":{\"value\":\"VAL\",\"signature\":\"SIG\"},"
                + "\"url\":{\"skin\":\"http://textures.minecraft.net/texture/x\"}}}";
    }

    @Test
    void completedWithSkinInResponse() {
        MineSkinResponse response = MineSkinResponse.parse(200,
                "{\"success\":true,\"job\":{\"id\":\"j1\",\"status\":\"completed\",\"result\":\"u1\"},\"skin\":" + skinJson() + "}");
        assertEquals(Kind.COMPLETED, response.kind());
        assertEquals("VAL", response.property().value());
        assertEquals("SIG", response.property().signature());
    }

    @Test
    void queuedJobMeansPending() {
        MineSkinResponse response = MineSkinResponse.parse(202,
                "{\"success\":true,\"job\":{\"id\":\"j1\",\"status\":\"waiting\"}}");
        assertEquals(Kind.PENDING, response.kind());
        assertEquals("j1", response.jobId());
    }

    @Test
    void activeJobMeansPending() {
        assertEquals(Kind.PENDING, MineSkinResponse.parse(200,
                "{\"job\":{\"id\":\"j1\",\"status\":\"active\"}}").kind());
    }

    @Test
    void completedJobWithoutSkinNeedsLookup() {
        MineSkinResponse response = MineSkinResponse.parse(200,
                "{\"success\":true,\"job\":{\"id\":\"j1\",\"status\":\"completed\",\"result\":\"u1\"}}");
        assertEquals(Kind.SKIN_LOOKUP, response.kind());
        assertEquals("u1", response.skinUuid());
    }

    @Test
    void skinLookupResponseWithoutWrapper() {
        MineSkinResponse response = MineSkinResponse.parse(200, skinJson());
        assertEquals(Kind.COMPLETED, response.kind());
        assertEquals("VAL", response.property().value());
    }

    @Test
    void failedJobCarriesMessage() {
        MineSkinResponse response = MineSkinResponse.parse(200,
                "{\"success\":true,\"job\":{\"id\":\"j1\",\"status\":\"failed\"},"
                        + "\"errors\":[{\"code\":\"invalid_image\",\"message\":\"Invalid image size\"}]}");
        assertEquals(Kind.FAILED, response.kind());
        assertEquals("Invalid image size", response.message());
    }

    @Test
    void badRequestIsFailedWithMessage() {
        MineSkinResponse response = MineSkinResponse.parse(400,
                "{\"success\":false,\"errors\":[{\"code\":\"invalid_url\"}]}");
        assertEquals(Kind.FAILED, response.kind());
        assertEquals("invalid_url", response.message());
    }

    @Test
    void rateLimitAndAuth() {
        assertEquals(Kind.RATE_LIMITED, MineSkinResponse.parse(429, "{}").kind());
        assertEquals(Kind.AUTH_ERROR, MineSkinResponse.parse(401, "{}").kind());
        assertEquals(Kind.AUTH_ERROR, MineSkinResponse.parse(403, "").kind());
    }

    @Test
    void garbageBodyFails() {
        MineSkinResponse response = MineSkinResponse.parse(502, "<html>Bad gateway</html>");
        assertEquals(Kind.FAILED, response.kind());
        assertNull(response.property());
        assertNotNull(response.message());
    }

    @Test
    void emptyObjectFails() {
        assertTrue(MineSkinResponse.parse(200, "{}").kind() == Kind.FAILED);
    }
}
