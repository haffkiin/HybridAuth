package com.hybridauth.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к отслеживаемым сущностям чанк-мапы: нужен, чтобы пересоздать игрока у наблюдателей при смене скина. */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("entityMap")
    Int2ObjectMap<TrackedEntityAccessor> hybridauth$getEntityMap();
}
