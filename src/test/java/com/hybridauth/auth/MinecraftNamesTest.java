package com.hybridauth.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftNamesTest {
    @Test
    void acceptsStandardNames() {
        assertTrue(MinecraftNames.isValid("Player_123"));
        assertTrue(MinecraftNames.isValid("abc"));
        assertTrue(MinecraftNames.isValid("1234567890123456"));
    }

    @Test
    void rejectsInvalidNames() {
        assertFalse(MinecraftNames.isValid("ab"));
        assertFalse(MinecraftNames.isValid("player-name"));
        assertFalse(MinecraftNames.isValid("русский"));
        assertFalse(MinecraftNames.isValid(" player "));
        assertFalse(MinecraftNames.isValid("12345678901234567"));
    }
}
