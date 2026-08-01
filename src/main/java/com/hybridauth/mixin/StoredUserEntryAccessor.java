package com.hybridauth.mixin;

import net.minecraft.server.players.StoredUserEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StoredUserEntry.class)
public interface StoredUserEntryAccessor {
    @Accessor("user")
    Object hybridauth$getUser();
}
