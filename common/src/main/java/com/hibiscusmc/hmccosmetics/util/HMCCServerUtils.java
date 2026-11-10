package com.hibiscusmc.hmccosmetics.util;

import com.github.Anon8281.universalScheduler.UniversalScheduler;
import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import me.lojosho.hibiscuscommons.nms.NMSHandlers;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.Arrays;

public class HMCCServerUtils {

    private static String COLOR_CHAR = "&";

    /**
     * Converts a bukkit gamemode into an integer for use in packets
     * @param gamemode Bukkit gamemode to convert.
     * @return int of the gamemode
     */
    public static int convertGamemode(final GameMode gamemode) {
        return switch (gamemode) {
            case SURVIVAL -> 0;
            case CREATIVE -> 1;
            case ADVENTURE -> 2;
            case SPECTATOR -> 3;
        };
    }

    public static Entity getEntity(int entityId, World world) {
        return NMSHandlers.getHandler().getUtilHandler().getEntity(entityId, world);
    }

    /**
     * Canvas and Folia reject synchronous {@link Entity#teleport(Location)} on a region thread.
     */
    public static void teleport(Entity entity, Location location) {
        if (entity == null || location == null || location.getWorld() == null) return;
        if (UniversalScheduler.isFolia || UniversalScheduler.isCanvas) {
            entity.teleportAsync(location);
            return;
        }
        entity.teleport(location);
    }

    public static void teleport(Entity entity, Location location, PlayerTeleportEvent.TeleportCause cause) {
        if (entity == null || location == null || location.getWorld() == null) return;
        if (UniversalScheduler.isFolia || UniversalScheduler.isCanvas) {
            entity.teleportAsync(location, cause);
            return;
        }
        entity.teleport(location, cause);
    }

    /**
     * Runs a task on the region thread that owns {@code entity} after a delay.
     *
     * <p>On region-threaded servers an entity scheduler silently drops tasks while the entity is
     * retired, which happens during cross-world teleports. This retries on the global scheduler
     * until the entity is available again, so cosmetic refreshes are not lost when a player
     * changes worlds.</p>
     */
    public static void runOnEntityLater(final Entity entity, final Runnable task, final long delayTicks) {
        if (entity == null || task == null) return;
        if (HMCCosmeticsPlugin.getScheduler() == null) return;
        if (!UniversalScheduler.isFolia && !UniversalScheduler.isCanvas) {
            HMCCosmeticsPlugin.getScheduler().runTaskLater(HMCCosmeticsPlugin.getInstance(), task, delayTicks);
            return;
        }
        scheduleOnEntity(entity, task, delayTicks, 0);
    }

    private static void scheduleOnEntity(final Entity entity, final Runnable task, final long delayTicks, final int attempt) {
        if (attempt > 100 || HMCCosmeticsPlugin.getScheduler() == null) return;

        boolean scheduled = false;
        try {
            scheduled = entity.getScheduler().runDelayed(
                    HMCCosmeticsPlugin.getInstance(),
                    scheduledTask -> task.run(),
                    () -> {
                        // The entity was retired before the task could run (common during a
                        // cross-world teleport). Reschedule instead of silently losing the task.
                        if (HMCCosmeticsPlugin.getScheduler() == null) return;
                        HMCCosmeticsPlugin.getScheduler().runTaskLater(
                                HMCCosmeticsPlugin.getInstance(),
                                () -> scheduleOnEntity(entity, task, delayTicks, attempt + 1),
                                1L
                        );
                    },
                    delayTicks
            ) != null;
        } catch (IllegalStateException exception) {
            // The entity was retired between the check and the scheduling attempt.
        }

        if (scheduled) return;

        // The entity is mid-handoff; try again on the next global tick.
        HMCCosmeticsPlugin.getScheduler().runTaskLater(
                HMCCosmeticsPlugin.getInstance(),
                () -> scheduleOnEntity(entity, task, delayTicks, attempt + 1),
                1L
        );
    }

    /**
     * This takes in a string like #FFFFFF to convert it into a Bukkit color
     * @param colorStr
     * @return
     */
    public static Color hex2Rgb(String colorStr) {
        if (colorStr.startsWith("#")) return Color.fromRGB(Integer.valueOf(colorStr.substring(1), 16));
        if (colorStr.startsWith("0x")) return Color.fromRGB(Integer.valueOf(colorStr.substring(2), 16));
        if (colorStr.contains(",")) {
            String[] colorString = colorStr.replace(" ", "").split(",");
            for (String color : colorString) if (Integer.valueOf(color) == null) return Color.WHITE;
            Color.fromRGB(Integer.valueOf(colorString[0]), Integer.valueOf(colorString[1]), Integer.valueOf(colorString[2]));
        }

        return Color.WHITE;
    }

    /**
     * This takes in a string like 55,49,181 to convert it into a Bukkit Color
     * @param colorStr
     * @return
     */
    public static Color rgbToRgb(String colorStr) {
        if (colorStr.contains(",")) {
            String[] colors = colorStr.split(",", 3);
            if (colors.length == 3) {
                return Color.fromRGB(Integer.parseInt(colors[0]), Integer.parseInt(colors[1]), Integer.parseInt(colors[2]));
            }
        }

        return Color.WHITE;
    }

    private static BigInteger getBigInteger(String string) {
        try {
            return new BigInteger(string);
        } catch (Exception e) {
            return BigInteger.valueOf(1);
        }
    }

    /**
     * Parse a color from a string.
     * Formats: #RRGGBB; R,G,B
     *
     * @param color The string
     * @return The color, if the string can't be parsed, null is returned
     */
    public static Color colorFromString(@Nullable String color) {
        if (color == null) {
            return null;
        }
        try {
            var decodedColor = java.awt.Color.decode(color.startsWith("#") ? color : "#" + color);
            return Color.fromRGB(decodedColor.getRed(), decodedColor.getGreen(), decodedColor.getBlue());
        } catch (NumberFormatException invalidHex) {
            try {
                var rgbValues = Arrays.stream(color.split(",")).map(Integer::parseInt).toArray(Integer[]::new);
                return Color.fromRGB(rgbValues[0], rgbValues[1], rgbValues[2]);
            } catch (Exception invalidRgb) {
                return null;
            }
        }
    }

    public static int getNextYaw(final int current, final int rotationSpeed) {
        int nextYaw = current + rotationSpeed;
        if (nextYaw > 179) {
            nextYaw = (current + rotationSpeed) - 358;
            return nextYaw;
        }
        return nextYaw;
    }

    public static NamespacedKey getCosmemeticMobKey() {
        return new NamespacedKey(HMCCosmeticsPlugin.getInstance(), "cosmeticmob");
    }
}
