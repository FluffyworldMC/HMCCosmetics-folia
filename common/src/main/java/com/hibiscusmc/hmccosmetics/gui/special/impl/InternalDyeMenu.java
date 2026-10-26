package com.hibiscusmc.hmccosmetics.gui.special.impl;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticHolder;
import com.hibiscusmc.hmccosmetics.gui.special.DyeMenu;
import com.hibiscusmc.hmccosmetics.util.HMCCInventoryUtils;
import com.hibiscusmc.hmccosmetics.util.HMCCServerUtils;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import dev.triumphteam.gui.builder.gui.ChestGuiBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import me.lojosho.hibiscuscommons.config.serializer.ItemSerializer;
import me.lojosho.hibiscuscommons.hooks.Hooks;
import me.lojosho.hibiscuscommons.nms.NMSHandlers;
import me.lojosho.shaded.configurate.CommentedConfigurationNode;
import me.lojosho.shaded.configurate.ConfigurateException;
import me.lojosho.shaded.configurate.ConfigurationNode;
import me.lojosho.shaded.configurate.serialize.SerializationException;
import me.lojosho.shaded.configurate.yaml.YamlConfigurationLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public class InternalDyeMenu implements DyeMenu {

    private static final int MAX_ROWS = 6;
    private static final int ROW_SIZE = 9;

    private static final String DEFAULT_COLOR_NAME = "<#{hex}>{color_name} {shade_name}";
    private static final List<String> DEFAULT_COLOR_LORE = List.of(
            "<gray>Hex: <white>#{hex}",
            "<gray>Shade: <white>{shade_name}",
            "<gray>Click to preview"
    );

    private static final List<DefaultColor> DEFAULT_BASE_COLORS = List.of(
            new DefaultColor("Red", "FF0000"),
            new DefaultColor("Orange", "FFA500"),
            new DefaultColor("Yellow", "FFFF00"),
            new DefaultColor("Lime", "BFFF00"),
            new DefaultColor("Green", "00A86B"),
            new DefaultColor("Cyan", "00CED1"),
            new DefaultColor("Blue", "0000FF"),
            new DefaultColor("Purple", "800080"),
            new DefaultColor("White", "FFFFFF"),
            new DefaultColor("Gray", "808080"),
            new DefaultColor("Black", "000000"),
            new DefaultColor("Brown", "8B4513")
    );

    private List<PaletteColor> COLORS = List.of();
    private final List<Shade> SHADES = new ArrayList<>();
    private final List<Integer> PALETTE_SLOTS = new ArrayList<>();

    private int ROWS = 6;
    private int COLOR_START_SLOT = 18;
    private int CONFIGURED_PAGE_SIZE = 36;
    private int PREVIOUS_SLOT = 0;
    private int NEXT_SLOT = 8;

    private int INPUT_SLOT;
    private int OUTPUT_SLOT;

    private boolean TITLE_HAS_PAGE_PLACEHOLDER = false;

    private @Nullable ItemStack PREVIOUS_ITEM = null;
    private @Nullable ItemStack NEXT_ITEM = null;
    private @Nullable ItemStack PREVIOUS_DISABLED_ITEM = null;
    private @Nullable ItemStack NEXT_DISABLED_ITEM = null;
    private @Nullable ItemStack FILLER_ITEM = null;

    private @Nullable ItemStack COLOR_ITEM = null;
    private @Nullable String COLOR_NAME_TEMPLATE = null;
    private @Nullable List<String> COLOR_LORE_TEMPLATE = null;

    private boolean CUSTOM_COLOR_ENABLED = true;
    private int CUSTOM_COLOR_SLOT = 4;
    private String CUSTOM_COLOR_PERMISSION = "hmccosmetics.dye.custom-color";
    private @Nullable ItemStack CUSTOM_COLOR_ITEM = null;
    private String CUSTOM_COLOR_DIALOG_TITLE = "<#45CDE9>Custom Color";
    private String CUSTOM_COLOR_DIALOG_LABEL = "<gray>Hex color (e.g. #FF00AA)";
    private String CUSTOM_COLOR_DIALOG_CONFIRM = "<green>Confirm";
    private String CUSTOM_COLOR_DIALOG_CANCEL = "<red>Cancel";
    private String CUSTOM_COLOR_DIALOG_INITIAL = "#FFFFFF";
    private int CUSTOM_COLOR_DIALOG_MAX_LENGTH = 7;

    /**
     * Whether the running server exposes the Paper dialog API. Checked before any of the
     * dialog classes are touched so the plugin still loads on servers older than 1.21.7.
     */
    private static final boolean DIALOG_SUPPORTED = isDialogSupported();

    private static boolean isDialogSupported() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog", false, InternalDyeMenu.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    @Override
    public void reload() {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(Path.of(HMCCosmeticsPlugin.getInstance().getDataFolder() + "/menus/functional/internal_dye_menu.yml")).build();
        CommentedConfigurationNode config;
        try {
            config = loader.load();
        } catch (ConfigurateException e) {
            throw new RuntimeException(e);
        }

        if (!config.node("format").virtual()) {
            MessagesUtil.sendDebugMessages("internal_dye_menu.yml is using the old format, which is no longer supported. Delete plugins/HMCCosmetics/menus/functional/internal_dye_menu.yml and restart to regenerate it. Falling back to the built-in palette until then.", Level.WARNING);
        }

        ConfigurationNode menuNode = config.node("menu");
        ROWS = Math.max(1, Math.min(MAX_ROWS, menuNode.node("rows").getInt(6)));
        COLOR_START_SLOT = menuNode.node("color-start-slot").getInt(18);
        CONFIGURED_PAGE_SIZE = Math.max(1, menuNode.node("color-page-size").getInt(36));
        PREVIOUS_SLOT = menuNode.node("previous-slot").getInt(0);
        NEXT_SLOT = menuNode.node("next-slot").getInt(8);

        PREVIOUS_ITEM = deserializeRawItem(menuNode.node("previous-item"));
        if (PREVIOUS_ITEM == null) PREVIOUS_ITEM = createNavigationItem(true);
        NEXT_ITEM = deserializeRawItem(menuNode.node("next-item"));
        if (NEXT_ITEM == null) NEXT_ITEM = createNavigationItem(false);
        PREVIOUS_DISABLED_ITEM = deserializeRawItem(menuNode.node("previous-disabled"));
        if (PREVIOUS_DISABLED_ITEM == null) PREVIOUS_DISABLED_ITEM = createDisabledNavigationItem();
        NEXT_DISABLED_ITEM = deserializeRawItem(menuNode.node("next-disabled"));
        if (NEXT_DISABLED_ITEM == null) NEXT_DISABLED_ITEM = createDisabledNavigationItem();
        FILLER_ITEM = deserializeRawItem(menuNode.node("filler"));

        ConfigurationNode colorItemNode = config.node("color-item");
        COLOR_ITEM = deserializeItem(colorItemNode);

        String nameTemplate = colorItemNode.node("name").getString("");
        COLOR_NAME_TEMPLATE = nameTemplate.isBlank() ? DEFAULT_COLOR_NAME : nameTemplate;

        ConfigurationNode loreNode = colorItemNode.node("lore");
        COLOR_LORE_TEMPLATE = loreNode.virtual() ? DEFAULT_COLOR_LORE : getStringList(loreNode);

        loadShades(config.node("shades"));
        COLORS = buildPalette(config.node("base-colors"));

        INPUT_SLOT = Settings.getDyeMenuInputSlot();
        OUTPUT_SLOT = Settings.getDyeMenuOutputSlot();
        TITLE_HAS_PAGE_PLACEHOLDER = Settings.getDyeMenuName().contains("{page}")
                || Settings.getDyeMenuName().contains("{pages}")
                || Settings.getDyeMenuName().contains("{target_page}");
        if (!isValidSlot(INPUT_SLOT)) {
            MessagesUtil.sendDebugMessages("Dye menu input slot " + INPUT_SLOT + " is outside the menu. Falling back to slot 10.", Level.WARNING);
            INPUT_SLOT = Math.min(10, ROWS * ROW_SIZE - 1);
        }
        if (!isValidSlot(OUTPUT_SLOT)) {
            MessagesUtil.sendDebugMessages("Dye menu output slot " + OUTPUT_SLOT + " is outside the menu. Falling back to slot 16.", Level.WARNING);
            OUTPUT_SLOT = Math.min(16, ROWS * ROW_SIZE - 1);
        }
        if (INPUT_SLOT == OUTPUT_SLOT) {
            MessagesUtil.sendDebugMessages("Dye menu input and output slots are the same slot (" + INPUT_SLOT + "). Check your config.yml.", Level.WARNING);
        }

        loadCustomColorSettings(config.node("custom-color"));

        buildPaletteSlots(true);
        validateCustomColorSettings();
    }

    private void loadCustomColorSettings(@NotNull ConfigurationNode customColorNode) {
        CUSTOM_COLOR_ENABLED = customColorNode.node("enabled").getBoolean(true);
        CUSTOM_COLOR_SLOT = customColorNode.node("slot").getInt(4);
        CUSTOM_COLOR_PERMISSION = customColorNode.node("permission").getString("hmccosmetics.dye.custom-color");

        CUSTOM_COLOR_ITEM = null;
        if (!customColorNode.node("item").virtual()) {
            try {
                ItemStack item = ItemSerializer.INSTANCE.deserialize(ItemStack.class, customColorNode.node("item"));
                if (item != null && item.getType() != Material.AIR) {
                    CUSTOM_COLOR_ITEM = item;
                }
            } catch (SerializationException e) {
                e.printStackTrace();
            }
        }

        ConfigurationNode dialogNode = customColorNode.node("dialog");
        CUSTOM_COLOR_DIALOG_TITLE = dialogNode.node("title").getString(CUSTOM_COLOR_DIALOG_TITLE);
        CUSTOM_COLOR_DIALOG_LABEL = dialogNode.node("label").getString(CUSTOM_COLOR_DIALOG_LABEL);
        CUSTOM_COLOR_DIALOG_CONFIRM = dialogNode.node("confirm").getString(CUSTOM_COLOR_DIALOG_CONFIRM);
        CUSTOM_COLOR_DIALOG_CANCEL = dialogNode.node("cancel").getString(CUSTOM_COLOR_DIALOG_CANCEL);
        CUSTOM_COLOR_DIALOG_INITIAL = dialogNode.node("initial").getString(CUSTOM_COLOR_DIALOG_INITIAL);
        CUSTOM_COLOR_DIALOG_MAX_LENGTH = Math.max(7, dialogNode.node("max-length").getInt(7));
    }

    private void validateCustomColorSettings() {
        if (!CUSTOM_COLOR_ENABLED) return;

        if (!DIALOG_SUPPORTED) {
            CUSTOM_COLOR_ENABLED = false;
            MessagesUtil.sendDebugMessages("Dialogs are not available on this server version, so the custom color button has been disabled.", Level.WARNING);
            return;
        }

        if (!isValidSlot(CUSTOM_COLOR_SLOT)) {
            CUSTOM_COLOR_ENABLED = false;
            MessagesUtil.sendDebugMessages("Custom color slot " + CUSTOM_COLOR_SLOT + " is outside of the menu, so the custom color button has been disabled.", Level.WARNING);
            return;
        }

        if (CUSTOM_COLOR_SLOT == INPUT_SLOT
                || CUSTOM_COLOR_SLOT == OUTPUT_SLOT
                || CUSTOM_COLOR_SLOT == PREVIOUS_SLOT
                || CUSTOM_COLOR_SLOT == NEXT_SLOT
                || PALETTE_SLOTS.contains(CUSTOM_COLOR_SLOT)) {
            CUSTOM_COLOR_ENABLED = false;
            MessagesUtil.sendDebugMessages("Custom color slot " + CUSTOM_COLOR_SLOT + " overlaps another menu item, so the custom color button has been disabled.", Level.WARNING);
        }
    }

    private void buildPaletteSlots(boolean warn) {
        PALETTE_SLOTS.clear();

        int menuSize = ROWS * ROW_SIZE;
        if (!isValidSlot(COLOR_START_SLOT)) {
            if (warn) MessagesUtil.sendDebugMessages("Color start slot " + COLOR_START_SLOT + " is outside the menu. No colors will be displayed.", Level.WARNING);
            return;
        }

        boolean reserveCustomSlot = CUSTOM_COLOR_ENABLED && DIALOG_SUPPORTED && isValidSlot(CUSTOM_COLOR_SLOT);

        for (int i = 0; i < CONFIGURED_PAGE_SIZE; i++) {
            int slot = COLOR_START_SLOT + i;
            if (slot >= menuSize) {
                if (warn) MessagesUtil.sendDebugMessages("color-start-slot + color-page-size goes outside the menu, so only " + PALETTE_SLOTS.size() + " color slots are used.", Level.WARNING);
                break;
            }

            if (slot == INPUT_SLOT || slot == OUTPUT_SLOT || slot == PREVIOUS_SLOT || slot == NEXT_SLOT || (reserveCustomSlot && slot == CUSTOM_COLOR_SLOT)) {
                if (warn) MessagesUtil.sendDebugMessages("Color slot " + slot + " overlaps another menu item, so it has been skipped.", Level.WARNING);
                continue;
            }

            PALETTE_SLOTS.add(slot);
        }

        if (PALETTE_SLOTS.isEmpty() && warn) {
            MessagesUtil.sendDebugMessages("No color slots are available in the internal dye menu. Check the menu section of internal_dye_menu.yml.", Level.WARNING);
        }

        if (warn) {
            if (PREVIOUS_SLOT >= 0 && !isValidSlot(PREVIOUS_SLOT)) {
                MessagesUtil.sendDebugMessages("previous-slot " + PREVIOUS_SLOT + " is outside of the menu.", Level.WARNING);
            }
            if (NEXT_SLOT >= 0 && !isValidSlot(NEXT_SLOT)) {
                MessagesUtil.sendDebugMessages("next-slot " + NEXT_SLOT + " is outside of the menu.", Level.WARNING);
            }
            if (PREVIOUS_SLOT >= 0 && PREVIOUS_SLOT == NEXT_SLOT) {
                MessagesUtil.sendDebugMessages("previous-slot and next-slot are the same slot (" + PREVIOUS_SLOT + ").", Level.WARNING);
            }
        }
    }

    @Override
    public void openMenu(@NotNull Player viewer, @NotNull CosmeticHolder cosmeticHolder, @NotNull Cosmetic cosmetic) {
        openMenu(viewer, cosmeticHolder, cosmetic, null, 0);
    }

    private void openMenu(@NotNull Player viewer, @NotNull CosmeticHolder cosmeticHolder, @NotNull Cosmetic cosmetic, @Nullable Color presetColor, int page) {
        if (PALETTE_SLOTS.isEmpty() || COLORS.isEmpty()) {
            MessagesUtil.sendDebugMessages("The internal dye menu has no colors to display. Check menus/functional/internal_dye_menu.yml.", Level.WARNING);
            cosmeticHolder.addCosmetic(cosmetic);
            return;
        }

        ItemStack cosmeticItem = cosmetic.getItem();
        if (cosmeticItem == null) {
            cosmeticHolder.addCosmetic(cosmetic);
            return;
        }

        new DyeSession(viewer, cosmeticHolder, cosmetic, cosmeticItem.clone(), presetColor, page).open();
    }

    private final class DyeSession {

        private final Player viewer;
        private final CosmeticHolder cosmeticHolder;
        private final Cosmetic cosmetic;
        private final ItemStack cosmeticItem;
        private final AtomicReference<Color> preview = new AtomicReference<>();
        private final int lastPage;
        private final Gui gui;

        private int page;

        private DyeSession(@NotNull Player viewer,
                           @NotNull CosmeticHolder cosmeticHolder,
                           @NotNull Cosmetic cosmetic,
                           @NotNull ItemStack cosmeticItem,
                           @Nullable Color presetColor,
                           int page) {
            this.viewer = viewer;
            this.cosmeticHolder = cosmeticHolder;
            this.cosmetic = cosmetic;
            this.cosmeticItem = cosmeticItem;
            this.preview.set(presetColor);
            this.lastPage = Math.max(0, (COLORS.size() - 1) / PALETTE_SLOTS.size());
            this.page = Math.max(0, Math.min(page, lastPage));
            this.gui = buildGui();
        }

        private Gui buildGui() {
            Gui created = new ChestGuiBuilder().rows(ROWS).title(titleComponent(page)).create();
            created.setUpdating(true);
            created.setDefaultClickAction(event -> event.setCancelled(true));
            created.setPlayerInventoryAction(event -> event.setCancelled(true));
            created.setDefaultTopClickAction(event -> {
                event.setCancelled(true);
                if (event.getSlot() != OUTPUT_SLOT) return;

                Color color = preview.get();
                if (color == null) {
                    ItemStack outputItem = event.getInventory().getItem(OUTPUT_SLOT);
                    color = NMSHandlers.getHandler().getUtilHandler().getColor(outputItem);
                }

                created.close(event.getWhoClicked());
                if (color != null) cosmeticHolder.addCosmetic(cosmetic, color);
                else cosmeticHolder.addCosmetic(cosmetic);
            });

            if (CUSTOM_COLOR_ENABLED) {
                created.setItem(CUSTOM_COLOR_SLOT, createCustomColorButton());
            }

            return created;
        }

        private void open() {
            // The Triumph GUI inventory only exists after open(), so the palette must be
            // rendered afterwards (updateItem/removeItem write straight into the inventory).
            gui.open(viewer);
            render(false);
        }

        private void render(boolean refreshTitle) {
            if (refreshTitle && TITLE_HAS_PAGE_PLACEHOLDER) {
                // updateTitle recreates the inventory (and re-opens it for viewers), so it is
                // only done when the page placeholder in the title actually changes.
                gui.updateTitle(titleComponent(page));
            }

            gui.updateItem(INPUT_SLOT, new GuiItem(processItem(viewer, cosmeticItem.clone(), Map.of())));
            updateOutput();

            ItemStack filler = FILLER_ITEM;
            if (filler != null && filler.getType() == Material.AIR) filler = null;

            for (int slot : PALETTE_SLOTS) {
                if (filler == null) {
                    gui.removeItem(slot);
                } else {
                    gui.updateItem(slot, new GuiItem(processItem(viewer, filler.clone(), pagePlaceholders(page))));
                }
            }

            int startIndex = page * PALETTE_SLOTS.size();
            for (int i = 0; i < PALETTE_SLOTS.size(); i++) {
                int colorIndex = startIndex + i;
                if (colorIndex >= COLORS.size()) break;

                PaletteColor paletteColor = COLORS.get(colorIndex);
                GuiItem item = new GuiItem(buildColorItem(paletteColor));
                item.setAction(event -> {
                    event.setCancelled(true);
                    select(paletteColor);
                });
                gui.updateItem(PALETTE_SLOTS.get(i), item);
            }

            renderNavigation();
        }

        private void updateOutput() {
            ItemStack outputItem = cosmeticItem.clone();
            Color color = preview.get();
            if (color != null) {
                outputItem = NMSHandlers.getHandler().getUtilHandler().setColor(outputItem, color);
            }
            gui.updateItem(OUTPUT_SLOT, new GuiItem(processItem(viewer, outputItem, Map.of())));
        }

        private void select(@NotNull PaletteColor paletteColor) {
            preview.set(paletteColor.color());
            updateOutput();
        }

        private void renderNavigation() {
            renderNavigationButton(true);
            renderNavigationButton(false);
        }

        private void renderNavigationButton(boolean previous) {
            int slot = previous ? PREVIOUS_SLOT : NEXT_SLOT;
            if (!isValidSlot(slot)) return;

            boolean enabled = previous ? page > 0 : page < lastPage;
            ItemStack template = previous
                    ? (enabled ? PREVIOUS_ITEM : PREVIOUS_DISABLED_ITEM)
                    : (enabled ? NEXT_ITEM : NEXT_DISABLED_ITEM);

            if (template == null || template.getType() == Material.AIR) {
                gui.removeItem(slot);
                return;
            }

            int targetPage = previous ? page - 1 : page + 1;
            GuiItem item = new GuiItem(processItem(viewer, template.clone(), pagePlaceholders(targetPage)));
            if (enabled) {
                item.setAction(event -> {
                    event.setCancelled(true);
                    page = targetPage;
                    render(true);
                });
            }
            gui.updateItem(slot, item);
        }

        private GuiItem createCustomColorButton() {
            ItemStack item = CUSTOM_COLOR_ITEM != null ? CUSTOM_COLOR_ITEM.clone() : createDefaultCustomColorItem();
            GuiItem guiItem = new GuiItem(processItem(viewer, item, Map.of()));
            guiItem.setAction(event -> {
                event.setCancelled(true);

                if (!CUSTOM_COLOR_PERMISSION.isBlank()
                        && !viewer.hasPermission(CUSTOM_COLOR_PERMISSION)
                        && !viewer.isOp()) {
                    MessagesUtil.sendMessage(viewer, "no-permission");
                    return;
                }

                int returnPage = page;
                CustomColorDialog.open(
                        viewer,
                        CUSTOM_COLOR_DIALOG_TITLE,
                        CUSTOM_COLOR_DIALOG_LABEL,
                        CUSTOM_COLOR_DIALOG_CONFIRM,
                        CUSTOM_COLOR_DIALOG_CANCEL,
                        CUSTOM_COLOR_DIALOG_INITIAL,
                        CUSTOM_COLOR_DIALOG_MAX_LENGTH,
                        color -> openMenu(viewer, cosmeticHolder, cosmetic, color, returnPage)
                );
            });
            return guiItem;
        }

        private ItemStack buildColorItem(@NotNull PaletteColor paletteColor) {
            ItemStack item = COLOR_ITEM != null ? COLOR_ITEM.clone() : cosmeticItem.clone();
            item = NMSHandlers.getHandler().getUtilHandler().setColor(item, paletteColor.color());

            Map<String, String> placeholders = colorPlaceholders(paletteColor);
            ItemMeta itemMeta = item.getItemMeta();
            if (itemMeta != null) {
                if (COLOR_NAME_TEMPLATE != null) {
                    itemMeta.displayName(formatText(COLOR_NAME_TEMPLATE, placeholders));
                }
                if (COLOR_LORE_TEMPLATE != null && !COLOR_LORE_TEMPLATE.isEmpty()) {
                    List<Component> lore = new ArrayList<>(COLOR_LORE_TEMPLATE.size());
                    for (String line : COLOR_LORE_TEMPLATE) {
                        lore.add(formatText(line, placeholders));
                    }
                    itemMeta.lore(lore);
                }
                makeNonItalic(itemMeta);
                item.setItemMeta(itemMeta);
            }

            return HMCCInventoryUtils.hideMenuTooltipDetails(item);
        }

        private Component titleComponent(int pageIndex) {
            String title = applyPlaceholders(Settings.getDyeMenuName(), pagePlaceholders(pageIndex));
            title = Hooks.processPlaceholders(viewer, title);
            return MiniMessage.miniMessage().deserialize(title);
        }

        private Component formatText(@NotNull String rawText, @NotNull Map<String, String> placeholders) {
            String text = applyPlaceholders(rawText, placeholders);
            text = Hooks.processPlaceholders(viewer, text);
            return MiniMessage.miniMessage().deserialize(text)
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        }

        private Map<String, String> colorPlaceholders(@NotNull PaletteColor paletteColor) {
            Map<String, String> placeholders = pagePlaceholders(page);
            placeholders.put("{color_name}", paletteColor.name());
            placeholders.put("{shade_name}", paletteColor.shadeName());
            placeholders.put("{shade}", String.valueOf(paletteColor.shade()));
            placeholders.put("{hex}", paletteColor.hex());
            placeholders.put("{hex_code}", "#" + paletteColor.hex());
            placeholders.put("{color}", "#" + paletteColor.hex());
            return placeholders;
        }

        private Map<String, String> pagePlaceholders(int targetPage) {
            int safeTarget = Math.max(0, Math.min(targetPage, lastPage));
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("{page}", String.valueOf(page + 1));
            placeholders.put("{pages}", String.valueOf(lastPage + 1));
            placeholders.put("{target_page}", String.valueOf(safeTarget + 1));
            return placeholders;
        }
    }

    private static ItemStack createDefaultCustomColorItem() {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        item.editMeta(itemMeta -> {
            itemMeta.displayName(MiniMessage.miniMessage().deserialize("<#45CDE9>Custom Color")
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            itemMeta.lore(List.of(
                    MiniMessage.miniMessage().deserialize("<gray>Click to type a hex color")
                            .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE),
                    MiniMessage.miniMessage().deserialize("<gray>Example: <white>#FF00AA")
                            .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
            ));
        });
        return item;
    }

    private static ItemStack createNavigationItem(boolean previous) {
        ItemStack item = new ItemStack(Material.MAGENTA_GLAZED_TERRACOTTA);
        item.editMeta(itemMeta -> {
            itemMeta.displayName(MiniMessage.miniMessage().deserialize(previous ? "<red><bold><<</bold>" : "<green><bold>>></bold>")
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            itemMeta.lore(List.of(
                    MiniMessage.miniMessage().deserialize("<gray>Page {target_page}/{pages}")
                            .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
            ));
        });
        return item;
    }

    private static ItemStack createDisabledNavigationItem() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        item.editMeta(itemMeta -> itemMeta.displayName(MiniMessage.miniMessage().deserialize(" ")
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)));
        return item;
    }

    /**
     * Menu items configured through ItemSerializer keep the client's default italic style on
     * display names and lore. This forces them to be non-italic (and resolves placeholders)
     * so buttons in the dye menu match the rest of the plugin's menus.
     */
    private static ItemStack processItem(@NotNull Player viewer, @NotNull ItemStack item, @NotNull Map<String, String> placeholders) {
        ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta == null) return item;

        if (itemMeta.hasDisplayName()) {
            String displayName = MiniMessage.miniMessage().serialize(itemMeta.displayName());
            displayName = applyPlaceholders(displayName, placeholders);
            displayName = Hooks.processPlaceholders(viewer, displayName);
            itemMeta.displayName(MiniMessage.miniMessage().deserialize(displayName)
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }

        List<Component> itemLore = itemMeta.lore();
        if (itemLore != null) {
            List<Component> processedLore = new ArrayList<>(itemLore.size());
            for (Component loreLine : itemLore) {
                String loreString = MiniMessage.miniMessage().serialize(loreLine);
                loreString = applyPlaceholders(loreString, placeholders);
                loreString = Hooks.processPlaceholders(viewer, loreString);
                processedLore.add(MiniMessage.miniMessage().deserialize(loreString)
                        .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            itemMeta.lore(processedLore);
        }

        item.setItemMeta(itemMeta);
        return HMCCInventoryUtils.hideMenuTooltipDetails(item);
    }

    private static void makeNonItalic(@NotNull ItemMeta itemMeta) {
        if (itemMeta.hasDisplayName()) {
            Component displayName = itemMeta.displayName();
            if (displayName != null) {
                itemMeta.displayName(displayName.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
        }

        List<Component> lore = itemMeta.lore();
        if (lore != null) {
            List<Component> processedLore = new ArrayList<>(lore.size());
            for (Component line : lore) {
                processedLore.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            itemMeta.lore(processedLore);
        }
    }

    private static String applyPlaceholders(@NotNull String text, @NotNull Map<String, String> placeholders) {
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private void loadShades(@NotNull ConfigurationNode shadesNode) {
        SHADES.clear();

        if (!shadesNode.virtual()) {
            for (ConfigurationNode shadeNode : shadesNode.childrenList()) {
                String name = shadeNode.node("name").getString("Base");
                double factor = shadeNode.node("factor").getDouble(1.0D);
                if (!Double.isFinite(factor) || factor < 0) {
                    MessagesUtil.sendDebugMessages("Skipping dye shade '" + name + "' because its factor is invalid.", Level.WARNING);
                    continue;
                }
                SHADES.add(new Shade(name, factor));
            }
        }

        if (SHADES.isEmpty()) {
            SHADES.add(new Shade("Dark", 0.55D));
            SHADES.add(new Shade("Dim", 0.75D));
            SHADES.add(new Shade("Base", 1.0D));
            SHADES.add(new Shade("Light", 1.25D));
        }
    }

    /**
     * Flattens the configured base colors into a single palette by generating one entry per
     * shade, in the same order as the ArtMap artkit palette.
     */
    private List<PaletteColor> buildPalette(@NotNull ConfigurationNode baseColorsNode) {
        List<PaletteColor> palette = new ArrayList<>();

        for (Map.Entry<Object, ? extends ConfigurationNode> entry : baseColorsNode.childrenMap().entrySet()) {
            ConfigurationNode colorNode = entry.getValue();
            String name = colorNode.node("name").getString("");
            if (name.isBlank()) name = String.valueOf(entry.getKey());

            String hex = normalizeHex(colorNode.node("color").getString("#FFFFFF"));
            addShades(palette, name, hex);
        }

        if (palette.isEmpty()) {
            MessagesUtil.sendDebugMessages("No base colors were found in internal_dye_menu.yml, so the built-in palette is being used. Delete the file to regenerate it with the new format.", Level.WARNING);
            for (DefaultColor defaultColor : DEFAULT_BASE_COLORS) {
                addShades(palette, defaultColor.name(), defaultColor.hex());
            }
        }

        return palette;
    }

    private void addShades(@NotNull List<PaletteColor> palette, @NotNull String name, @NotNull String hex) {
        for (int i = 0; i < SHADES.size(); i++) {
            Shade shade = SHADES.get(i);
            String shadedHex = shadeHex(hex, shade.factor());
            palette.add(new PaletteColor(name, shade.name(), i, shadedHex, HMCCServerUtils.hex2Rgb("#" + shadedHex)));
        }
    }

    private static String shadeHex(@NotNull String baseHex, double factor) {
        int rgb = Integer.parseInt(baseHex, 16);
        int red = clampColorChannel(Math.round(((rgb >> 16) & 0xFF) * factor));
        int green = clampColorChannel(Math.round(((rgb >> 8) & 0xFF) * factor));
        int blue = clampColorChannel(Math.round((rgb & 0xFF) * factor));
        return String.format(Locale.ROOT, "%02X%02X%02X", red, green, blue);
    }

    private static int clampColorChannel(long value) {
        return (int) Math.max(0, Math.min(255, value));
    }

    private static String normalizeHex(@Nullable String rawHex) {
        if (rawHex == null) return "FFFFFF";

        String value = rawHex.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("#")) value = value.substring(1);
        else if (value.startsWith("0X")) value = value.substring(2);

        if (value.length() != 6) return "FFFFFF";
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) return "FFFFFF";
        }
        return value;
    }

    private boolean isValidSlot(int slot) {
        return slot >= 0 && slot < ROWS * ROW_SIZE;
    }

    private static @Nullable ItemStack deserializeItem(@NotNull ConfigurationNode node) {
        ItemStack item = deserializeRawItem(node);
        if (item == null || item.getType() == Material.AIR) return null;
        return item;
    }

    private static @Nullable ItemStack deserializeRawItem(@NotNull ConfigurationNode node) {
        if (node.virtual()) return null;
        try {
            return ItemSerializer.INSTANCE.deserialize(ItemStack.class, node);
        } catch (SerializationException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static List<String> getStringList(@NotNull ConfigurationNode node) {
        if (node.virtual()) return List.of();
        try {
            List<String> list = node.getList(String.class);
            return list == null ? List.of() : list;
        } catch (SerializationException e) {
            e.printStackTrace();
            return List.of();
        }
    }

    private record Shade(String name, double factor) {}

    private record DefaultColor(String name, String hex) {}

    public record PaletteColor(String name, String shadeName, int shade, String hex, Color color) {}
}
