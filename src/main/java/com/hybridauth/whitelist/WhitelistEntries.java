package com.hybridauth.whitelist;

import com.hybridauth.mixin.StoredUserEntryAccessor;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.players.UserWhiteListEntry;

final class WhitelistEntries {
    private WhitelistEntries() {
    }

    static GameProfile profile(UserWhiteListEntry entry) {
        return (GameProfile) ((StoredUserEntryAccessor) entry).hybridauth$getUser();
    }
}
