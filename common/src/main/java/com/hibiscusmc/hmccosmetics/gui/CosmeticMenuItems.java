package com.hibiscusmc.hmccosmetics.gui;

import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.gui.type.Type;
import com.hibiscusmc.hmccosmetics.gui.type.Types;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import me.lojosho.hibiscuscommons.config.serializer.ItemSerializer;
import me.lojosho.shaded.configurate.CommentedConfigurationNode;
import me.lojosho.shaded.configurate.ConfigurationNode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.logging.Level;

/**
 * Builds the menu item for a cosmetic from the display data in the cosmetic's config.
 */
public final class CosmeticMenuItems {

    private CosmeticMenuItems() {
    }

    public static boolean isMenuEnabled(@NotNull Cosmetic cosmetic) {
        ConfigurationNode config = cosmetic.getConfig();
        if (config == null) return true;
        return config.node("menu", "enabled").getBoolean(true);
    }

    @Nullable
    public static ConfigurationNode displaySource(@NotNull Cosmetic cosmetic) {
        ConfigurationNode config = cosmetic.getConfig();
        if (config == null) return null;

        ConfigurationNode menuItem = config.node("menu", "item");
        if (!menuItem.virtual()) return menuItem;

        ConfigurationNode item = config.node("item");
        return item.virtual() ? null : item;
    }

    /**
     * Creates the menu item used for a cosmetic. The returned item still has to be processed by
     * {@link Type#setItem} (placeholders, equipped/locked variants, etc.).
     *
     * @param cosmetic The cosmetic to display
     * @param slot The menu slot the item will occupy
     * @return The menu item, or null if the cosmetic has no usable display item
     */
    @Nullable
    public static MenuItem createMenuItem(@NotNull Cosmetic cosmetic, int slot) {
        ConfigurationNode config = cosmetic.getConfig();
        ConfigurationNode sourceItem = displaySource(cosmetic);
        if (config == null || sourceItem == null) return null;

        ConfigurationNode itemConfig = CommentedConfigurationNode.root();
        try {
            itemConfig.node("cosmetic").set(cosmetic.getId());
            itemConfig.node("item").from(sourceItem);

            ConfigurationNode menuNode = config.node("menu");
            if (!menuNode.virtual()) {
                ConfigurationNode menuName = menuNode.node("name");
                if (!menuName.virtual()) {
                    itemConfig.node("item", "name").set(menuName.getString());
                }

                ConfigurationNode menuLore = menuNode.node("lore");
                if (!menuLore.virtual()) {
                    List<String> lore = menuLore.getList(String.class);
                    if (lore != null) {
                        itemConfig.node("item", "lore").setList(String.class, lore);
                    }
                }

                ConfigurationNode equippedItem = menuNode.node("equipped-item");
                if (!equippedItem.virtual()) {
                    itemConfig.node("equipped-item").from(equippedItem);
                }

                ConfigurationNode lockedItem = menuNode.node("locked-item");
                if (!lockedItem.virtual()) {
                    itemConfig.node("locked-item").from(lockedItem);
                }
            }

            ItemStack itemStack = ItemSerializer.INSTANCE.deserialize(ItemStack.class, itemConfig.node("item"));
            if (itemStack == null || itemStack.getType() == Material.AIR) return null;

            Type type = Types.getType("cosmetic");
            if (type == null) return null;

            return new MenuItem(List.of(slot), itemStack, type, 1, itemConfig);
        } catch (Exception e) {
            MessagesUtil.sendDebugMessages("Unable to create menu item for cosmetic " + cosmetic.getId() + ": " + e.getMessage(), Level.WARNING);
            return null;
        }
    }
}
