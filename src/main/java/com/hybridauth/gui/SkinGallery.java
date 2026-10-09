package com.hybridauth.gui;

import com.hybridauth.HybridAuthMod;
import com.hybridauth.auth.OfflineUuid;
import com.hybridauth.commands.SkinCommands;
import com.hybridauth.config.ModConfig;
import com.hybridauth.skin.SkinEntry;
import com.hybridauth.skin.SkinService;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Меню {@code /skin gallery}: головы с настоящими скинами игроков из настройки {@code [skins] gallery}.
 * Клик по голове выбирает этот скин так же, как команда {@code /skin <ник>}.
 */
public final class SkinGallery {

    private static final int MAX_ENTRIES = 36;
    private static final int SLOT_SLIM = 46;
    private static final int SLOT_RESET = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_CLASSIC = 52;

    private SkinGallery() {
    }

    public static void open(ServerPlayer player) {
        SkinService skins = HybridAuthMod.getSkinService();
        List<? extends String> nicks = ModConfig.SERVER.skinsGallery.get();
        if (!ModConfig.SERVER.skinsEnabled.get() || !ModConfig.SERVER.skinsNickEnabled.get() || nicks.isEmpty()) {
            player.sendSystemMessage(Component.literal(colorize(ModConfig.SERVER.msgSkinDisabled.get())));
            return;
        }
        // Головы, которых ещё нет в памяти, подгружаются в фоне: при следующем открытии они будут на месте
        skins.prefetchGallery(nicks);

        Container container = new SimpleContainer(54);
        Map<Integer, Consumer<ServerPlayer>> actions = new HashMap<>();

        int slot = 0;
        for (String nick : nicks.stream().limit(MAX_ENTRIES).toList()) {
            Optional<SkinEntry> entry = skins.galleryEntry(nick);
            container.setItem(slot, headFor(nick, entry));
            actions.put(slot, clicker -> {
                clicker.closeContainer();
                SkinCommands.chooseNick(clicker, nick);
            });
            slot++;
        }

        container.setItem(SLOT_SLIM, button(Items.STICK, "§eТонкие руки", List.of(
                "§7Сделать у текущего скина тонкие руки (Alex).",
                "§7Нужен ключ MineSkin на сервере.")));
        actions.put(SLOT_SLIM, clicker -> {
            clicker.closeContainer();
            SkinCommands.chooseModel(clicker, "slim");
        });
        container.setItem(SLOT_RESET, button(Items.BARRIER, "§cСбросить скин", List.of(
                "§7Вернуть стандартный скин",
                "§7(у лицензии это скин Mojang).")));
        actions.put(SLOT_RESET, clicker -> {
            clicker.closeContainer();
            SkinCommands.chooseReset(clicker);
        });
        container.setItem(SLOT_INFO, button(Items.PAPER, "§6Свой скин из файла", List.of(
                "§7Выложите PNG на imgur.com или postimages.org,",
                "§7скопируйте прямую ссылку на картинку и введите:",
                "§e/skin url <ссылка> §7(в конце можно slim или classic)",
                "§7Подробнее: §e/skin help")));
        container.setItem(SLOT_CLASSIC, button(Items.OAK_LOG, "§eОбычные руки", List.of(
                "§7Сделать у текущего скина обычные широкие руки (Steve).",
                "§7Нужен ключ MineSkin на сервере.")));
        actions.put(SLOT_CLASSIC, clicker -> {
            clicker.closeContainer();
            SkinCommands.chooseModel(clicker, "classic");
        });

        Component title = Component.literal(colorize(ModConfig.SERVER.msgSkinGalleryTitle.get()));
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, ignored) -> new SkinGalleryMenu(id, inventory, container, actions), title));
    }

    /** Голова с настоящим скином, если он уже загружен; иначе обычная голова с подписью «загружается». */
    private static ItemStack headFor(String nick, Optional<SkinEntry> entry) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        GameProfile profile = new GameProfile(OfflineUuid.forName(nick), nick);
        entry.ifPresent(skin -> profile.getProperties().put("textures",
                new Property("textures", skin.property().value(), skin.property().signature())));
        head.set(DataComponents.PROFILE, new ResolvableProfile(profile));
        head.set(DataComponents.CUSTOM_NAME, plain("§f" + nick));
        List<String> lore = new ArrayList<>();
        if (entry.isPresent()) {
            lore.add("§7Нажмите, чтобы взять этот скин.");
        } else {
            lore.add("§7Скин загружается, откройте меню ещё раз.");
            lore.add("§7Нажатие всё равно выберет этот скин.");
        }
        head.set(DataComponents.LORE, new ItemLore(lore.stream().map(SkinGallery::plain).toList()));
        return head;
    }

    private static ItemStack button(net.minecraft.world.item.Item item, String name, List<String> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, plain(name));
        stack.set(DataComponents.LORE, new ItemLore(lore.stream().map(SkinGallery::plain).toList()));
        return stack;
    }

    /** Текст без курсива, который Minecraft по умолчанию добавляет названиям предметов. */
    private static Component plain(String text) {
        return Component.literal(colorize(text)).withStyle(style -> style.withItalic(false));
    }

    private static String colorize(String message) {
        return message.replace("&", "§").replace("\r", "").replace("\n", " ");
    }
}
