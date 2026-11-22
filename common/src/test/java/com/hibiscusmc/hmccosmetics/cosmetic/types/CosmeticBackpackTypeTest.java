package com.hibiscusmc.hmccosmetics.cosmetic.types;

import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.user.manager.UserBackpackManager;
import com.hibiscusmc.hmccosmetics.user.manager.UserEntity;
import com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager;
import me.lojosho.hibiscuscommons.nms.NMSHandler;
import me.lojosho.hibiscuscommons.nms.NMSHandlers;
import me.lojosho.hibiscuscommons.nms.NMSPacketBuilder;
import me.lojosho.hibiscuscommons.nms.NMSPacketSender;
import me.lojosho.hibiscuscommons.packets.wrapper.PacketWrapper;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

class CosmeticBackpackTypeTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mountsBackpackAfterSpawningItForNewViewers(boolean forceRiding) throws ClassNotFoundException {
        try (var ignored = mockConstruction(ItemStack.class)) {
            Class.forName("com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic");
        }
        CosmeticBackpackType cosmetic = mock(CosmeticBackpackType.class, CALLS_REAL_METHODS);
        CosmeticUser user = mock(CosmeticUser.class);
        UserBackpackManager backpack = mock(UserBackpackManager.class);
        UserEntity tracking = mock(UserEntity.class);
        Entity wearer = mock(Entity.class);
        Player newViewer = mock(Player.class);
        Player existingViewer = mock(Player.class);
        Location location = new Location(mock(World.class), 1, 64, 2);
        ItemStack item = mock(ItemStack.class);

        when(user.getEntity()).thenReturn(wearer);
        when(wearer.getLocation()).thenReturn(location);
        when(user.getUserBackpackManager()).thenReturn(backpack);
        when(backpack.getEntityManager()).thenReturn(tracking);
        when(backpack.getFirstArmorStandId()).thenReturn(42);
        when(tracking.refreshViewers(any(Location.class))).thenReturn(List.of(newViewer));
        when(tracking.getViewers()).thenReturn(List.of(existingViewer, newViewer));
        doReturn(item).when(cosmetic).getItem();
        when(user.getUserCosmeticItem(cosmetic, item)).thenReturn(item);

        NMSHandler handler = mock(NMSHandler.class);
        NMSPacketBuilder builder = mock(NMSPacketBuilder.class);
        NMSPacketSender sender = mock(NMSPacketSender.class);
        PacketWrapper spawn = mock(PacketWrapper.class);
        PacketWrapper equipment = mock(PacketWrapper.class);
        PacketWrapper mount = mock(PacketWrapper.class);
        when(handler.getPacketBuilder()).thenReturn(builder);
        when(handler.getPacketSender()).thenReturn(sender);
        when(builder.buildEntityEquipmentSlotUpdatePacket(42, Map.of(EquipmentSlot.HEAD, item))).thenReturn(equipment);
        doAnswer(call -> {
            call.<List<PacketWrapper>>getArgument(0).add(mount);
            return null;
        }).when(backpack).addMountPackets(anyList(), eq(builder), eq(wearer), eq(false));

        try (var handlers = mockStatic(NMSHandlers.class);
             var settings = mockStatic(Settings.class);
             var packets = mockStatic(HMCCPacketManager.class)) {
            handlers.when(NMSHandlers::getHandler).thenReturn(handler);
            settings.when(Settings::isBackpackForceRidingEnabled).thenReturn(forceRiding);
            packets.when(() -> HMCCPacketManager.getInvisibleArmorStand(eq(42), eq(location), any()))
                    .thenReturn(List.of(spawn));

            cosmetic.dispatchUpdate(user);

            verify(sender).sendBundle(List.of(spawn, equipment, mount), List.of(newViewer));
            if (forceRiding) verify(sender).sendBundle(List.of(mount), List.of(existingViewer));
            verifyNoMoreInteractions(sender);
        }
    }
}
