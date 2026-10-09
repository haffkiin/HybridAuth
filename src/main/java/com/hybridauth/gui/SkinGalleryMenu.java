package com.hybridauth.gui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Меню-сундук без возможности брать предметы: клик по слоту только вызывает действие.
 * Слоты инвентаря игрока в меню не обрабатываются, предметы переложить нельзя.
 */
public final class SkinGalleryMenu extends ChestMenu {

    private static final int SLOTS = 54;

    private final Map<Integer, Consumer<ServerPlayer>> actions;
    private boolean actionSubmitted;

    public SkinGalleryMenu(int containerId, Inventory inventory, Container container,
                           Map<Integer, Consumer<ServerPlayer>> actions) {
        super(MenuType.GENERIC_9x6, containerId, inventory, container, 6);
        this.actions = actions;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || slotId < 0 || slotId >= SLOTS) {
            return;
        }
        setCarried(ItemStack.EMPTY);
        Consumer<ServerPlayer> action = actions.get(slotId);
        if (action != null && !actionSubmitted) {
            // Одно действие на открытие: повторные клики, пока меню закрывается, игнорируются
            actionSubmitted = true;
            serverPlayer.getServer().execute(() -> action.accept(serverPlayer));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotId) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
