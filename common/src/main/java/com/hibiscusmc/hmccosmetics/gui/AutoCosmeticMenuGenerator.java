package com.hibiscusmc.hmccosmetics.gui;

import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import me.lojosho.shaded.configurate.ConfigurationNode;
import me.lojosho.shaded.configurate.serialize.SerializationException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Builds the pages of a menu that uses the {@code cosmetic-layout} section.
 * <p>
 * The menu file only defines the layout (which slots are used for cosmetics). Every matching
 * cosmetic is placed into those slots ordered by its priority, and additional pages are generated
 * automatically as {@code <menu_id>_2}, {@code <menu_id>_3}, ...
 * <p>
 * Page contents are resolved per player when the menu is opened, so owned/unowned filtering can
 * change how many pages a player effectively sees.
 */
public final class AutoCosmeticMenuGenerator {

    public static final String LAYOUT_NODE = "cosmetic-layout";

    private AutoCosmeticMenuGenerator() {
    }

    /**
     * Generates every page for an automatic cosmetic menu and registers it in {@link Menus}.
     *
     * @param baseId The id of the base menu (page 1)
     * @param source The loaded menu configuration
     * @return The ids of all generated pages, including the base id
     */
    public static List<String> generate(@NotNull String baseId, @NotNull ConfigurationNode source) {
        final ConfigurationNode layoutNode = source.node(LAYOUT_NODE);
        final CosmeticLayout layout = CosmeticLayout.parse(layoutNode);

        if (layout == null) {
            new Menu(baseId, source);
            return List.of(baseId);
        }

        if (layout.pageSize() == 0) {
            MessagesUtil.sendDebugMessages("Menu " + baseId + " has a cosmetic-layout but no valid slots. The menu will be empty.", Level.WARNING);
        }

        final int totalPages = layout.totalPages(layout.eligibleCosmetics().size());
        final List<String> generatedIds = new ArrayList<>(totalPages);

        for (int page = 1; page <= totalPages; page++) {
            final String pageId = page == 1 ? baseId : baseId + "_" + page;

            ConfigurationNode pageRoot;
            try {
                pageRoot = source.copy();
                pageRoot.node("id").set(pageId);
                applyTitlePlaceholders(pageRoot, page, totalPages);
                pageRoot.removeChild(LAYOUT_NODE);

                warnAboutExplicitCosmetics(pageId, pageRoot);
                removeExplicitCosmetics(pageRoot);
            } catch (Exception e) {
                MessagesUtil.sendDebugMessages("Unable to generate page " + pageId + " for menu " + baseId + ": " + e.getMessage(), Level.WARNING);
                if (Settings.isDebugMode()) e.printStackTrace();
                continue;
            }

            new Menu(pageId, pageRoot, layout, baseId, page);
            generatedIds.add(pageId);
        }

        return generatedIds;
    }

    private static void applyTitlePlaceholders(@NotNull ConfigurationNode pageRoot, int page, int totalPages) {
        String title = pageRoot.node("title").getString();
        if (title == null) return;

        title = title.replace("%page%", String.valueOf(page))
                .replace("%pages%", String.valueOf(totalPages));
        try {
            pageRoot.node("title").set(title);
        } catch (SerializationException e) {
            MessagesUtil.sendDebugMessages("Unable to set generated menu title: " + e.getMessage(), Level.WARNING);
        }
    }

    private static void warnAboutExplicitCosmetics(@NotNull String pageId, @NotNull ConfigurationNode pageRoot) {
        for (ConfigurationNode item : pageRoot.node("items").childrenMap().values()) {
            if ("cosmetic".equalsIgnoreCase(item.node("type").getString(""))) {
                MessagesUtil.sendDebugMessages("Menu " + pageId + " has explicit cosmetic items. They are ignored because cosmetic-layout is enabled.", Level.WARNING);
                return;
            }
        }
    }

    private static void removeExplicitCosmetics(@NotNull ConfigurationNode pageRoot) {
        ConfigurationNode itemsNode = pageRoot.node("items");
        List<Object> keys = new ArrayList<>(itemsNode.childrenMap().keySet());
        for (Object key : keys) {
            ConfigurationNode item = itemsNode.node(key);
            if ("cosmetic".equalsIgnoreCase(item.node("type").getString(""))) {
                itemsNode.removeChild(key);
            }
        }
    }
}
