package com.hibiscusmc.hmccosmetics.gui;

import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticHolder;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetics;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import me.lojosho.shaded.configurate.ConfigurationNode;
import me.lojosho.shaded.configurate.serialize.SerializationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Parsed {@code cosmetic-layout} section. Describes which cosmetics belong to a menu, which
 * slots they fill, and the labels used by the owned/unowned filter button.
 */
public final class CosmeticLayout {

    private final List<Integer> slots;
    private final Set<String> filters;
    private final Map<CosmeticFilterMode, String> labels;
    private final List<Cosmetic> eligible;

    private CosmeticLayout(@NotNull List<Integer> slots,
                           @NotNull Set<String> filters,
                           @NotNull Map<CosmeticFilterMode, String> labels) {
        this.slots = List.copyOf(slots);
        this.filters = Set.copyOf(filters);
        this.labels = Map.copyOf(labels);
        this.eligible = computeEligibleCosmetics();
    }

    /**
     * @return The parsed layout, or null when the menu has no {@code cosmetic-layout} section
     */
    @Nullable
    public static CosmeticLayout parse(@NotNull ConfigurationNode layoutNode) {
        if (layoutNode.virtual()) return null;

        List<Integer> slots = parseSlots(layoutNode.node("slots"));
        Set<String> filters = parseFilters(layoutNode.node("filter"));

        Map<CosmeticFilterMode, String> labels = new EnumMap<>(CosmeticFilterMode.class);
        ConfigurationNode labelsNode = layoutNode.node("filter-labels");
        labels.put(CosmeticFilterMode.ALL, labelsNode.node("all").getString("ทั้งหมด"));
        labels.put(CosmeticFilterMode.OWNED, labelsNode.node("owned").getString("ครอบครองแล้ว"));
        labels.put(CosmeticFilterMode.UNOWNED, labelsNode.node("unowned").getString("ยังไม่ได้ครอบครอง"));

        return new CosmeticLayout(slots, filters, labels);
    }

    private static List<Integer> parseSlots(@NotNull ConfigurationNode slotsNode) {
        if (slotsNode.virtual()) return List.of();
        try {
            List<String> rawSlots = slotsNode.getList(String.class);
            if (rawSlots == null || rawSlots.isEmpty()) return List.of();
            return Menu.getSlots(rawSlots).stream().distinct().sorted().toList();
        } catch (SerializationException e) {
            MessagesUtil.sendDebugMessages("Unable to parse cosmetic-layout slots: " + e.getMessage(), Level.WARNING);
            return List.of();
        }
    }

    private static Set<String> parseFilters(@NotNull ConfigurationNode filterNode) {
        if (filterNode.virtual()) return Set.of();
        try {
            List<String> filters = filterNode.getList(String.class);
            if (filters == null) return Set.of();

            Set<String> result = new HashSet<>();
            for (String filter : filters) {
                if (filter != null && !filter.isBlank()) {
                    result.add(filter.toUpperCase(Locale.ROOT));
                }
            }
            return result;
        } catch (SerializationException e) {
            MessagesUtil.sendDebugMessages("Unable to parse cosmetic-layout filter: " + e.getMessage(), Level.WARNING);
            return Set.of();
        }
    }

    public @NotNull List<Integer> slots() {
        return slots;
    }

    public int pageSize() {
        return slots.size();
    }

    public int totalPages(int cosmeticCount) {
        if (pageSize() == 0) return 1;
        return Math.max(1, (cosmeticCount + pageSize() - 1) / pageSize());
    }

    public @NotNull String label(@NotNull CosmeticFilterMode mode) {
        return labels.getOrDefault(mode, mode.name());
    }

    /**
     * All cosmetics that can appear in this menu, before any player-specific filtering.
     */
    public @NotNull List<Cosmetic> eligibleCosmetics() {
        return eligible;
    }

    private @NotNull List<Cosmetic> computeEligibleCosmetics() {
        List<Cosmetic> result = new ArrayList<>();
        for (Cosmetic cosmetic : Cosmetics.getSortedCosmetics()) {
            if (!matchesFilter(cosmetic)) continue;
            if (!CosmeticMenuItems.isMenuEnabled(cosmetic)) continue;
            if (CosmeticMenuItems.displaySource(cosmetic) == null) continue;
            result.add(cosmetic);
        }
        return List.copyOf(result);
    }

    /**
     * Cosmetics visible to the given holder under the selected filter.
     */
    public @NotNull List<Cosmetic> visibleCosmetics(@NotNull CosmeticHolder holder,
                                                    @NotNull CosmeticFilterMode mode) {
        List<Cosmetic> result = new ArrayList<>();
        for (Cosmetic cosmetic : eligible) {
            boolean owned = holder.canEquipCosmetic(cosmetic, true);
            boolean visible = switch (mode) {
                case ALL -> true;
                case OWNED -> owned;
                case UNOWNED -> !owned;
            };
            if (visible) result.add(cosmetic);
        }
        return result;
    }

    private boolean matchesFilter(@NotNull Cosmetic cosmetic) {
        if (filters.isEmpty()) return true;
        if (cosmetic.getSlot() == null) return false;
        return filters.contains(cosmetic.getSlot().getName().toUpperCase(Locale.ROOT));
    }
}
