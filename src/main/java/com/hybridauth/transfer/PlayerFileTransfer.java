package com.hybridauth.transfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/**
 * Файлы мира одного игрока: playerdata (.dat, .dat_old), stats и advancements.
 * Всё, что относится к UUID, переносится здесь. Откат восстанавливает файлы из бэкапа.
 */
final class PlayerFileTransfer {

    private static final String DAT = ".dat";
    private static final String DAT_OLD = ".dat_old";
    private static final String JSON = ".json";

    private final Path playerDataDir;
    private final Path statsDir;
    private final Path advancementsDir;

    PlayerFileTransfer(Path playerDataDir, Path statsDir, Path advancementsDir) {
        this.playerDataDir = playerDataDir;
        this.statsDir = statsDir;
        this.advancementsDir = advancementsDir;
    }

    /** Есть ли у UUID хоть один файл мира. Используется, чтобы не затереть данные нового ника. */
    boolean hasWorldData(UUID id) {
        for (Path file : filesOf(id)) {
            if (Files.exists(file)) {
                return true;
            }
        }
        return false;
    }

    /** Копирует существующие файлы игрока в backupDir, сохраняя имена папок. */
    void backup(UUID id, Path backupDir) throws IOException {
        for (Path file : filesOf(id)) {
            if (Files.isRegularFile(file)) {
                Path target = backupDir.resolve(file.getParent().getFileName()).resolve(file.getFileName());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Переносит файлы со старого UUID на новый. Новый UUID должен быть свободен
     * (проверяется до вызова). Файлы старого UUID удаляются только после успешной записи.
     */
    void move(UUID from, UUID to) throws IOException {
        Path datFrom = playerDataDir.resolve(from + DAT);
        if (Files.isRegularFile(datFrom)) {
            CompoundTag tag = NbtIo.readCompressed(datFrom, NbtAccounter.unlimitedHeap());
            tag.putUUID("UUID", to);
            writeAtomically(tag, playerDataDir.resolve(to + DAT));
            Files.delete(datFrom);
        }
        moveIfExists(playerDataDir.resolve(from + DAT_OLD), playerDataDir.resolve(to + DAT_OLD));
        moveIfExists(statsDir.resolve(from + JSON), statsDir.resolve(to + JSON));
        moveIfExists(advancementsDir.resolve(from + JSON), advancementsDir.resolve(to + JSON));
    }

    /**
     * Откат: файлы старого UUID возвращаются из бэкапа, файлы нового UUID удаляются.
     * Новый UUID до переноса файлов не имел, поэтому удаление безопасно.
     */
    void restore(UUID from, UUID to, Path backupDir) throws IOException {
        for (Path file : filesOf(from)) {
            Path backup = backupDir.resolve(file.getParent().getFileName()).resolve(file.getFileName());
            if (Files.isRegularFile(backup)) {
                Files.createDirectories(file.getParent());
                Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(file);
            }
        }
        for (Path file : filesOf(to)) {
            Files.deleteIfExists(file);
        }
    }

    private List<Path> filesOf(UUID id) {
        return List.of(
                playerDataDir.resolve(id + DAT),
                playerDataDir.resolve(id + DAT_OLD),
                statsDir.resolve(id + JSON),
                advancementsDir.resolve(id + JSON));
    }

    private static void moveIfExists(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.move(source, target);
        }
    }

    private static void writeAtomically(CompoundTag tag, Path target) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".accounttransfer.tmp");
        NbtIo.writeCompressed(tag, temporary);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target);
        }
    }
}
