package com.hybridauth.skin;

import com.google.gson.JsonObject;

import java.util.Optional;

/**
 * Выбранный игроком скин. Хранится по UUID игрока и подставляется в профиль при каждом входе.
 *
 * @param source    откуда взят скин
 * @param argument  ник (для {@link Source#NICK}) или ссылка (для {@link Source#URL}); нужен только для показа
 * @param variant   модель скина
 * @param property  текстура с подписью Mojang
 * @param updatedAt время установки, мс
 */
public record SkinEntry(Source source, String argument, SkinVariant variant, SkinProperty property, long updatedAt) {

    public enum Source {
        NICK,
        URL
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("source", source.name().toLowerCase(java.util.Locale.ROOT));
        json.addProperty("argument", argument);
        json.addProperty("variant", variant.name().toLowerCase(java.util.Locale.ROOT));
        json.addProperty("value", property.value());
        if (property.signature() != null) {
            json.addProperty("signature", property.signature());
        }
        json.addProperty("updatedAt", updatedAt);
        return json;
    }

    /** Разбор записи из файла; повреждённая запись даёт пустой результат, а не исключение. */
    public static Optional<SkinEntry> fromJson(JsonObject json) {
        try {
            Source source = Source.valueOf(json.get("source").getAsString().toUpperCase(java.util.Locale.ROOT));
            String argument = json.has("argument") && !json.get("argument").isJsonNull()
                    ? json.get("argument").getAsString()
                    : "";
            SkinVariant variant = SkinVariant.parse(json.get("variant").getAsString()).orElse(SkinVariant.CLASSIC);
            String value = json.get("value").getAsString();
            String signature = json.has("signature") && !json.get("signature").isJsonNull()
                    ? json.get("signature").getAsString()
                    : null;
            long updatedAt = json.has("updatedAt") ? json.get("updatedAt").getAsLong() : 0L;
            return Optional.of(new SkinEntry(source, argument, variant, new SkinProperty(value, signature), updatedAt));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
