package com.hibiscusmc.hmccosmetics.cosmetic;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import lombok.extern.slf4j.Slf4j;
import me.lojosho.shaded.configurate.CommentedConfigurationNode;
import me.lojosho.shaded.configurate.ConfigurateException;
import me.lojosho.shaded.configurate.ConfigurationNode;
import me.lojosho.shaded.configurate.yaml.YamlConfigurationLoader;
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

@Slf4j
public class Cosmetics {
    private static final Map<String, Cosmetic> COSMETICS = new LinkedHashMap<>();

    private static CosmeticProvider PROVIDER = CosmeticProvider.Default.INSTANCE;

    public static void addCosmetic(Cosmetic cosmetic) {
        COSMETICS.put(cosmetic.getId(), cosmetic);
    }

    public static void removeCosmetic(String id) {
        COSMETICS.remove(id);
    }

    public static void removeCosmetic(Cosmetic cosmetic) {
        COSMETICS.remove(cosmetic.getId());
    }

    @Nullable
    public static Cosmetic getCosmetic(String id) {
        return COSMETICS.get(id);
    }

    @Contract(pure = true)
    @NotNull
    public static Set<Cosmetic> values() {
        return new LinkedHashSet<>(COSMETICS.values());
    }

    @Contract(pure = true)
    @NotNull
    public static Set<String> keys() {
        return new LinkedHashSet<>(COSMETICS.keySet());
    }

    /**
     * Returns all loaded cosmetics ordered by priority (highest first). Cosmetics with the same
     * priority keep the order in which their files and entries were loaded.
     *
     * @return the sorted cosmetics
     */
    @Contract(pure = true)
    @NotNull
    public static List<Cosmetic> getSortedCosmetics() {
        List<Cosmetic> sorted = new ArrayList<>(COSMETICS.values());
        // List#sort is stable, so equal priorities keep the deterministic load order.
        sorted.sort(Comparator.comparingInt(Cosmetic::getPriority).reversed());
        return sorted;
    }

    public static boolean hasCosmetic(String id) {
        return COSMETICS.containsKey(id);
    }

    public static boolean hasCosmetic(Cosmetic cosmetic) {
        return COSMETICS.containsValue(cosmetic);
    }

    public static void setup() {
        COSMETICS.clear();

        File cosmeticFolder = new File(HMCCosmeticsPlugin.getInstance().getDataFolder() + "/cosmetics");
        if (!cosmeticFolder.exists()) cosmeticFolder.mkdir();

        List<Path> cosmeticFiles;
        try (Stream<Path> walkStream = Files.walk(cosmeticFolder.toPath())) {
            cosmeticFiles = walkStream
                    .filter(p -> p.toFile().isFile())
                    .filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                    // Deterministic load order: equal priorities are ordered by file path.
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        for (Path child : cosmeticFiles) {
            MessagesUtil.sendDebugMessages("Scanning " + child);
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(child).build();
            CommentedConfigurationNode root;
            try {
                root = loader.load();
            } catch (ConfigurateException e) {
                throw new RuntimeException(e);
            }

            ConfigurationNode priorityNode = root.node("priority");
            int filePriority = (!priorityNode.virtual() && !priorityNode.isMap() && !priorityNode.isList())
                    ? priorityNode.getInt(0)
                    : 0;
            setupCosmetics(root, filePriority);
        }

        refreshPermissions();
    }

    /**
     * Register a custom {@link CosmeticProvider} to provide your own user implementation to
     * be used and queried.
     * @param provider the provider to register
     * @throws IllegalArgumentException if the provider is already registered by another plugin
     */
    public static void registerProvider(final CosmeticProvider provider) {
        if(PROVIDER != CosmeticProvider.Default.INSTANCE) {
            throw new IllegalArgumentException("CosmeticProvider already registered by %s, this conflicts with %s attempting to register their own.".formatted(
                PROVIDER.getProviderPlugin().getName(),
                provider.getProviderPlugin().getName()
            ));
        }

        PROVIDER = provider;
    }

    /**
     * Fetch the current {@link CosmeticProvider} being used.
     * @return the current {@link CosmeticProvider} being used
     */
    public static CosmeticProvider getProvider() {
        return PROVIDER;
    }

    private static void setupCosmetics(@NotNull CommentedConfigurationNode config, int filePriority) {
        for (ConfigurationNode cosmeticConfig : config.childrenMap().values()) {
            String id = cosmeticConfig.key().toString();

            // Top-level "priority" is file metadata. A map/entry named "priority" is still a cosmetic.
            if ("priority".equalsIgnoreCase(id) && !cosmeticConfig.isMap()) {
                continue;
            }

            MessagesUtil.sendDebugMessages("Attempting to add " + id);
            ConfigurationNode slotNode = cosmeticConfig.node("slot");
            if (slotNode.virtual()) {
                MessagesUtil.sendDebugMessages("Unable to create " + id + " because there is no slot defined!", Level.WARNING);
                continue;
            }
            String slot = slotNode.getString("");
            CosmeticSlot cosmeticSlot = CosmeticSlot.valueOf(slot);
            if (cosmeticSlot == null) {
                MessagesUtil.sendDebugMessages("Unable to create " + id + " because " + slotNode.getString() + " is not a valid slot!", Level.WARNING);
                continue;
            }

            try {
                Cosmetic cosmetic = PROVIDER.createCosmetic(id, cosmeticConfig, cosmeticSlot);
                cosmetic.setPriority(cosmeticConfig.node("priority").getInt(filePriority));
                addCosmetic(cosmetic);
            } catch(Exception ex) {
                log.error("Unable to construct cosmetic for {}, skipping processing it.", id, ex);
            }
        }
    }

    public static void refreshPermissions() {
        final HMCCosmeticsPlugin instance = HMCCosmeticsPlugin.getInstance();
        for (Cosmetic cosmetic : Cosmetics.values()) {
            if (cosmetic.getPermission() == null) continue;
            if (instance.getServer().getPluginManager().getPermission(cosmetic.getPermission()) != null) continue;
            instance.getServer().getPluginManager().addPermission(new Permission(cosmetic.getPermission()));
        }
    }
}