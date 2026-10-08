package com.hybridauth.skin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Общие данные для тестов скинов. */
final class SkinTestData {

    private SkinTestData() {
    }

    /** base64-значение свойства textures со скином на адресе Mojang. */
    static String texturesValue(String url, String model) {
        String metadata = model == null ? "" : ",\"metadata\":{\"model\":\"" + model + "\"}";
        String json = "{\"timestamp\":1,\"profileId\":\"x\",\"profileName\":\"Test\",\"textures\":{\"SKIN\":{\"url\":\""
                + url + "\"" + metadata + "}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static String validValue() {
        return texturesValue("http://textures.minecraft.net/texture/abc123", null);
    }

    static String validValue(String model) {
        return texturesValue("http://textures.minecraft.net/texture/abc123", model);
    }
}
