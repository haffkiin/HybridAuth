package com.hybridauth.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Аудит-лог с ротацией: при превышении maxLogSizeBytes файл переименовывается
 * в auth-<timestamp>.log, старые архивы сверх maxLogFiles удаляются.
 */
public class AuthAuditLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("HybridAuth");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter ROTATION_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern ROTATED_NAME = Pattern.compile("auth-\\d{8}-\\d{6}\\.log");

    private static final long DEFAULT_MAX_LOG_SIZE_BYTES = 5L * 1024 * 1024;
    private static final int DEFAULT_MAX_LOG_FILES = 5;

    private final Path logFile;
    private final Path logDir;
    private final long maxLogSizeBytes;
    private final int maxLogFiles;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hybridauth-audit-writer");
        thread.setDaemon(true);
        return thread;
    });

    public AuthAuditLogger(Path configDir) {
        this(configDir, DEFAULT_MAX_LOG_SIZE_BYTES, DEFAULT_MAX_LOG_FILES);
    }

    /** Package-private constructor for tests — allows injecting small rotation limits. */
    AuthAuditLogger(Path configDir, long maxLogSizeBytes, int maxLogFiles) {
        this.logDir = configDir.resolve("hybridauth").resolve("logs");
        this.logFile = logDir.resolve("auth.log");
        this.maxLogSizeBytes = Math.max(1024, maxLogSizeBytes);
        this.maxLogFiles = Math.max(1, maxLogFiles);
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
            Files.createDirectories(logDir);
            rotateIfNeeded();
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

    /** Вызывается только из writer-потока. */
    private void rotateIfNeeded() throws IOException {
        if (!Files.exists(logFile) || Files.size(logFile) < maxLogSizeBytes) {
            return;
        }

        String rotatedName = "auth-" + ROTATION_FORMAT.format(OffsetDateTime.now()) + ".log";
        Path rotated = logDir.resolve(rotatedName);
        Files.move(logFile, rotated, StandardCopyOption.REPLACE_EXISTING);
        deleteOldRotations();
    }

    private void deleteOldRotations() throws IOException {
        if (!Files.isDirectory(logDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(logDir)) {
            Path[] rotated = files
                    .filter(path -> ROTATED_NAME.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toArray(Path[]::new);
            int excess = rotated.length - maxLogFiles;
            for (int i = 0; i < excess; i++) {
                Files.deleteIfExists(rotated[i]);
            }
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
