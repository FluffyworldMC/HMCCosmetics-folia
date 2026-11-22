package com.hibiscusmc.hmccosmetics.user.manager;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.config.WardrobeSettings;
import com.hibiscusmc.hmccosmetics.config.section.Wardrobe;
import com.hibiscusmc.hmccosmetics.config.section.WardrobeLocation;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticBalloonType;
import com.hibiscusmc.hmccosmetics.gui.Menu;
import com.hibiscusmc.hmccosmetics.gui.Menus;
import com.hibiscusmc.hmccosmetics.packets.WardrobeClickHandler;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.util.HMCCInventoryUtils;
import com.hibiscusmc.hmccosmetics.util.HMCCServerUtils;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager;
import lombok.Getter;
import lombok.Setter;
import me.lojosho.hibiscuscommons.nms.NMSHandlers;
import me.lojosho.hibiscuscommons.nms.NMSPacketBuilder;
import me.lojosho.hibiscuscommons.nms.NMSPacketSender;
import me.lojosho.hibiscuscommons.packets.wrapper.PacketWrapper;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import com.github.Anon8281.universalScheduler.scheduling.tasks.MyScheduledTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class UserWardrobeManager {

    @Getter
    private final int NPC_ID;
    @Getter
    private final int ARMORSTAND_ID;
    private final int backpackPreviewId;
    private ItemStack backpackPreviewItem;
    private double mannequinScale = 1.0;
    @Getter
    private final UUID WARDROBE_UUID;
    @Getter
    private String npcName;
    @Getter
    private GameMode originalGamemode;
    @Getter
    private final CosmeticUser user;
    @Getter
    private final Wardrobe wardrobe;
    @Getter
    private final WardrobeLocation wardrobeLocation;
    @Getter
    private final Location viewingLocation;
    @Getter
    private final Location npcLocation;
    @Getter
    private Location exitLocation;
    @Getter
    private BossBar bossBar;
    @Getter
    private boolean active;
    private MyScheduledTask wardrobeTask;
    private Runnable removeClickHandler;
    private final AtomicBoolean menuOpenQueued = new AtomicBoolean();
    @Setter
    @Getter
    private volatile WardrobeStatus wardrobeStatus;
    @Getter
    @Setter
    private Menu lastOpenMenu;

    private NMSPacketBuilder packetBuilder = NMSHandlers.getHandler().getPacketBuilder();
    private NMSPacketSender packetSender = NMSHandlers.getHandler().getPacketSender();

    public UserWardrobeManager(CosmeticUser user, Wardrobe wardrobe) {
        World world = user.getEntity().getWorld();
        NPC_ID = me.lojosho.hibiscuscommons.util.ServerUtils.getNextEntityId(world);
        ARMORSTAND_ID = me.lojosho.hibiscuscommons.util.ServerUtils.getNextEntityId(world);
        backpackPreviewId = me.lojosho.hibiscuscommons.util.ServerUtils.getNextEntityId(world);
        WARDROBE_UUID = UUID.randomUUID();
        this.user = user;

        this.wardrobe = wardrobe;
        this.wardrobeLocation = wardrobe.getLocation();

        this.exitLocation = wardrobeLocation.getLeaveLocation();
        this.viewingLocation = wardrobeLocation.getViewerLocation();
        this.npcLocation = wardrobeLocation.getNpcLocation();

        String defaultMenu = wardrobe.getDefaultMenu();
        lastOpenMenu = defaultMenu == null ? null : Menus.getMenu(defaultMenu);
        if (lastOpenMenu == null) {
            if (defaultMenu != null) {
                MessagesUtil.sendDebugMessages("Unable to set menu (" + defaultMenu + ") in wardrobe " + wardrobe.getId() + ". Defaulting to the menu defined in config.yml", Level.WARNING);
            }
            lastOpenMenu = Menus.getDefaultMenu();
        }

        wardrobeStatus = WardrobeStatus.SETUP;
    }

    public void start() {
        setWardrobeStatus(WardrobeStatus.STARTING);
        Player player = user.getPlayer();

        this.originalGamemode = player.getGameMode();
        if (WardrobeSettings.isReturnLastLocation()) {
            this.exitLocation = player.getLocation().clone();
        }

        user.hidePlayer();
        if (!Bukkit.getServer().getAllowFlight()) player.setAllowFlight(true);
        List<Player> viewer = Collections.singletonList(player);

        MessagesUtil.sendMessage(player, "opened-wardrobe");

        Runnable run = () -> {
            if (!player.isOnline()) {
                end();
                return;
            }

            user.despawnBackpack();
            List<PacketWrapper> viewerPackets = new ArrayList<>();

            // Armorstand
            viewerPackets.add(packetBuilder.buildEntitySpawnPacket(ARMORSTAND_ID, UUID.randomUUID(), EntityType.ARMOR_STAND, viewingLocation));
            viewerPackets.add(packetBuilder.buildEntityMetadataPacket(ARMORSTAND_ID, HMCCPacketManager.getInvisibleArmorStandData()));
            viewerPackets.add(packetBuilder.buildEntityTeleportPacket(ARMORSTAND_ID, viewingLocation.getX(), viewingLocation.getY(), viewingLocation.getZ(), viewingLocation.getYaw(), viewingLocation.getPitch(), false));
            viewerPackets.add(packetBuilder.buildEntityRotateHeadPacket(ARMORSTAND_ID, viewingLocation));

            // Player
            HMCCServerUtils.teleport(player, viewingLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
            player.setInvisible(true);
            viewerPackets.add(packetBuilder.buildPlayerGamemodeChangePacket(GameMode.SPECTATOR));
            viewerPackets.add(packetBuilder.buildEntityCameraPacket(ARMORSTAND_ID));

            // NPC
            npcName = "Mannequin";
            if (npcName.length() >= 16) {
                npcName = npcName.substring(0, 15);
            }
            viewerPackets.add(packetBuilder.buildPlayerInfoAddPacket(player, NPC_ID, WARDROBE_UUID, npcName));
            viewerPackets.add(packetBuilder.buildEntitySpawnPacket(NPC_ID, WARDROBE_UUID, EntityType.PLAYER, npcLocation));
            viewerPackets.add(packetBuilder.buildEntityMetadataPacket(NPC_ID, HMCCPacketManager.getPlayerOverlayMetaData()));
            viewerPackets.add(packetBuilder.buildPlayerScoreboardRemovePacket(player, npcName));
            viewerPackets.add(packetBuilder.buildPlayerScoreboardCreatePacket(player, npcName));
            viewerPackets.add(packetBuilder.buildPlayerScoreboardAddPlayersPacket(player, npcName));
            AttributeInstance scaleAttribute = user.getPlayer().getAttribute(Attribute.SCALE);
            if (scaleAttribute != null) {
                mannequinScale = scaleAttribute.getValue();
                viewerPackets.add(packetBuilder.buildEntityAttributePacket(NPC_ID, Attribute.SCALE, mannequinScale));
            }

            // Location
            viewerPackets.add(packetBuilder.buildEntityRotateHeadPacket(NPC_ID, npcLocation));
            viewerPackets.add(packetBuilder.buildEntityRotatePacket(NPC_ID, npcLocation, true));

            removeClickHandler = WardrobeClickHandler.register(player, () -> requestMenuOpen(player));
            packetSender.sendBundle(viewerPackets, viewer);

            if (user.hasCosmeticInSlot(CosmeticSlot.BALLOON)) {
                if (user.getBalloonManager() == null) user.respawnBalloon();
                if (user.isBalloonSpawned()) {
                    CosmeticBalloonType cosmetic = (CosmeticBalloonType) user.getCosmetic(CosmeticSlot.BALLOON);
                    user.getBalloonManager().sendRemoveLeashPacket(viewer);
                    user.getBalloonManager().sendLeashPacket(NPC_ID);
                    //PacketManager.sendLeashPacket(VIEWER.getBalloonEntity().getModelId(), NPC_ID, viewer);

                    Location balloonLocation = npcLocation.clone().add(cosmetic.getBalloonOffset());
                    HMCCPacketManager.sendTeleportPacket(user.getBalloonManager().getPufferfishBalloonId(), balloonLocation, false, viewer);
                    user.getBalloonManager().setLocation(balloonLocation);
                }
            }

            if (WardrobeSettings.isEnabledBossbar()) {
                float progress = WardrobeSettings.getBossbarProgress();
                Component message = MessagesUtil.processStringNoKey(player, WardrobeSettings.getBossbarMessage());

                bossBar = BossBar.bossBar(message, progress, WardrobeSettings.getBossbarColor(), WardrobeSettings.getBossbarOverlay());
                //Audience target = BukkitAudiences.create(HMCCosmeticsPlugin.getInstance()).player(player);

                player.showBossBar(bossBar);
            }

            this.active = true;
            update();
            setWardrobeStatus(WardrobeStatus.RUNNING);
            if (WardrobeSettings.isEnterOpenMenu() && lastOpenMenu != null) lastOpenMenu.openMenu(user);
        };


        if (WardrobeSettings.isEnabledTransition()) {
            MessagesUtil.sendTitle(
                    user.getPlayer(),
                    WardrobeSettings.getTransitionText(),
                    WardrobeSettings.getTransitionFadeIn(),
                    WardrobeSettings.getTransitionStay(),
                    WardrobeSettings.getTransitionFadeOut()
            );
            int delay = WardrobeSettings.getTransitionDelay();
            if (delay > 0) {
                HMCCosmeticsPlugin.getScheduler().runTaskLater(player, run, delay);
            } else {
                run.run();
            }
        } else {
            run.run();
        }

    }

    public void end() {
        setWardrobeStatus(WardrobeStatus.STOPPING);
        if (removeClickHandler != null) {
            removeClickHandler.run();
            removeClickHandler = null;
        }
        if (wardrobeTask != null) {
            wardrobeTask.cancel();
            wardrobeTask = null;
        }
        Player player = user.getPlayer();

        if (player == null) return;
        List<Player> viewer = Collections.singletonList(player);
        if (!Bukkit.getServer().getAllowFlight()) player.setAllowFlight(false);
        MessagesUtil.sendMessage(player, "closed-wardrobe");

        Runnable run = () -> {
            this.active = false;

            // For Wardrobe Temp Cosmetics
            for (Cosmetic cosmetic : user.getCosmetics()) {
                MessagesUtil.sendDebugMessages("Checking... " + cosmetic.getId());
                if (!user.canEquipCosmetic(cosmetic)) {
                    MessagesUtil.sendDebugMessages("Unable to keep " + cosmetic.getId());
                    user.removeCosmeticSlot(cosmetic.getSlot());
                }
            }

            // NPC
            removeBackpackPreview(viewer);
            if (user.isBalloonSpawned()) user.getBalloonManager().sendRemoveLeashPacket();
            HMCCPacketManager.sendEntityDestroyPacket(NPC_ID, viewer); // Success
            HMCCPacketManager.sendRemovePlayerPacket(player, WARDROBE_UUID, viewer); // Success

            // Player
            packetBuilder.buildEntityCameraPacket(player.getEntityId()).sendPacket(viewer);
            user.getPlayer().setInvisible(false);

            // Armorstand
            HMCCPacketManager.sendEntityDestroyPacket(ARMORSTAND_ID, viewer); // Sucess

            //PacketManager.sendEntityDestroyPacket(player.getEntityId(), viewer); // Success
            if (WardrobeSettings.isForceExitGamemode()) {
                MessagesUtil.sendDebugMessages("Force Exit Gamemode " + WardrobeSettings.getExitGamemode());
                player.setGameMode(WardrobeSettings.getExitGamemode());
                packetBuilder.buildPlayerGamemodeChangePacket(WardrobeSettings.getExitGamemode()).sendPacket(viewer);
            } else {
                MessagesUtil.sendDebugMessages("Original Gamemode " + this.originalGamemode);
                player.setGameMode(this.originalGamemode);
                packetBuilder.buildPlayerGamemodeChangePacket(this.originalGamemode).sendPacket(viewer);
            }
            user.showPlayer();

            Location destination = Objects.requireNonNullElseGet(exitLocation, () -> player.getWorld().getSpawnLocation());
            CompletableFuture<Boolean> teleport = HMCCServerUtils.teleportWithResult(player, destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
            restoreBackpackAfterExit(player, teleport);

            HashMap<EquipmentSlot, ItemStack> items = new HashMap<>();
            for (EquipmentSlot slot : HMCCInventoryUtils.getPlayerArmorSlots()) {
                ItemStack item = player.getInventory().getItem(slot);
                items.put(slot, item);
            }
            /*
            if (WardrobeSettings.isEquipPumpkin()) {
                items.put(EquipmentSlot.HEAD, player.getInventory().getHelmet());
            }
             */
            packetBuilder.buildEntityEquipmentSlotUpdatePacket(player.getEntityId(), items).sendPacket(viewer);

            if (WardrobeSettings.isEnabledBossbar()) {
                //Audience target = BukkitAudiences.create(HMCCosmeticsPlugin.getInstance()).player(player);
                player.hideBossBar(bossBar);
            }

            user.updateCosmetic();
        };
        run.run();
    }

    void restoreBackpackAfterExit(Player player, CompletableFuture<Boolean> teleport) {
        teleport.whenComplete((success, failure) -> {
            if (failure != null) {
                HMCCosmeticsPlugin.getInstance().getLogger().log(Level.WARNING, "Could not teleport player out of wardrobe " + wardrobe.getId(), failure);
            }
            // Wait for both the teleport and leaveWardrobe clearing this manager before mounting the backpack.
            HMCCServerUtils.runOnEntityLater(player, () -> {
                if (player.isOnline() && user.getPlayer() == player && !user.isInWardrobe()
                        && user.hasCosmeticInSlot(CosmeticSlot.BACKPACK)) {
                    user.refreshWorldState();
                    user.refreshBackpackPackets();
                }
            }, 1);
        });
    }

    public boolean requestMenuOpen(Player player) {
        if (wardrobeStatus != WardrobeStatus.RUNNING) return false;
        if (menuOpenQueued.compareAndSet(false, true)) {
            HMCCServerUtils.runOnEntityLater(player, () -> {
                menuOpenQueued.set(false);
                if (wardrobeStatus != WardrobeStatus.RUNNING || !player.isOnline() || user.getWardrobeManager() != this) return;
                // The player's default container has four crafting slots and one result slot.
                if (!(player.getOpenInventory().getTopInventory() instanceof CraftingInventory crafting)
                        || crafting.getSize() != 5) return;
                if (lastOpenMenu != null) lastOpenMenu.openMenu(user);
            }, 1);
        }
        return true;
    }

    void updateBackpackPreview(Player player, Location location) {
        // A world transfer clears client-only entities. Wait for the player to reach the wardrobe.
        if (!player.getWorld().equals(viewingLocation.getWorld())) {
            backpackPreviewItem = null;
            return;
        }

        List<Player> viewer = List.of(player);
        Cosmetic backpack = user.getCosmetic(CosmeticSlot.BACKPACK);
        if (backpack == null || user.isHidden()) {
            removeBackpackPreview(viewer);
            return;
        }

        ItemStack item = user.getUserCosmeticItem(backpack);
        if (item.isEmpty()) {
            removeBackpackPreview(viewer);
            return;
        }

        List<PacketWrapper> packets = new ArrayList<>();
        // The mannequin needs its own stand. The normal backpack can be cleared by teleports
        // or attached to the player's first-person passenger chain.
        boolean needsSpawn = backpackPreviewItem == null;
        boolean itemChanged = !item.equals(backpackPreviewItem);
        if (needsSpawn) {
            packets.addAll(HMCCPacketManager.getInvisibleArmorStand(backpackPreviewId, location, UUID.randomUUID()));
            if (mannequinScale != 1.0) {
                packets.add(packetBuilder.buildEntityAttributePacket(backpackPreviewId, Attribute.SCALE, mannequinScale));
            }
        }
        if (itemChanged) {
            packets.add(packetBuilder.buildEntityEquipmentSlotUpdatePacket(backpackPreviewId, Map.of(EquipmentSlot.HEAD, item)));
        }
        if (needsSpawn) {
            packets.add(packetBuilder.buildEntityMountPacket(NPC_ID, new int[]{backpackPreviewId}));
        }
        packets.add(packetBuilder.buildEntityRotatePacket(backpackPreviewId, location, false));
        packets.add(packetBuilder.buildEntityRotateHeadPacket(backpackPreviewId, location));
        packetSender.sendBundle(packets, viewer);

        if (itemChanged) backpackPreviewItem = item.clone();
    }

    private void removeBackpackPreview(List<Player> viewer) {
        if (backpackPreviewItem == null) return;
        HMCCPacketManager.sendEntityDestroyPacket(backpackPreviewId, viewer);
        backpackPreviewItem = null;
    }

    private void update() {
        final AtomicInteger data = new AtomicInteger();
        Player playerEntity = user.getPlayer();
        if (playerEntity == null) return;

        wardrobeTask = HMCCosmeticsPlugin.getScheduler().runTaskTimer(playerEntity, () -> {
                Player player = user.getPlayer();
                if (!active || player == null) {
                    MessagesUtil.sendDebugMessages("WardrobeEnd[user=" + user.getUniqueId() + ",reason=Active is false]");
                    if (wardrobeTask != null) {
                        wardrobeTask.cancel();
                        wardrobeTask = null;
                    }
                    return;
                }
                MessagesUtil.sendDebugMessages("WardrobeUpdate[user=" + user.getUniqueId() + ",status=" + getWardrobeStatus() + "]");
                List<Player> viewer = Collections.singletonList(player);
                List<Player> outsideViewers = HMCCPacketManager.getViewers(viewingLocation);
                outsideViewers.remove(player);

                Location location = npcLocation;
                int yaw = data.get();
                location.setYaw(yaw);

                HMCCPacketManager.sendRotateHeadPacket(NPC_ID, location, viewer);
                user.hidePlayer();
                int rotationSpeed = WardrobeSettings.getRotationSpeed();
                int newYaw = HMCCServerUtils.getNextYaw(yaw - 30, rotationSpeed);
                location.setYaw(newYaw);
                packetBuilder.buildEntityRotatePacket(NPC_ID, newYaw, 0, false).sendPacket(viewer);
                int nextyaw = HMCCServerUtils.getNextYaw(yaw, rotationSpeed);
                data.set(nextyaw);

                for (CosmeticSlot slot : CosmeticSlot.values().values()) {
                    HMCCPacketManager.equipmentSlotUpdate(NPC_ID, user, slot, viewer);
                }

                updateBackpackPreview(player, location);

                if (user.hasCosmeticInSlot(CosmeticSlot.BALLOON) && user.isBalloonSpawned()) {
                    // The two lines below broke, solved by listening to PlayerCosmeticPostEquipEvent
                    //PacketManager.sendTeleportPacket(user.getBalloonManager().getPufferfishBalloonId(), npcLocation.add(Settings.getBalloonOffset()), false, viewer);
                    //user.getBalloonManager().getModelEntity().teleport(npcLocation.add(Settings.getBalloonOffset()));
                    user.getBalloonManager().sendRemoveLeashPacket(outsideViewers);
                    if (user.getBalloonManager().getBalloonType() != UserBalloonManager.BalloonType.MODELENGINE) {
                        HMCCPacketManager.sendEntityDestroyPacket(user.getBalloonManager().getModelId(), outsideViewers);
                    }
                    user.getBalloonManager().sendLeashPacket(NPC_ID);
                }

                if (WardrobeSettings.isEquipPumpkin()) {
                    HMCCPacketManager.equipmentSlotUpdate(user.getPlayer().getEntityId(), EquipmentSlot.HEAD, new ItemStack(Material.CARVED_PUMPKIN), viewer);
                } else {
                    HMCCPacketManager.equipmentSlotUpdate(user.getPlayer(), true, viewer); // Optifine dumbassery
                }
        }, 0, 2);
    }

    public enum WardrobeStatus {
        SETUP,
        STARTING,
        RUNNING,
        STOPPING,
    }

}
