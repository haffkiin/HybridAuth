package com.hybridauth.skin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinTexturesAndVariantTest {

    @Test
    void inspect_classicByDefault() {
        SkinTextures.Info info = SkinTextures.inspect(SkinTestData.validValue()).orElseThrow();
        assertEquals("http://textures.minecraft.net/texture/abc123", info.skinUrl());
        assertEquals(SkinVariant.CLASSIC, info.variant());
    }

    @Test
    void inspect_slimModel() {
        assertEquals(SkinVariant.SLIM, SkinTextures.inspect(SkinTestData.validValue("slim")).orElseThrow().variant());
    }

    @Test
    void inspect_rejectsForeignHost() {
        String value = SkinTestData.texturesValue("http://evil.example.com/texture/abc", null);
        assertEquals(Optional.empty(), SkinTextures.inspect(value));
        // Похожее имя, не совпадающее с доменом Mojang
        String lookalike = SkinTestData.texturesValue("http://textures.minecraft.net.evil.com/a", null);
        assertEquals(Optional.empty(), SkinTextures.inspect(lookalike));
    }

    @Test
    void inspect_rejectsGarbage() {
        assertEquals(Optional.empty(), SkinTextures.inspect(null));
        assertEquals(Optional.empty(), SkinTextures.inspect(""));
        assertEquals(Optional.empty(), SkinTextures.inspect("not base64 !!"));
        String noSkin = Base64.getEncoder().encodeToString(
                "{\"textures\":{\"CAPE\":{\"url\":\"http://textures.minecraft.net/x\"}}}".getBytes(StandardCharsets.UTF_8));
        assertEquals(Optional.empty(), SkinTextures.inspect(noSkin));
    }

    @Test
    void variant_parse() {
        assertEquals(Optional.of(SkinVariant.SLIM), SkinVariant.parse("SLIM"));
        assertEquals(Optional.of(SkinVariant.SLIM), SkinVariant.parse("alex"));
        assertEquals(Optional.of(SkinVariant.CLASSIC), SkinVariant.parse("Classic"));
        assertEquals(Optional.of(SkinVariant.AUTO), SkinVariant.parse("auto"));
        assertEquals(Optional.empty(), SkinVariant.parse("wide"));
        assertEquals(Optional.empty(), SkinVariant.parse(null));
        assertEquals("unknown", SkinVariant.AUTO.apiName());
        assertTrue(SkinVariant.fromTextureModel("slim") == SkinVariant.SLIM);
        assertTrue(SkinVariant.fromTextureModel(null) == SkinVariant.CLASSIC);
    }
}
