package com.hibiscusmc.hmccosmetics.gui;

import org.jetbrains.annotations.NotNull;

/**
 * Player-selectable filter for automatic cosmetic menus.
 */
public enum CosmeticFilterMode {
    ALL,
    OWNED,
    UNOWNED;

    public @NotNull CosmeticFilterMode next() {
        return switch (this) {
            case ALL -> OWNED;
            case OWNED -> UNOWNED;
            case UNOWNED -> ALL;
        };
    }
}
