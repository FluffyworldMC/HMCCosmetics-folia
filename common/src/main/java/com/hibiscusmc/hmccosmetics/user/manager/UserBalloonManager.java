package com.hibiscusmc.hmccosmetics.user.manager;

import com.github.Anon8281.universalScheduler.UniversalScheduler;
import com.github.Anon8281.universalScheduler.scheduling.schedulers.TaskScheduler;
import com.github.Anon8281.universalScheduler.scheduling.tasks.MyScheduledTask;
import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.util.HMCCServerUtils;
import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticBalloonType;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.user.CosmeticUsers;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager;
import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.entity.data.BukkitEntityData;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import lombok.Getter;
import me.lojosho.hibiscuscommons.hooks.Hooks;
import me.lojosho.hibiscuscommons.nms.NMSHandlers;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class UserBalloonManager {

    private static final int MAX_TELEPORT_RETRIES = 100;
    private static final Set<UserBalloonManager> ANIMATED_BALLOONS = ConcurrentHashMap.newKeySet();
    private static MyScheduledTask animationTask;

    private final CosmeticUser user;
    @Getter
    private BalloonType balloonType;
    private CosmeticBalloonType cosmeticBalloonType;
    @Getter
    private UserBalloonPufferfish pufferfish;
    private final ArmorStand modelEntity;
    /**
     * Last known balloon location. Reading {@link #modelEntity}'s location is only legal on the
     * region thread that owns it, so callers read this cache instead.
     */
    private volatile Location location;
    /**
     * Location the balloon should follow, without the floating animation applied. Kept separate so
     * the animation can bob around it without drifting away from the player.
     */
    private volatile Location baseLocation;
    /**
     * Floating animation state. The animation runs on one shared task and only updates these
     * fields, the actual entity movement still goes through the region-aware teleport queue.
     */
    private volatile double bobPhase;
    private volatile double swayPhase;
    private volatile float animationSway;
    private int animationTicks;
    /**
     * Teleport bookkeeping. Region-threaded servers only allow one region handoff per entity at a
     * time and reject teleports made from inside a move event, so requests are coalesced and
     * executed on the balloon's own region thread on the next tick.
     */
    private final Object teleportLock = new Object();
    private boolean teleportScheduled;
    private boolean teleportInFlight;
    private boolean teleportRetryScheduled;
    private volatile boolean removed;
    private int teleportRetries;

    public UserBalloonManager(CosmeticUser user, @NotNull Location location) {
        this.user = user;
        this.location = location.clone();
        this.baseLocation = location.clone();
        this.pufferfish = new UserBalloonPufferfish(user.getUniqueId(), NMSHandlers.getHandler().getUtilHandler().getNextEntityId(location.getWorld()), UUID.randomUUID());
        this.modelEntity = location.getWorld().spawn(location, ArmorStand.class, (e) -> {
            e.setInvisible(true);
            e.setGravity(false);
            e.setSilent(true);
            e.setInvulnerable(true);
            e.setSmall(true);
            e.setMarker(true);
            e.setPersistent(false);
            e.setAI(false);
            e.getPersistentDataContainer().set(HMCCServerUtils.getCosmemeticMobKey(), PersistentDataType.BOOLEAN, true);
        });

        ANIMATED_BALLOONS.add(this);
        ensureAnimationTask();
    }

    /**
     * Starts the single shared animation task used by every balloon. Keeping one task instead of
     * one task per balloon is much lighter, and running it on the global scheduler means it keeps
     * working while a balloon is retired during a cross-region handoff.
     */
    private static void ensureAnimationTask() {
        synchronized (UserBalloonManager.class) {
            if (animationTask != null && !animationTask.isCancelled()) return;

            animationTask = HMCCosmeticsPlugin.getScheduler().runTaskTimer(
                    HMCCosmeticsPlugin.getInstance(),
                    () -> {
                        for (final UserBalloonManager balloon : ANIMATED_BALLOONS) {
                            try {
                                balloon.tickAnimation();
                            } catch (Exception exception) {
                                MessagesUtil.sendDebugMessages("Balloon animation failed: " + exception.getMessage(), Level.WARNING);
                            }
                        }
                    },
                    1L,
                    1L
            );
        }
    }

    public void spawnModel(@NotNull CosmeticBalloonType cosmeticBalloonType, Color color) {
        // redo this
        if (cosmeticBalloonType.getModelName() != null && Hooks.isActiveHook("ModelEngine")) {
            balloonType = BalloonType.MODELENGINE;
        } else {
            if (cosmeticBalloonType.getItem() != null) {
                balloonType = BalloonType.ITEM;
            } else {
                balloonType = BalloonType.NONE;
            }
        }
        this.cosmeticBalloonType = cosmeticBalloonType;
        MessagesUtil.sendDebugMessages("balloontype is " + balloonType);

        if (balloonType == BalloonType.MODELENGINE) {
            String id = cosmeticBalloonType.getModelName();
            MessagesUtil.sendDebugMessages("Attempting Spawning for " + id);
            if (ModelEngineAPI.getBlueprint(id) == null) {
                MessagesUtil.sendDebugMessages("Invalid Model Engine Blueprint " + id, Level.SEVERE);
                return;
            }
            ModeledEntity modeledEntity = ModelEngineAPI.getOrCreateModeledEntity(modelEntity);
            ActiveModel model = ModelEngineAPI.createActiveModel(ModelEngineAPI.getBlueprint(id));
            model.setCanHurt(false);
            modeledEntity.addModel(model, false);

            if (color != null) {
                modeledEntity.getModels().forEach((d, singleModel) -> {
                    if (cosmeticBalloonType.isDyeablePart(d)) {
                        singleModel.setDefaultTint(color);
                        singleModel.getModelRenderer().sendToClient(ModelEngineAPI.getNMSHandler().createParsers());
                    }
                });
            }

            BukkitEntityData data = (BukkitEntityData) modeledEntity.getBase().getData();
            data.setBlockedCullIgnoreRadius((double) Settings.getViewDistance());
            data.getTracked().setPlayerPredicate(this::playerCheck);
            return;
        }
        if (balloonType == BalloonType.ITEM) {
            modelEntity.getEquipment().setHelmet(cosmeticBalloonType.getItem());
        }
    }

    public void remove() {
        ANIMATED_BALLOONS.remove(this);
        synchronized (this.teleportLock) {
            this.removed = true;
        }
        // This code is like a brick road, always bumpy.
        // Basically, the balloon viewers ignore people in wardrobe, which well, if your the user in the wardrobe, ain't including you.
        // This manually passes in the viewers for it to destroy, which includes the person in the wardrobe
        if (user.getPlayer() != null && user.isInWardrobe()) pufferfish.destroyPufferfish(List.of(user.getPlayer()));
        else pufferfish.destroyPufferfish();

        final Entity entity = this.modelEntity;
        final BalloonType type = this.balloonType;
        this.cosmeticBalloonType = null;

        final Runnable removal = () -> {
            if (type == BalloonType.MODELENGINE) {
                final ModeledEntity modeledEntity = ModelEngineAPI.getModeledEntity(entity);
                if (modeledEntity == null) {
                    MessagesUtil.sendDebugMessages("Balloon Removal Failed - Model Entity is Null");
                } else {
                    modeledEntity.destroy();
                    MessagesUtil.sendDebugMessages("Balloon Model Engine Removal");
                }
            }

            entity.remove();
            MessagesUtil.sendDebugMessages("Balloon Entity Removed");
        };

        // The balloon follows the player through async teleports, so it can briefly be owned by a
        // different region than the one running this code. Entity state may only be touched on the
        // region that owns it, otherwise Folia throws a thread ownership exception.
        final TaskScheduler scheduler = HMCCosmeticsPlugin.getScheduler();
        if (scheduler.isEntityThread(entity)) {
            removal.run();
        } else {
            // Retries if the entity is currently retired by a cross-region handoff.
            HMCCServerUtils.runOnEntityLater(entity, removal, 0L);
        }
    }

    /**
     * Updates the model entity's helmet on the region thread that owns it.
     *
     * @param item the item to show, or {@code null} to clear the equipment
     */
    private void setHelmet(@Nullable ItemStack item) {
        final Runnable update = () -> {
            if (item == null) {
                modelEntity.getEquipment().clear();
            } else {
                modelEntity.getEquipment().setHelmet(item);
            }
        };

        final TaskScheduler scheduler = HMCCosmeticsPlugin.getScheduler();
        if (scheduler.isEntityThread(modelEntity)) {
            update.run();
        } else {
            scheduler.runTask(modelEntity, update);
        }
    }

    public void addPlayerToModel(final CosmeticUser user, final CosmeticBalloonType cosmeticBalloonType) {
        addPlayerToModel(user, cosmeticBalloonType, null);
    }

    public void addPlayerToModel(final CosmeticUser user, final CosmeticBalloonType cosmeticBalloonType, Color color) {
        if (balloonType == BalloonType.MODELENGINE) {
            final ModeledEntity model = ModelEngineAPI.getModeledEntity(modelEntity);
            if (model == null) {
                spawnModel(cosmeticBalloonType, color);
                MessagesUtil.sendDebugMessages("model is null");
                return;
            }

            MessagesUtil.sendDebugMessages("Show to player");
            return;
        }
        if (balloonType == BalloonType.ITEM) {
            setHelmet(user.getUserCosmeticItem(cosmeticBalloonType));
        }
    }
    public void removePlayerFromModel(final Player viewer) {
        if (balloonType == BalloonType.MODELENGINE) {
            final ModeledEntity model = ModelEngineAPI.getModeledEntity(modelEntity);
            if (model == null) return;

            MessagesUtil.sendDebugMessages("Hidden from player");
            return;
        }
        if (balloonType == BalloonType.ITEM) {
            setHelmet(null);
            return;
        }
    }

    public Entity getModelEntity() {
        return this.modelEntity;
    }


    public int getPufferfishBalloonId() {
        return pufferfish.getPufferFishEntityId();
    }

    public UUID getPufferfishBalloonUniqueId() {
        return pufferfish.getUuid();
    }

    public UUID getModelUnqiueId() {
        return getModelEntity().getUniqueId();
    }

    public int getModelId() {
        return getModelEntity().getEntityId();
    }

    public Location getLocation() {
        return this.location;
    }

    public void setLocation(Location location) {
        if (this.removed) return;

        final Location base = location.clone();

        if (Settings.isBalloonAnimationFacePlayer()) {
            final Player player = user.getPlayer();
            if (player != null) {
                final Location eyes = player.getEyeLocation();
                final double dx = eyes.getX() - base.getX();
                final double dz = eyes.getZ() - base.getZ();
                if (dx * dx + dz * dz > 1.0E-4) {
                    // Minecraft yaw: 0 = south (+Z), 90 = west (-X).
                    base.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                }
                base.setPitch(0.0F);
            }
        }

        this.baseLocation = base;
        this.location = currentAnimatedLocation(base);

        if (!UniversalScheduler.isFolia && !UniversalScheduler.isCanvas) {
            applyAnimationPose();
            HMCCServerUtils.teleport(this.modelEntity, this.location);
            return;
        }

        requestTeleport();
    }

    /**
     * Advances the floating animation. Called from the shared animation task, which runs on the
     * global region thread, so this method only updates cached state and then asks the balloon's
     * own region to move the entity.
     */
    private void tickAnimation() {
        if (this.removed) {
            ANIMATED_BALLOONS.remove(this);
            return;
        }
        if (!Settings.isBalloonAnimationEnabled()) return;
        if (this.balloonType == null || this.balloonType == BalloonType.NONE) return;
        if (Settings.getBalloonAnimationBobHeight() <= 0.0 && Settings.getBalloonAnimationSwayAngle() <= 0.0) return;

        final int interval = Math.max(1, Settings.getBalloonAnimationInterval());
        if (++this.animationTicks < interval) return;
        this.animationTicks = 0;

        this.bobPhase += Settings.getBalloonAnimationBobSpeed() * interval;
        this.swayPhase += Settings.getBalloonAnimationSwaySpeed() * interval;
        this.animationSway = (float) (Math.sin(this.swayPhase) * Settings.getBalloonAnimationSwayAngle());

        final Location base = this.baseLocation;
        if (base == null) return;

        final Location target = currentAnimatedLocation(base);
        this.location = target;

        if (UniversalScheduler.isFolia || UniversalScheduler.isCanvas) {
            requestTeleport();
            return;
        }

        applyAnimationPose();
        HMCCServerUtils.teleport(this.modelEntity, target);
    }

    private Location currentAnimatedLocation(final Location base) {
        if (!Settings.isBalloonAnimationEnabled()) return base.clone();
        final double bob = Math.sin(this.bobPhase) * Settings.getBalloonAnimationBobHeight();
        return base.clone().add(0, bob, 0);
    }

    /**
     * Applies the sway to the armor stand's head pose, which tilts the balloon item. Must run on the
     * region thread that owns the balloon.
     */
    private void applyAnimationPose() {
        final float sway = Settings.isBalloonAnimationEnabled() ? this.animationSway : 0.0F;
        this.modelEntity.setHeadPose(new EulerAngle(0, 0, Math.toRadians(sway)));
    }

    /**
     * Queues the model entity to be teleported to the latest cached location.
     *
     * <p>This must never teleport directly: it is called from {@code PlayerMoveEvent}, and Canvas
     * does not support teleporting there. On top of that, a second cross-region handoff cannot be
     * started while the previous one is still in progress, which is what caused
     * {@code IllegalStateException: Cannot teleport async when another region handoff is present!}.
     * The actual teleport is therefore run on the balloon's region thread on the next tick and
     * repeated requests are coalesced into a single teleport to the newest location.</p>
     */
    private void requestTeleport() {
        synchronized (this.teleportLock) {
            if (this.removed || this.teleportScheduled || this.teleportInFlight || this.teleportRetryScheduled) {
                return;
            }
            this.teleportScheduled = true;
        }

        scheduleTeleport();
    }

    private void scheduleTeleport() {
        boolean scheduled;
        try {
            scheduled = this.modelEntity.getScheduler().run(
                    HMCCosmeticsPlugin.getInstance(),
                    task -> {
                        synchronized (this.teleportLock) {
                            this.teleportScheduled = false;
                            if (this.removed) return;
                            this.teleportInFlight = true;
                        }
                        performTeleport();
                    },
                    () -> {
                        // The balloon was retired (usually because a region handoff started)
                        // before the queued task could run. Try again once it is back.
                        synchronized (this.teleportLock) {
                            this.teleportScheduled = false;
                        }
                        retryTeleportLater();
                    }
            ) != null;
        } catch (IllegalStateException exception) {
            // The entity was retired between the check and the scheduling attempt.
            scheduled = false;
        }

        if (scheduled) return;

        synchronized (this.teleportLock) {
            this.teleportScheduled = false;
        }
        retryTeleportLater();
    }

    /**
     * Runs on the region thread that owns the balloon.
     */
    private void performTeleport() {
        final Location requestedLocation;
        final Location destination;
        synchronized (this.teleportLock) {
            if (this.removed) {
                this.teleportInFlight = false;
                return;
            }
            requestedLocation = this.location;
            destination = requestedLocation.clone();
        }

        applyAnimationPose();

        try {
            this.modelEntity.teleportAsync(destination).whenComplete((success, throwable) -> {
                if (throwable != null) {
                    MessagesUtil.sendDebugMessages("Balloon teleport failed: " + throwable.getMessage(), Level.WARNING);
                }

                final boolean movedAgain;
                synchronized (this.teleportLock) {
                    this.teleportInFlight = false;
                    if (Boolean.TRUE.equals(success)) this.teleportRetries = 0;
                    // A newer movement or animation update replaced the cached location.
                    movedAgain = !this.removed && this.location != requestedLocation;
                }

                if (movedAgain) requestTeleport();
            });
        } catch (IllegalStateException exception) {
            // Another handoff (usually our own previous cross-world balloon teleport) is still
            // pending. Wait for it to finish instead of spamming the server with failed calls.
            MessagesUtil.sendDebugMessages("Balloon teleport postponed: " + exception.getMessage());
            synchronized (this.teleportLock) {
                this.teleportInFlight = false;
            }
            retryTeleportLater();
        }
    }

    private void retryTeleportLater() {
        synchronized (this.teleportLock) {
            if (this.removed || this.teleportRetryScheduled) return;
            if (++this.teleportRetries > MAX_TELEPORT_RETRIES) {
                this.teleportRetries = 0;
                return;
            }
            this.teleportRetryScheduled = true;
        }

        HMCCosmeticsPlugin.getScheduler().runTaskLater(HMCCosmeticsPlugin.getInstance(), () -> {
            synchronized (this.teleportLock) {
                this.teleportRetryScheduled = false;
            }
            requestTeleport();
        }, 1L);
    }

    public Vector getVelocity() {
        return getModelEntity().getVelocity();
    }

    public void setVelocity(Vector vector) {
        this.getModelEntity().setVelocity(vector);
    }

    public void sendRemoveLeashPacket(List<Player> viewer) {
        HMCCPacketManager.sendLeashPacket(getPufferfishBalloonId(), -1, viewer);
    }

    public void sendRemoveLeashPacket() {
        HMCCPacketManager.sendLeashPacket(getPufferfishBalloonId(), -1, getLocation());
    }

    public void sendLeashPacket(int entityId) {
        if (cosmeticBalloonType.isShowLead()) {
            HMCCPacketManager.sendLeashPacket(getPufferfishBalloonId(), entityId, getLocation());
        }
    }

    public enum BalloonType {
        MODELENGINE,
        ITEM,
        NONE
    }

    private boolean playerCheck(final Player player) {
        MessagesUtil.sendDebugMessages("playerCheck");
        CosmeticUser viewer = CosmeticUsers.getUser(player.getUniqueId());

        if (user.getPlayer() == player) {
            return (!user.isHidden());
        } else {
            if (user.isInWardrobe()) return false;
            MessagesUtil.sendDebugMessages("playerCheck - Not Same Player");
            if (viewer != null && viewer.isInWardrobe()) {
                MessagesUtil.sendDebugMessages("playerCheck - Viewer in Wardrobe");
                return false;
            }
        }
        return (!user.isHidden());
    }
}
