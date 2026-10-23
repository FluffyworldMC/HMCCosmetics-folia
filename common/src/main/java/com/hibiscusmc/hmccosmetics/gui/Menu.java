package com.hibiscusmc.hmccosmetics.gui;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.api.events.PlayerMenuCloseEvent;
import com.hibiscusmc.hmccosmetics.api.events.PlayerMenuOpenEvent;
import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticHolder;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetics;
import com.hibiscusmc.hmccosmetics.gui.type.ShadingType;
import com.hibiscusmc.hmccosmetics.gui.type.Type;
import com.hibiscusmc.hmccosmetics.gui.type.Types;
import com.hibiscusmc.hmccosmetics.gui.type.types.TypeCosmetic;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.util.HMCCInventoryUtils;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import io.papermc.paper.datacomponent.item.CustomModelData;
import lombok.Getter;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import me.lojosho.hibiscuscommons.config.serializer.ItemSerializer;
import me.lojosho.hibiscuscommons.hooks.Hooks;
import me.lojosho.hibiscuscommons.util.AdventureUtils;
import me.lojosho.shaded.configurate.ConfigurationNode;
import me.lojosho.shaded.configurate.serialize.SerializationException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import com.github.Anon8281.universalScheduler.scheduling.tasks.MyScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

public class Menu {

    @Getter
    private final String id;
    @Getter
    private final String title;
    @Getter
    private final int rows;
    @Getter
    private final Long cooldown;
    @Getter
    private final ConfigurationNode config;
    @Getter
    private final String permissionNode;
    private final HashMap<Integer, List<MenuItem>> items;
    @Getter
    private final int refreshRate;
    @Getter
    private final @Nullable CosmeticLayout cosmeticLayout;
    @Getter
    private final @Nullable String cosmeticBaseId;
    @Getter
    private final int cosmeticPage;

    public Menu(String id, @NotNull ConfigurationNode config) {
        this(id, config, null, null, 1);
    }

    public Menu(String id, @NotNull ConfigurationNode config,
                @Nullable CosmeticLayout cosmeticLayout,
                @Nullable String cosmeticBaseId,
                int cosmeticPage) {
        this.id = config.node("id").getString(id);
        this.config = config;
        this.cosmeticLayout = cosmeticLayout;
        this.cosmeticBaseId = cosmeticBaseId;
        this.cosmeticPage = Math.max(1, cosmeticPage);

        title = config.node("title").getString("chest");

        int configuredRows = config.node("rows").getInt(1);
        if (cosmeticLayout != null) {
            int maxSlot = cosmeticLayout.slots().stream().mapToInt(Integer::intValue).max().orElse(-1);
            int requiredRows = maxSlot < 0 ? 1 : (maxSlot / 9) + 1;
            int targetRows = Math.min(6, Math.max(configuredRows, requiredRows));
            if (requiredRows > configuredRows) {
                MessagesUtil.sendDebugMessages("Menu " + id + " has rows=" + configuredRows
                        + " but its cosmetic-layout needs " + requiredRows + " rows. Using " + targetRows + ".", Level.WARNING);
            }
            rows = targetRows;
        } else {
            rows = configuredRows;
        }

        cooldown = config.node("click-cooldown").getLong(Settings.getDefaultMenuCooldown());
        permissionNode = config.node("permission").getString("");
        refreshRate = config.node("refresh-rate").getInt(-1);

        items = new HashMap<>();
        setupItems();

        Menus.addMenu(this);
    }

    private void setupItems() {
        for (ConfigurationNode config : config.node("items").childrenMap().values()) {

            List<String> slotString;
            try {
                slotString = config.node("slots").getList(String.class);
            } catch (SerializationException e) {
                continue;
            }
            if (slotString == null) {
                MessagesUtil.sendDebugMessages("Unable to get valid slot for " + config.key().toString());
                continue;
            }

            List<Integer> slots = getSlots(slotString);

            if (slots.isEmpty()) {
                MessagesUtil.sendDebugMessages("Slot is empty for " + config.key().toString());
                continue;
            }

            ItemStack item;
            try {
                item = ItemSerializer.INSTANCE.deserialize(ItemStack.class, config.node("item"));
            } catch (SerializationException e) {
                MessagesUtil.sendDebugMessages("Unable to get valid item for " + config.key().toString() + " " + e.getMessage());
                continue;
            }

            if (item == null) {
                MessagesUtil.sendDebugMessages("Something went wrong with the item creation for " + config.key().toString());
                continue;
            }

            int priority = config.node("priority").getInt(1);

            Type type = Types.getDefaultType();
            if (!config.node("type").virtual()) {
                String typeId = config.node("type").getString("");
                if (Types.isType(typeId)) type = Types.getType(typeId);
            }

            for (Integer slot : slots) {
                if (slot < 0 || slot >= rows * 9) {
                    MessagesUtil.sendDebugMessages("Skipping invalid slot " + slot + " for item " + config.key() + " in menu " + id, Level.WARNING);
                    continue;
                }

                MenuItem menuItem = new MenuItem(slots, item, type, priority, config);
                if (items.containsKey(slot)) {
                    List<MenuItem> menuItems = items.get(slot);
                    menuItems.add(menuItem);
                    menuItems.sort(priorityCompare);
                    items.put(slot, menuItems);
                } else {
                    items.put(slot, new ArrayList<>(List.of(menuItem)));
                }
            }
        }
    }

    public void openMenu(CosmeticUser user) {
        openMenu(user, false);
    }

    public void openMenu(@NotNull CosmeticUser user, boolean ignorePermission) {
        Player player = user.getPlayer();
        if (player == null) return;
        openMenu(player, user, ignorePermission);
    }

    public void openMenu(@NotNull Player viewer, @NotNull CosmeticHolder cosmeticHolder) {
        openMenu(viewer, cosmeticHolder, false);
    }

    public void openMenu(@NotNull Player viewer, @NotNull CosmeticHolder cosmeticHolder, boolean ignorePermission) {
        if (!ignorePermission && !permissionNode.isBlank()) {
            if (!viewer.hasPermission(permissionNode) && !viewer.isOp()) {
                MessagesUtil.sendMessage(viewer, "no-permission");
                return;
            }
        }
        final Component component = AdventureUtils.MINI_MESSAGE.deserialize(Hooks.processPlaceholders(viewer, this.title));
        Gui gui = Gui.gui()
                .rows(rows)
                .title(component)
                .inventory((title, owner, inventorySize) -> Bukkit.createInventory(owner, inventorySize, title))
                .create();

        gui.setDefaultClickAction(event -> event.setCancelled(true));

        AtomicReference<MyScheduledTask> task = new AtomicReference<>();
        gui.setOpenGuiAction(event -> {
            Runnable run = () -> {
                MyScheduledTask current = task.get();
                if (gui.getInventory().getViewers().isEmpty() && current != null) {
                    current.cancel();
                }

                updateMenu(viewer, cosmeticHolder, gui);
            };

            if (refreshRate > 0) {
                task.set(HMCCosmeticsPlugin.getScheduler().runTaskTimer(viewer, run, 0, refreshRate));
            } else {
                run.run();
            }
        });

        gui.setCloseGuiAction(event -> {
            if (cosmeticHolder instanceof CosmeticUser user) {
                PlayerMenuCloseEvent closeEvent = new PlayerMenuCloseEvent(user, this, event.getReason());
                HMCCosmeticsPlugin.getScheduler().runTask(viewer, () -> Bukkit.getPluginManager().callEvent(closeEvent));
            }

            MyScheduledTask current = task.get();
            if (current != null) current.cancel();
        });

        Runnable openGuiTask = () -> {
            gui.open(viewer);
            updateMenu(viewer, cosmeticHolder, gui); // fixes shading? I know I do this twice but it's easier than writing a whole new class to deal with this shit
        };

        // API
        if (cosmeticHolder instanceof CosmeticUser user) {
            PlayerMenuOpenEvent event = new PlayerMenuOpenEvent(user, this);
            HMCCosmeticsPlugin.getScheduler().runTask(viewer, () -> {
                Bukkit.getPluginManager().callEvent(event);
                if (!event.isCancelled()) {
                    openGuiTask.run();
                }
            });
        }
        // Internal
        else {
            HMCCosmeticsPlugin.getScheduler().runTask(viewer, openGuiTask);
        }
    }

    private void updateMenu(Player viewer, CosmeticHolder cosmeticHolder, Gui gui) {
        final MenuView view = buildView(viewer, cosmeticHolder);
        if (cosmeticLayout != null) {
            // Remove stale cosmetic items when a page/filter no longer fills every layout slot.
            int inventorySize = gui.getInventory().getSize();
            for (int slot : cosmeticLayout.slots()) {
                if (slot >= 0 && slot < inventorySize) {
                    gui.removeItem(slot);
                }
            }
        }
        StringBuilder title = new StringBuilder(this.title);

        int row = 0;
        switch (Settings.getShadingType()) {
            case TEXT -> {
                for (int i = 0; i < gui.getInventory().getSize(); i++) {
                    // Handles the title
                    if (i % 9 == 0) {
                        if (row == 0) {
                            title.append(Settings.getFirstRowShift()); // Goes back to the start of the gui
                        } else {
                            title.append(Settings.getSequentRowShift());
                        }
                        row += 1;
                    } else {
                        title.append(Settings.getIndividualColumnShift()); // Goes to the next slot
                    }

                    boolean occupied = false;

                    if (view.items().containsKey(i)) {
                        // Handles the items
                        List<MenuItem> menuItems = view.items().get(i);
                        MenuItem item = menuItems.getFirst();
                        updateItem(viewer, cosmeticHolder, gui, i, view);

                        if (item.type() instanceof TypeCosmetic) {
                            Cosmetic cosmetic = Cosmetics.getCosmetic(item.itemConfig().node("cosmetic").getString(""));
                            if (cosmetic == null) continue;
                            if (cosmeticHolder.hasCosmeticInSlot(cosmetic)) {
                                title.append(Settings.getEquippedCosmeticColor());
                            } else {
                                if (cosmeticHolder.canEquipCosmetic(cosmetic, true)) {
                                    title.append(Settings.getEquipableCosmeticColor());
                                } else {
                                    title.append(Settings.getLockedCosmeticColor());
                                }
                            }
                            occupied = true;
                        }
                    }
                    if (occupied) {
                        title.append(Settings.getBackground().replaceAll("<row>", String.valueOf(row)));
                    } else {
                        title.append(Settings.getClearBackground().replaceAll("<row>", String.valueOf(row)));
                    }
                }
                MessagesUtil.sendDebugMessages("Updated menu with title " + title);
                gui.updateTitle(AdventureUtils.MINI_MESSAGE.deserialize(Hooks.processPlaceholders(viewer, title.toString())));
            }
            case MODERN -> {
                TextColor equippableColor = resolveStateColor(Settings.getEquipableCosmeticColor());
                TextColor equippedColor = resolveStateColor(Settings.getEquippedCosmeticColor());
                TextColor lockedColor = resolveStateColor(Settings.getLockedCosmeticColor());

                for (int i = 0; i < gui.getInventory().getSize(); i++) {
                    if (view.items().containsKey(i)) {
                        List<MenuItem> menuItems = view.items().get(i);
                        MenuItem item = menuItems.getFirst();
                        updateItem(viewer, cosmeticHolder, gui, i, (itemStack -> {
                            // Handles the items
                            if (item.type() instanceof TypeCosmetic) {
                                Cosmetic cosmetic = Cosmetics.getCosmetic(item.itemConfig().node("cosmetic").getString(""));
                                if (cosmetic == null) return;

                                Key itemKey = Key.key("hmccosmetics", cosmetic.getId() + "_shading");
                                itemStack.setData(DataComponentTypes.ITEM_MODEL, itemKey);



                                CustomModelData.Builder builder = CustomModelData.customModelData();
                                if (cosmeticHolder.hasCosmeticInSlot(cosmetic)) builder.addColor(Color.fromRGB(equippedColor.value()));
                                else if (cosmeticHolder.canEquipCosmetic(cosmetic, true)) builder.addColor(Color.fromRGB(equippableColor.value()));
                                else builder.addColor(Color.fromRGB(lockedColor.value()));

                                CustomModelData cmd = itemStack.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
                                if (cmd != null) builder.addFlags(cmd.flags()).addFloats(cmd.floats()).addStrings(cmd.strings());

                                itemStack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, builder.build());
                            }
                        }), view);
                    }
                }
            }
            case null, default -> {
                for (int i = 0; i < gui.getInventory().getSize(); i++) {
                    if (view.items().containsKey(i)) {
                        updateItem(viewer, cosmeticHolder, gui, i, view);
                    }
                }
            }
        }
    }

    /**
     * Builds the items that should be displayed to this viewer for the current page. For automatic
     * cosmetic menus this also applies the owned/unowned filter and reflows the page.
     */
    private MenuView buildView(Player viewer, CosmeticHolder cosmeticHolder) {
        Map<Integer, List<MenuItem>> view = new HashMap<>();
        for (Map.Entry<Integer, List<MenuItem>> entry : items.entrySet()) {
            view.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        if (cosmeticLayout == null || cosmeticBaseId == null || cosmeticLayout.pageSize() == 0) {
            return new MenuView(view, 1, 1);
        }

        CosmeticFilterMode mode = Menus.getCosmeticFilter(viewer.getUniqueId());
        List<Cosmetic> visible = cosmeticLayout.visibleCosmetics(cosmeticHolder, mode);
        int totalPages = cosmeticLayout.totalPages(visible.size());
        int page = Math.min(cosmeticPage, totalPages);
        int start = (page - 1) * cosmeticLayout.pageSize();

        for (int i = 0; i < cosmeticLayout.pageSize(); i++) {
            int index = start + i;
            if (index >= visible.size()) break;

            int slot = cosmeticLayout.slots().get(i);
            if (slot < 0 || slot >= rows * 9) continue;

            MenuItem menuItem = CosmeticMenuItems.createMenuItem(visible.get(index), slot);
            if (menuItem != null) {
                view.put(slot, new ArrayList<>(List.of(menuItem)));
            }
        }

        return new MenuView(view, page, totalPages);
    }

    private record MenuView(Map<Integer, List<MenuItem>> items, int page, int totalPages) {
    }

    /**
     * Resolves a MiniMessage-formatted color string into a {@link TextColor}, falling back to white
     * if the string is null or carries no explicit color (avoids an NPE when used for MODERN shading dye).
     */
    private static TextColor resolveStateColor(String miniMessage) {
        if (miniMessage == null) return NamedTextColor.WHITE;
        TextColor color = MiniMessage.miniMessage().deserialize(miniMessage).color();
        return color != null ? color : NamedTextColor.WHITE;
    }

    private void updateItem(Player viewer, CosmeticHolder cosmeticHolder, Gui gui, int slot, MenuView view) {
        updateItem(viewer, cosmeticHolder, gui, slot, null, view);
    }

    private void updateItem(Player viewer, CosmeticHolder cosmeticHolder, Gui gui, int slot,
                            @Nullable Consumer<ItemStack> consumer, MenuView view) {
        if (!view.items().containsKey(slot)) return;
        List<MenuItem> menuItems = view.items().get(slot);
        if (menuItems.isEmpty()) return;

        for (MenuItem item : menuItems) {
            boolean nextPage = item.itemConfig().node("next-page").getBoolean(false);
            boolean previousPage = item.itemConfig().node("previous-page").getBoolean(false);
            boolean filterToggle = item.itemConfig().node("filter-toggle").getBoolean(false);

            if (nextPage && view.page() >= view.totalPages()) {
                removeItemIfValid(gui, slot);
                return;
            }
            if (previousPage && view.page() <= 1) {
                removeItemIfValid(gui, slot);
                return;
            }

            Type type = item.type();
            ItemStack itemStack = item.item().clone();
            ItemStack modifiedItem = getMenuItem(viewer, cosmeticHolder, type, item.itemConfig(), itemStack, slot);

            if (filterToggle && cosmeticLayout != null) {
                modifiedItem = applyFilterLabel(modifiedItem, cosmeticLayout.label(Menus.getCosmeticFilter(viewer.getUniqueId())));
            }

            if (consumer != null) {
                // Shading decoration is best-effort: a failure here must never stop the item from
                // being placed, otherwise the whole menu renders empty.
                try {
                    consumer.accept(modifiedItem);
                } catch (Exception e) {
                    MessagesUtil.sendDebugMessages("Failed to apply shading to menu item in slot " + slot + ": " + e.getMessage());
                }
            }
            if (modifiedItem.getType().isAir()) continue;
            GuiItem guiItem = ItemBuilder.from(modifiedItem).asGuiItem();

            if ((nextPage || previousPage) && cosmeticBaseId != null) {
                final int targetPage = view.page() + (nextPage ? 1 : -1);
                final String targetId = targetPage == 1 ? cosmeticBaseId : cosmeticBaseId + "_" + targetPage;
                guiItem.setAction(event -> {
                    event.setCancelled(true);
                    Menu target = Menus.getMenu(targetId);
                    if (target != null) target.openMenu(viewer, cosmeticHolder);
                });
            } else if (filterToggle && cosmeticLayout != null) {
                guiItem.setAction(event -> {
                    event.setCancelled(true);
                    Menus.toggleCosmeticFilter(viewer.getUniqueId());
                    openFilteredPage(viewer, cosmeticHolder);
                });
            } else {
                guiItem.setAction(event -> {
                    UUID uuid = viewer.getUniqueId();
                    if (Settings.isMenuClickCooldown()) {
                        Long userCooldown = Menus.getCooldown(uuid);
                        if (userCooldown != 0 && (System.currentTimeMillis() - Menus.getCooldown(uuid) <= getCooldown())) {
                            MessagesUtil.sendDebugMessages("Cooldown for " + viewer.getUniqueId() + " System time: " + System.currentTimeMillis() + " Cooldown: " + Menus.getCooldown(viewer.getUniqueId()) + " Difference: " + (System.currentTimeMillis() - Menus.getCooldown(viewer.getUniqueId())));
                            MessagesUtil.sendMessage(viewer, "on-click-cooldown");
                            return;
                        } else {
                            Menus.addCooldown(uuid, System.currentTimeMillis());
                        }
                    }
                    MessagesUtil.sendDebugMessages("Updated Menu Item in slot number " + slot);
                    final ClickType clickType = event.getClick();
                    if (type != null) type.run(viewer, cosmeticHolder, item.itemConfig(), clickType);
                    updateMenu(viewer, cosmeticHolder, gui);
                });
            }

            MessagesUtil.sendDebugMessages("Set an item in slot " + slot + " in the menu of " + getId());
            gui.updateItem(slot, guiItem);
            break;
        }
    }

    private static void removeItemIfValid(Gui gui, int slot) {
        if (slot < 0 || slot >= gui.getInventory().getSize()) return;
        gui.removeItem(slot);
    }

    /**
     * Reopens the current automatic menu on a page that exists for the newly selected filter.
     */
    private void openFilteredPage(Player viewer, CosmeticHolder cosmeticHolder) {
        if (cosmeticLayout == null || cosmeticBaseId == null) return;

        CosmeticFilterMode mode = Menus.getCosmeticFilter(viewer.getUniqueId());
        int totalPages = cosmeticLayout.totalPages(cosmeticLayout.visibleCosmetics(cosmeticHolder, mode).size());
        int page = Math.min(cosmeticPage, totalPages);
        String targetId = page == 1 ? cosmeticBaseId : cosmeticBaseId + "_" + page;

        Menu target = Menus.getMenu(targetId);
        if (target != null) target.openMenu(viewer, cosmeticHolder);
    }

    private static ItemStack applyFilterLabel(ItemStack item, String label) {
        if (!item.hasItemMeta()) return item;

        ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta == null) return item;

        if (itemMeta.hasDisplayName()) {
            itemMeta.displayName(replaceFilterPlaceholder(itemMeta.displayName(), label));
        }

        if (itemMeta.hasLore()) {
            List<Component> lore = new ArrayList<>();
            for (Component line : itemMeta.lore()) {
                lore.add(replaceFilterPlaceholder(line, label));
            }
            itemMeta.lore(lore);
        }

        item.setItemMeta(itemMeta);
        return item;
    }

    private static Component replaceFilterPlaceholder(Component component, String label) {
        String serialized = MiniMessage.miniMessage().serialize(component)
                .replace("%cosmetic_filter%", label);
        return MiniMessage.miniMessage().deserialize(serialized)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    @NotNull
    static List<Integer> getSlots(@NotNull List<String> slotString) {
        List<Integer> slots = new ArrayList<>();

        for (String a : slotString) {
            if (a.contains("-")) {
                String[] split = a.split("-");
                int min = Integer.parseInt(split[0]);
                int max = Integer.parseInt(split[1]);
                slots.addAll(getSlots(min, max));
            } else {
                slots.add(Integer.valueOf(a));
            }
        }

        return slots;
    }

    @NotNull
    private static List<Integer> getSlots(int small, int max) {
        List<Integer> slots = new ArrayList<>();

        for (int i = small; i <= max; i++) slots.add(i);
        return slots;
    }

    @Contract("_, _, _, _, _, _ -> param4")
    @NotNull
    private ItemStack getMenuItem(Player viewer, CosmeticHolder cosmeticHolder, Type type, ConfigurationNode config, ItemStack itemStack, int slot) {
        if (!itemStack.hasItemMeta()) return HMCCInventoryUtils.hideMenuTooltipDetails(itemStack);
        return HMCCInventoryUtils.hideMenuTooltipDetails(type.setItem(viewer, cosmeticHolder, config, itemStack, slot));
    }

    public boolean canOpen(Player player) {
        if (permissionNode.isBlank()) return true;
        return player.isOp() || player.hasPermission(permissionNode);
    }

    public static Comparator<MenuItem> priorityCompare = Comparator.comparing(MenuItem::priority).reversed();
}
