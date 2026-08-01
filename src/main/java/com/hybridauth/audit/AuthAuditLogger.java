package com.hybridauth.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AuthAuditLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("HybridAuth");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Path logFile;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hybridauth-audit-writer");
        thread.setDaemon(true);
        return thread;
    });

    public AuthAuditLogger(Path configDir) {
        this.logFile = configDir.resolve("hybridauth").resolve("logs").resolve("auth.log");
    }

    public void log(String event, String username, UUID uuid, String ipAddress, String details) {
        String line = String.join(" | ",
                OffsetDateTime.now().format(TIMESTAMP_FORMAT),
                sanitize(event),
                "user=" + sanitize(username),
                "uuid=" + (uuid == null ? "-" : uuid),
                "ip=" + sanitize(ipAddress),
                "details=" + sanitize(details)) + System.lineSeparator();

        writer.execute(() -> append(line));
    }

    private void append(String line) {
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(
                    logFile,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("[HybridAuth] Failed to write authentication audit log.", e);
        }
    }

    public Path getLogFile() {
        return logFile;
    }

    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace('\r', ' ').replace('\n', ' ').replace('|', '/');
    }
}
