package com.hybridauth.skin;

/**
 * Свойство {@code textures} профиля игрока: base64-JSON с адресом скина и подпись Mojang.
 * Не зависит от authlib, чтобы тестироваться без Minecraft.
 */
public record SkinProperty(String value, String signature) {

    public SkinProperty {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value");
        }
    }
}
