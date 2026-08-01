package com.hybridauth.auth;

import java.util.regex.Pattern;

public final class MinecraftNames {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private MinecraftNames() {
    }

    public static boolean isValid(String name) {
        return name != null && VALID.matcher(name).matches();
    }
}
