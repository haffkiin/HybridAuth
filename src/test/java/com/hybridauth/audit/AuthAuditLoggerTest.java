package com.hybridauth.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AuthAuditLoggerTest {

    private static final Pattern ROTATED_NAME = Pattern.compile("auth-\\d{8}-\\d{6}\\.log");

    @Test
    void writesLinesToFile(@TempDir Path tempDir) throws IOException {
        AuthAuditLogger logger = new AuthAuditLogger(tempDir, 1024, 5);
        logger.log("LOGIN_SUCCESS", "Steve", null, "1.2.3.4", "method=PASSWORD");
        logger.close();

        String content = Files.readString(tempDir.resolve("hybridauth").resolve("logs").resolve("auth.log"));
        assertTrue(content.contains("LOGIN_SUCCESS"));
        assertTrue(content.contains("user=Steve"));
    }

    @Test
    void rotatesWhenSizeExceededAndKeepsAtMostMaxFiles(@TempDir Path tempDir) throws IOException, InterruptedException {
        // 1 КБ лимит, максимум 2 архива
        AuthAuditLogger logger = new AuthAuditLogger(tempDir, 1024, 2);
        String longDetails = "x".repeat(200);
        for (int i = 0; i < 100; i++) {
            logger.log("LOGIN_FAILURE", "Steve" + i, null, "1.2.3.4", "details=" + longDetails);
        }
        logger.close();

        Path logDir = tempDir.resolve("hybridauth").resolve("logs");
        assertTrue(Files.exists(logDir.resolve("auth.log")), "Текущий лог должен существовать после ротаций");

        long rotatedCount;
        try (Stream<Path> files = Files.list(logDir)) {
            rotatedCount = files.filter(p -> ROTATED_NAME.matcher(p.getFileName().toString()).matches()).count();
        }
        assertTrue(rotatedCount > 0, "При превышении размера должна произойти ротация");
        assertTrue(rotatedCount <= 2, "Архивов не должно быть больше maxLogFiles, было: " + rotatedCount);

        String current = Files.readString(logDir.resolve("auth.log"));
        assertFalse(current.isBlank(), "После ротации текущий лог снова пишется");
    }

    @Test
    void noRotationBelowSizeLimit(@TempDir Path tempDir) throws IOException {
        AuthAuditLogger logger = new AuthAuditLogger(tempDir, 1024, 5);
        logger.log("CONNECTION", "Steve", null, "1.2.3.4", "registered=false");
        logger.close();

        Path logDir = tempDir.resolve("hybridauth").resolve("logs");
        try (Stream<Path> files = Files.list(logDir)) {
            assertEquals(1, files.count(), "Без превышения лимита должен быть только auth.log");
        }
    }
}
