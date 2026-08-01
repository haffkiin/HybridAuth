package com.hybridauth.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class OfflineUuid {
    private OfflineUuid() {
    }

    public static UUID forName(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
