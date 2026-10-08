package com.hybridauth.skin;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Проверки запросов {@code /skin}: ссылка, аргументы команды, время ожидания между запросами. */
public final class SkinRequestRules {

    public static final int MAX_URL_LENGTH = 512;

    private SkinRequestRules() {
    }

    public enum UrlProblem {
        EMPTY,
        TOO_LONG,
        BAD_SCHEME,
        BAD_HOST,
        DOMAIN_NOT_ALLOWED
    }

    /** Аргументы {@code /skin url}: ссылка и, необязательно, модель после неё. */
    public record UrlArgs(String url, SkinVariant variant) {
    }

    /**
     * Делит остаток команды на ссылку и модель. Если последнее слово — classic, slim или auto,
     * это модель, иначе весь текст считается ссылкой и модель определяется автоматически.
     */
    public static UrlArgs parseUrlArgs(String text) {
        String trimmed = text == null ? "" : text.trim();
        int space = trimmed.lastIndexOf(' ');
        if (space > 0) {
            Optional<SkinVariant> variant = SkinVariant.parse(trimmed.substring(space + 1));
            if (variant.isPresent()) {
                return new UrlArgs(trimmed.substring(0, space).trim(), variant.get());
            }
        }
        return new UrlArgs(trimmed, SkinVariant.AUTO);
    }

    /**
     * Проверяет ссылку до отправки в MineSkin. Сервис сам скачивает картинку, но адреса внутренней
     * сети, localhost и IP-литералы отсекаем сразу.
     *
     * @param allowedDomains пустой список — любые домены; иначе точное имя или {@code *.example.com}
     */
    public static Optional<UrlProblem> checkUrl(String url, List<? extends String> allowedDomains) {
        if (url == null || url.isBlank()) {
            return Optional.of(UrlProblem.EMPTY);
        }
        if (url.length() > MAX_URL_LENGTH) {
            return Optional.of(UrlProblem.TOO_LONG);
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (java.net.URISyntaxException e) {
            return Optional.of(UrlProblem.BAD_SCHEME);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return Optional.of(UrlProblem.BAD_SCHEME);
        }
        String host = uri.getHost();
        if (host == null || !isPublicHostName(host)) {
            return Optional.of(UrlProblem.BAD_HOST);
        }
        if (!allowedDomains.isEmpty() && !isDomainAllowed(host, allowedDomains)) {
            return Optional.of(UrlProblem.DOMAIN_NOT_ALLOWED);
        }
        return Optional.empty();
    }

    /** Имя вида {@code example.com}: с точкой, не localhost, не IP-адрес и не внутренний суффикс. */
    static boolean isPublicHostName(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local")
                || lower.endsWith(".internal") || lower.endsWith(".lan")) {
            return false;
        }
        if (lower.contains(":") || lower.startsWith("[")) {
            return false;
        }
        if (!lower.contains(".")) {
            return false;
        }
        // Четыре числовые части — IPv4-литерал; всё, что состоит только из цифр и точек, тоже отсекаем
        return !lower.chars().allMatch(c -> Character.isDigit(c) || c == '.');
    }

    static boolean isDomainAllowed(String host, List<? extends String> allowedDomains) {
        String lower = host.toLowerCase(Locale.ROOT);
        for (String entry : allowedDomains) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String domain = entry.trim().toLowerCase(Locale.ROOT);
            if (domain.startsWith("*.")) {
                if (lower.endsWith(domain.substring(1))) {
                    return true;
                }
            } else if (lower.equals(domain)) {
                return true;
            }
        }
        return false;
    }

    /** Сколько секунд осталось ждать; 0 — можно выполнять. */
    public static int cooldownRemainingSeconds(long lastUseMillis, long nowMillis, int cooldownSeconds) {
        if (lastUseMillis <= 0 || cooldownSeconds <= 0) {
            return 0;
        }
        long remainingMillis = lastUseMillis + cooldownSeconds * 1000L - nowMillis;
        if (remainingMillis <= 0) {
            return 0;
        }
        return (int) ((remainingMillis + 999) / 1000);
    }
}
