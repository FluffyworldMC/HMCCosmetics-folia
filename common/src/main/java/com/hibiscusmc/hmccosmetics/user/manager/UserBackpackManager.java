package com.hibiscusmc.hmccosmetics.user.manager;

import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticBackpackType;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager;
import lombok.Getter;
import me.lojosho.hibiscuscommons.nms.NMSHandlers;
import me.lojosho.hibiscuscommons.nms.NMSPacketBuilder;
import me.lojosho.hibiscuscommons.packets.wrapper.PacketWrapper;
import me.lojosho.hibiscuscommons.util.ServerUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class UserBackpackManager {

    @Getter
    private boolean backpackHidden;
    @Getter
    private final int invisibleArmorStand;
    private ArrayList<Integer> particleCloud = new ArrayList<>();
    @Getter
    private final CosmeticUser user;
    @Getter
    private final UserEntity entityManager;

    public UserBackpackManager(CosmeticUser user) {
        Location location = user.getEntity().getLocation();
        this.user = user;
        this.backpackHidden = false;
        this.invisibleArmorStand = ServerUtils.getNextEntityId(location.getWorld());
        this.entityManager = new UserEntity(user.getUniqueId());
        if (user.getEntity() != null) this.entityManager.refreshViewers(location); // Fixes an issue where a player, who somehow removes their potions, but doesn't have an entity produces an NPE (it's dumb)
    }

    public int getFirstArmorStandId() {
        return invisibleArmorStand;
    }

    public void spawnBackpack(CosmeticBackpackType cosmeticBackpackType) {
        MessagesUtil.sendDebugMessages("spawnBackpack Bukkit - Start");

        spawn(cosmeticBackpackType);
    }

    private void spawn(CosmeticBackpackType cosmeticBackpackType) {
        Location location = user.getEntity().getLocation();
        getEntityManager().setIds(List.of(invisibleArmorStand));
        getEntityManager().refreshViewers(location);
        getEntityManager().teleport(location);
        final List<Player> outsideViewers = new ArrayList<>(getEntityManager().getViewers());
        // The owner must always receive the spawn bundle too. On a world change the viewer list can
        // briefly be empty before the player is added to the new world, which would leave the
        // packet-only backpack invisible even though it is still equipped.
        if (user.getPlayer() != null && !outsideViewers.contains(user.getPlayer())) {
            outsideViewers.add(user.getPlayer());
        }

        NMSPacketBuilder packetBuilder = NMSHandlers.getHandler().getPacketBuilder();

        final List<PacketWrapper> outsideBundle = new ArrayList<>(16);
        final List<PacketWrapper> ownerBundle = new ArrayList<>(16);

        outsideBundle.addAll(HMCCPacketManager.getInvisibleArmorStand(getFirstArmorStandId(), location, UUID.randomUUID()));

        double scaleValue = 1;
        if (user.getPlayer() != null) {
            AttributeInstance scaleAttribute = user.getPlayer().getAttribute(Attribute.SCALE);
            if (scaleAttribute != null) {
                scaleValue = scaleAttribute.getValue();
                outsideBundle.add(packetBuilder.buildEntityAttributePacket(getFirstArmorStandId(), Attribute.SCALE, scaleValue));
            }
        }

        Entity entity = user.getEntity();

        if (cosmeticBackpackType.isFirstPersonCompadible() && user.getPlayer() != null) {
            // The owner needs the armor stand before any equipment or mount packets, and must
            // not receive the third-person mount afterwards (it replaces the cloud chain).
            outsideViewers.remove(user.getPlayer());
            ownerBundle.addAll(outsideBundle);
            for (int i = particleCloud.size(); i < Math.max(1, cosmeticBackpackType.getHeight()); i++) {
                int entityId = ServerUtils.getNextEntityId(location.getWorld());
                //if (scaleValue != 1) ownerBundle.add(packetBuilder.buildEntityAttributePacket(entityId, Attribute.SCALE, scaleValue)); // Todo: figure out how to impl scaling, area clouds can not scale and will kick the player if sent
                this.particleCloud.add(entityId);
            }
            // A world switch clears these entities on the client, even when their IDs are
            // still cached here. Re-send every cloud on refresh, not just newly allocated IDs.
            for (int entityId : particleCloud) {
                ownerBundle.addAll(HMCCPacketManager.getCloudHandleEffect(entityId, location, UUID.randomUUID()));
            }
            if (!user.isHidden()) ownerBundle.add(packetBuilder.buildEntityEquipmentSlotUpdatePacket(getFirstArmorStandId(), Map.of(EquipmentSlot.HEAD, user.getUserCosmeticItem(cosmeticBackpackType, cosmeticBackpackType.getFirstPersonBackpack()))));
            addMountPackets(ownerBundle, packetBuilder, entity, true);
        }
        outsideBundle.add(packetBuilder.buildEntityEquipmentSlotUpdatePacket(getFirstArmorStandId(), Map.of(EquipmentSlot.HEAD, user.getUserCosmeticItem(cosmeticBackpackType))));
        addMountPackets(outsideBundle, packetBuilder, entity, false);

        if (!ownerBundle.isEmpty()) NMSHandlers.getHandler().getPacketSender().sendBundle(ownerBundle, user.getPlayer());
        NMSHandlers.getHandler().getPacketSender().sendBundle(outsideBundle, outsideViewers);

        MessagesUtil.sendDebugMessages("spawnBackpack Bukkit - Finish");
    }

    public void addMountPackets(List<PacketWrapper> packets, NMSPacketBuilder packetBuilder, Entity rider, boolean firstPerson) {
        List<Entity> realPassengers = rider.getPassengers();
        int[] passengers = new int[realPassengers.size() + 1];
        for (int i = 0; i < realPassengers.size(); i++) {
            passengers[i] = realPassengers.get(i).getEntityId();
        }
        passengers[realPassengers.size()] = firstPerson ? particleCloud.getFirst() : invisibleArmorStand;

        if (firstPerson) {
            for (int i = 1; i < particleCloud.size(); i++) {
                packets.add(packetBuilder.buildEntityMountPacket(particleCloud.get(i - 1), new int[]{particleCloud.get(i)}));
            }
            packets.add(packetBuilder.buildEntityMountPacket(particleCloud.getLast(), new int[]{invisibleArmorStand}));
        }
        packets.add(packetBuilder.buildEntityMountPacket(rider.getEntityId(), passengers));
    }

    public void despawnBackpack() {
        int[] existingPassengers = user.getEntity().getPassengers().stream()
                .mapToInt(Entity::getEntityId)
                .toArray();
        if (existingPassengers.length > 0) HMCCPacketManager.sendRidingPacket(user.getEntity().getEntityId(), existingPassengers, getEntityManager().getViewers());

        HMCCPacketManager.sendEntityDestroyPacket(invisibleArmorStand, getEntityManager().getViewers());
        if (particleCloud != null) {
            for (Integer entityId : particleCloud) {
                HMCCPacketManager.sendEntityDestroyPacket(entityId, getEntityManager().getViewers());
            }
            this.particleCloud = null;
        }
    }

    public void hideBackpack() {
        if (user.isHidden()) return;
        //getArmorStand().getEquipment().clear();
        backpackHidden = true;
    }

    public void showBackpack() {
        if (!backpackHidden) return;
        CosmeticBackpackType cosmeticBackpackType = (CosmeticBackpackType) user.getCosmetic(CosmeticSlot.BACKPACK);
        ItemStack item = user.getUserCosmeticItem(cosmeticBackpackType);
        //getArmorStand().getEquipment().setHelmet(item);
        backpackHidden = false;
    }

    public void setVisibility(boolean shown) {
        backpackHidden = shown;
    }

    public ArrayList<Integer> getAreaEffectEntityId() {
        return particleCloud;
    }

    public void setItem(ItemStack item) {
        HMCCPacketManager.equipmentSlotUpdate(getFirstArmorStandId(), EquipmentSlot.HEAD, item, getEntityManager().getViewers());
    }

    public void clearItems() {
        ItemStack item = new ItemStack(Material.AIR);
        HMCCPacketManager.equipmentSlotUpdate(getFirstArmorStandId(), EquipmentSlot.HEAD, item, getEntityManager().getViewers());
    }
}
