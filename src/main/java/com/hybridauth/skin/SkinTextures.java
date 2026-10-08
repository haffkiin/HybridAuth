package com.hybridauth.skin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Разбор значения свойства {@code textures}. Клиент принимает текстуры только с доменов Mojang,
 * поэтому в хранилище попадает лишь то, что действительно указывает на {@code textures.minecraft.net}.
 */
public final class SkinTextures {

    private static final String TEXTURE_HOST = "textures.minecraft.net";

    private SkinTextures() {
    }

    /** @param skinUrl адрес PNG скина */
    public record Info(String skinUrl, SkinVariant variant) {
    }

    /** Адрес и модель скина из base64-значения; пусто, если это не корректная текстура скина Mojang. */
    public static Optional<Info> inspect(String base64Value) {
        if (base64Value == null || base64Value.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = new String(Base64.getDecoder().decode(base64Value), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("textures")) {
                return Optional.empty();
            }
            JsonObject textures = root.getAsJsonObject("textures");
            if (!textures.has("SKIN")) {
                return Optional.empty();
            }
            JsonObject skin = textures.getAsJsonObject("SKIN");
            if (!skin.has("url")) {
                return Optional.empty();
            }
            String url = skin.get("url").getAsString();
            URI uri = URI.create(url);
            if (!isTextureHost(uri)) {
                return Optional.empty();
            }
            String model = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
                    ? skin.getAsJsonObject("metadata").get("model").getAsString()
                    : null;
            return Optional.of(new Info(url, SkinVariant.fromTextureModel(model)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static boolean isTextureHost(URI uri) {
        String scheme = uri.getScheme();
        return ("http".equals(scheme) || "https".equals(scheme)) && TEXTURE_HOST.equalsIgnoreCase(uri.getHost());
    }
}
