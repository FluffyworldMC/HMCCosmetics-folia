package com.hibiscusmc.hmccosmetics.gui;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import me.lojosho.shaded.configurate.CommentedConfigurationNode;
import me.lojosho.shaded.configurate.ConfigurateException;
import me.lojosho.shaded.configurate.yaml.YamlConfigurationLoader;
import org.apache.commons.io.FilenameUtils;
import org.bukkit.permissions.Permission;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Stream;

public class Menus {

    private static final List<String> FILES_TO_IGNORE = List.of("internal_dye_menu.yml", "palette_dye_menu.yml");

    private static final HashMap<String, Menu> MENUS = new HashMap<>();
    private static final HashMap<UUID, Long> COOLDOWNS = new HashMap<>();
    private static final Map<UUID, CosmeticFilterMode> COSMETIC_FILTERS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void addMenu(@NotNull Menu menu) {
        MENUS.put(menu.getId().toUpperCase(), menu);
    }

    @Nullable
    public static Menu getMenu(@NotNull String id) {
        return MENUS.get(id.toUpperCase());
    }

    @Contract(pure = true)
    @NotNull
    public static Collection<Menu> getMenu() {
        return MENUS.values();
    }

    public static boolean hasMenu(@NotNull String id) {
        return MENUS.containsKey(id.toUpperCase());
    }

    public static boolean hasMenu(@NotNull Menu menu) {
        return MENUS.containsValue(menu);
    }

    public static boolean hasDefaultMenu() {
        return MENUS.containsKey(Settings.getDefaultMenu());
    }

    @Nullable
    public static Menu getDefaultMenu() {
        return Menus.getMenu(Settings.getDefaultMenu());
    }

    @NotNull
    public static List<String> getMenuNames() {
        List<String> names = new ArrayList<>();

        for (Menu menu : MENUS.values()) {
            names.add(menu.getId());
        }

        return names;
    }

    public static Collection<Menu> values() {
        return MENUS.values();
    }

    public static void addCooldown(UUID uuid, long time) {
        COOLDOWNS.put(uuid, time);
    }

    public static Long getCooldown(UUID uuid) {
        return COOLDOWNS.getOrDefault(uuid, 0L);
    }

    public static void removeCooldown(UUID uuid) {
        COOLDOWNS.remove(uuid);
    }

    /**
     * Gets the cosmetic filter selected by a player. Defaults to showing all cosmetics.
     */
    public static @NotNull CosmeticFilterMode getCosmeticFilter(@NotNull UUID uuid) {
        return COSMETIC_FILTERS.getOrDefault(uuid, CosmeticFilterMode.ALL);
    }

    /**
     * Cycles the player's cosmetic filter: ALL -> OWNED -> UNOWNED -> ALL.
     */
    public static @NotNull CosmeticFilterMode toggleCosmeticFilter(@NotNull UUID uuid) {
        return COSMETIC_FILTERS.compute(uuid, (key, current) ->
                (current == null ? CosmeticFilterMode.ALL : current).next());
    }

    public static void clearCosmeticFilter(@NotNull UUID uuid) {
        COSMETIC_FILTERS.remove(uuid);
    }

    public static void setup() {
        MENUS.clear();
        COOLDOWNS.clear();

        File menusFolder = new File(HMCCosmeticsPlugin.getInstance().getDataFolder() + "/menus");
        if (!menusFolder.exists()) menusFolder.mkdir();

        List<MenuFile> menuFiles = new ArrayList<>();

        // Recursive file lookup
        try (Stream<Path> walkStream = Files.walk(menusFolder.toPath())) {
            walkStream.filter(p -> p.toFile().isFile())
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(child -> {
                        if (child.toString().endsWith("yml") || child.toString().endsWith("yaml")) {
                            if (FILES_TO_IGNORE.contains(child.getFileName().toString())) return;
                            MessagesUtil.sendDebugMessages("Scanning " + child);
                            // Loads file
                            YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(child).build();
                            CommentedConfigurationNode root;
                            try {
                                root = loader.load();
                            } catch (ConfigurateException e) {
                                throw new RuntimeException(e);
                            }
                            menuFiles.add(new MenuFile(FilenameUtils.removeExtension(child.getFileName().toString()), root));
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Automatic cosmetic menus are generated first so their virtual page ids are reserved
        // before any leftover _2/_3 files are considered.
        Set<String> generatedIds = new HashSet<>();
        for (MenuFile file : menuFiles) {
            if (file.root().node(AutoCosmeticMenuGenerator.LAYOUT_NODE).virtual()) continue;
            generatedIds.addAll(AutoCosmeticMenuGenerator.generate(file.id(), file.root()));
        }

        for (MenuFile file : menuFiles) {
            if (!file.root().node(AutoCosmeticMenuGenerator.LAYOUT_NODE).virtual()) continue;

            if (generatedIds.contains(file.id())) {
                MessagesUtil.sendDebugMessages("Skipping menu " + file.id() + " because an automatic page with the same id was generated. Remove this file if it is an old page.", Level.WARNING);
                continue;
            }

            try {
                new Menu(file.id(), file.root());
            } catch (Exception e) {
                MessagesUtil.sendDebugMessages("Unable to create menu in " + file.id(), Level.WARNING);
                if (Settings.isDebugMode()) e.printStackTrace();
            }
        }

        refreshPermissions();
    }

    private record MenuFile(String id, CommentedConfigurationNode root) {
    }

    public static void refreshPermissions() {
        final HMCCosmeticsPlugin instance = HMCCosmeticsPlugin.getInstance();
        for (Menu menu : Menus.values()) {
            if (menu.getPermissionNode() == null) continue;
            if (instance.getServer().getPluginManager().getPermission(menu.getPermissionNode()) != null) continue;
            instance.getServer().getPluginManager().addPermission(new Permission(menu.getPermissionNode()));
        }
    }
}
