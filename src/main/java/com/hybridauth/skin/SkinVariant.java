package com.hybridauth.skin;

import java.util.Locale;
import java.util.Optional;

/** Модель скина: классическая (Steve, широкие руки) или тонкая (Alex). */
public enum SkinVariant {
    CLASSIC("classic"),
    SLIM("slim"),
    /** Определяется сервисом по самому изображению. */
    AUTO("unknown");

    private final String apiName;

    SkinVariant(String apiName) {
        this.apiName = apiName;
    }

    /** Значение поля {@code variant} в запросе к MineSkin. */
    public String apiName() {
        return apiName;
    }

    /** Разбор слова из команды: classic, slim, auto (без учёта регистра). */
    public static Optional<SkinVariant> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "classic", "steve", "wide", "normal" -> Optional.of(CLASSIC);
            case "slim", "alex", "thin" -> Optional.of(SLIM);
            case "auto" -> Optional.of(AUTO);
            default -> Optional.empty();
        };
    }

    /** Значение {@code metadata.model} из текстуры Mojang: {@code slim} или отсутствует (classic). */
    public static SkinVariant fromTextureModel(String model) {
        return "slim".equalsIgnoreCase(model) ? SLIM : CLASSIC;
    }
}
