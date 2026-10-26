package com.hibiscusmc.hmccosmetics.gui.special.impl;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Builds the Paper dialog used to enter a custom hex color.
 * <p>
 * This class references the dialog API directly, so it is only loaded when
 * {@link PaletteDyeMenu} has verified that the running server supports dialogs.
 */
public final class CustomColorDialog {

    private static final String INPUT_KEY = "color";
    private static final Pattern HEX_PATTERN = Pattern.compile("^#?[0-9a-fA-F]{6}$");

    private CustomColorDialog() {
    }

    /**
     * Opens the color input dialog.
     *
     * @param viewer The player that should see the dialog
     * @param title The dialog title (MiniMessage)
     * @param label The input label (MiniMessage)
     * @param confirm The confirm button text (MiniMessage)
     * @param cancel The cancel button text (MiniMessage)
     * @param initial The value pre-filled in the input box
     * @param maxLength The maximum length of the input
     * @param onColor Called with the parsed color after a valid submission
     */
    public static void open(Player viewer,
                            String title,
                            String label,
                            String confirm,
                            String cancel,
                            String initial,
                            int maxLength,
                            Consumer<Color> onColor) {
        show(viewer, title, label, confirm, cancel, initial, maxLength, onColor, null);
    }

    private static void show(Player viewer,
                             String title,
                             String label,
                             String confirm,
                             String cancel,
                             String initial,
                             int maxLength,
                             Consumer<Color> onColor,
                             @Nullable Component error) {
        final Component titleComponent = MiniMessage.miniMessage().deserialize(title);
        final Component labelComponent = MiniMessage.miniMessage().deserialize(label);
        final Component confirmComponent = MiniMessage.miniMessage().deserialize(confirm);
        final Component cancelComponent = MiniMessage.miniMessage().deserialize(cancel);
        final String safeInitial = initial == null ? "" : initial;
        final String initialValue = safeInitial.length() > maxLength ? safeInitial.substring(0, maxLength) : safeInitial;

        final DialogBase.Builder baseBuilder = DialogBase.builder(titleComponent)
                .canCloseWithEscape(true)
                .inputs(List.of(DialogInput.text(INPUT_KEY, labelComponent)
                        .initial(initialValue)
                        .maxLength(Math.max(1, maxLength))
                        .width(220)
                        .build()));
        if (error != null) {
            baseBuilder.body(List.of(DialogBody.plainMessage(error)));
        }

        final Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(baseBuilder.build())
                .type(DialogType.confirmation(
                        ActionButton.builder(confirmComponent)
                                .width(150)
                                .action(DialogAction.customClick((response, audience) ->
                                        handleResponse(response, audience, title, label, confirm, cancel, maxLength, onColor),
                                        ClickCallback.Options.builder().uses(1).build()))
                                .build(),
                        ActionButton.builder(cancelComponent)
                                .width(150)
                                .build())));

        viewer.showDialog(dialog);
    }

    private static void handleResponse(DialogResponseView response,
                                       Audience audience,
                                       String title,
                                       String label,
                                       String confirm,
                                       String cancel,
                                       int maxLength,
                                       Consumer<Color> onColor) {
        if (!(audience instanceof Player player)) return;

        final String rawInput = response.getText(INPUT_KEY);

        // Dialog callbacks may be invoked off the player's region thread on Folia.
        HMCCosmeticsPlugin.getScheduler().runTask(player, () -> {
            if (!player.isOnline()) return;

            player.closeDialog();

            final Color color = parseHex(rawInput);
            if (color == null) {
                Component error = MessagesUtil.processString(player, "invalid-color");
                if (error == null) {
                    error = Component.text("Invalid Color!", NamedTextColor.RED);
                }
                final Component errorMessage = error;
                final String retryValue = rawInput == null ? "" : rawInput;
                HMCCosmeticsPlugin.getScheduler().runTaskLater(player, () -> {
                    if (player.isOnline()) {
                        show(player, title, label, confirm, cancel, retryValue, maxLength, onColor, errorMessage);
                    }
                }, 2);
                return;
            }

            HMCCosmeticsPlugin.getScheduler().runTaskLater(player, () -> {
                if (player.isOnline()) onColor.accept(color);
            }, 2);
        });
    }

    /**
     * Parses a hex color in the form {@code #RRGGBB} or {@code RRGGBB}.
     *
     * @param input The raw input
     * @return The parsed color, or null if the input is not a valid hex color
     */
    public static @Nullable Color parseHex(@Nullable String input) {
        if (input == null) return null;

        String value = input.trim();
        if (!HEX_PATTERN.matcher(value).matches()) return null;
        if (value.startsWith("#")) value = value.substring(1);

        return Color.fromRGB(Integer.parseInt(value, 16));
    }
}
