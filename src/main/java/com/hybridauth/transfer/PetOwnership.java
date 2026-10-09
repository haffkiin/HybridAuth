package com.hybridauth.transfer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Передаёт приручённых животных (волки, кошки, попугаи, лошади и т. п.) новому аккаунту при переносе.
 * У загруженных сейчас питомцев владелец меняется сразу, у остальных в момент загрузки чанка
 * ({@link #applyOnLoad}), потому что в данных выгруженных чанков питомцев искать нельзя.
 */
public final class PetOwnership {

    private final OwnerRedirects redirects;

    public PetOwnership(OwnerRedirects redirects) {
        this.redirects = redirects;
    }

    /** Результат переноса: сколько питомцев сменили владельца сразу и действие отката. */
    public record Moved(int loadedNow, Runnable undo) {
    }

    /** Должен вызываться на потоке сервера. */
    public Moved move(MinecraftServer server, UUID from, UUID to) {
        Map<String, String> before = redirects.snapshot();
        redirects.add(from, to);

        List<Entity> changed = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (from.equals(ownerOf(entity)) && setOwner(entity, to)) {
                    changed.add(entity);
                }
            }
        }

        Runnable undo = () -> {
            redirects.restore(before);
            for (Entity entity : changed) {
                // Питомец мог выгрузиться: у него владелец вернётся сам, так как таблица восстановлена
                if (entity.isAlive() && to.equals(ownerOf(entity))) {
                    setOwner(entity, from);
                }
            }
        };
        return new Moved(changed.size(), undo);
    }

    /** Вызывается при появлении сущности в мире: меняет владельца, если он переехал. */
    public void applyOnLoad(Entity entity) {
        if (redirects.isEmpty() || entity.level().isClientSide || !(entity instanceof OwnableEntity)) {
            return;
        }
        UUID owner = ownerOf(entity);
        if (owner == null) {
            return;
        }
        UUID resolved = redirects.resolve(owner);
        if (!resolved.equals(owner)) {
            setOwner(entity, resolved);
        }
    }

    private static UUID ownerOf(Entity entity) {
        return entity instanceof OwnableEntity ownable ? ownable.getOwnerUUID() : null;
    }

    private static boolean setOwner(Entity entity, UUID owner) {
        if (entity instanceof TamableAnimal tamable) {
            tamable.setOwnerUUID(owner);
            return true;
        }
        if (entity instanceof AbstractHorse horse) {
            horse.setOwnerUUID(owner);
            return true;
        }
        return false;
    }
}
