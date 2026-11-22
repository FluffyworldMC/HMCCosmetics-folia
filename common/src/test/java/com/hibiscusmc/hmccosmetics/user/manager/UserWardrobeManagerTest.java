package com.hibiscusmc.hmccosmetics.user.manager;

import com.hibiscusmc.hmccosmetics.config.section.Wardrobe;
import com.hibiscusmc.hmccosmetics.config.section.WardrobeLocation;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticBackpackType;
import com.hibiscusmc.hmccosmetics.gui.Menu;
import com.hibiscusmc.hmccosmetics.gui.Menus;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.util.HMCCServerUtils;
import com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager;
import me.lojosho.hibiscuscommons.nms.NMSHandler;
import me.lojosho.hibiscuscommons.nms.NMSHandlers;
import me.lojosho.hibiscuscommons.nms.NMSPacketBuilder;
import me.lojosho.hibiscuscommons.nms.NMSPacketSender;
import me.lojosho.hibiscuscommons.packets.wrapper.PacketWrapper;
import me.lojosho.hibiscuscommons.util.ServerUtils;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

class UserWardrobeManagerTest {
    @Test
    void restoresEquippedBackpackOnThePlayerThreadAfterTheExitTeleport() {
        World world = mock(World.class);
        Location location = new Location(world, 0, 64, 0);
        Wardrobe wardrobe = new Wardrobe("test", new WardrobeLocation(location, location, location), null, 0, null, null);
        CosmeticUser user = mock(CosmeticUser.class);
        Player player = mock(Player.class);
        when(user.getEntity()).thenReturn(player);
        when(user.getPlayer()).thenReturn(player);
        when(user.hasCosmeticInSlot(CosmeticSlot.BACKPACK)).thenReturn(true);
        when(user.isInWardrobe()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        NMSHandler handler = mock(NMSHandler.class);
        Queue<Runnable> scheduled = new ArrayDeque<>();
        CompletableFuture<Boolean> teleport = new CompletableFuture<>();

        try (MockedStatic<NMSHandlers> handlers = mockStatic(NMSHandlers.class);
             MockedStatic<ServerUtils> server = mockStatic(ServerUtils.class);
             MockedStatic<HMCCServerUtils> scheduler = mockStatic(HMCCServerUtils.class)) {
            handlers.when(NMSHandlers::getHandler).thenReturn(handler);
            scheduler.when(() -> HMCCServerUtils.runOnEntityLater(eq(player), any(Runnable.class), eq(1L)))
                    .thenAnswer(call -> {
                        scheduled.add(call.getArgument(1));
                        return null;
                    });
            UserWardrobeManager manager = new UserWardrobeManager(user, wardrobe);

            manager.restoreBackpackAfterExit(player, teleport);
            assertTrue(scheduled.isEmpty());
            verify(user, never()).refreshBackpackPackets();

            teleport.complete(true);
            assertEquals(1, scheduled.size());
            verify(user, never()).refreshBackpackPackets();

            when(user.isInWardrobe()).thenReturn(false);
            scheduled.remove().run();
            var order = inOrder(user);
            order.verify(user).refreshWorldState();
            order.verify(user).refreshBackpackPackets();
        }
    }

    @Test
    void reopensWardrobeMenuAfterCloseOnThePlayerThread() {
        World world = mock(World.class);
        Location location = new Location(world, 0, 64, 0);
        Wardrobe wardrobe = new Wardrobe("test", new WardrobeLocation(location, location, location), null, 0, null, null);
        CosmeticUser user = mock(CosmeticUser.class);
        Player player = mock(Player.class);
        when(user.getEntity()).thenReturn(player);
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        InventoryView view = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        CraftingInventory personalInventory = mock(CraftingInventory.class);
        when(personalInventory.getSize()).thenReturn(5);
        when(player.getOpenInventory()).thenReturn(view);
        Menu defaultMenu = mock(Menu.class);
        NMSHandler handler = mock(NMSHandler.class);
        Queue<Runnable> scheduled = new ArrayDeque<>();

        try (MockedStatic<NMSHandlers> handlers = mockStatic(NMSHandlers.class);
             MockedStatic<ServerUtils> server = mockStatic(ServerUtils.class);
             MockedStatic<Menus> menus = mockStatic(Menus.class);
             MockedStatic<HMCCServerUtils> scheduler = mockStatic(HMCCServerUtils.class)) {
            handlers.when(NMSHandlers::getHandler).thenReturn(handler);
            menus.when(Menus::getDefaultMenu).thenReturn(defaultMenu);
            scheduler.when(() -> HMCCServerUtils.runOnEntityLater(eq(player), any(Runnable.class), eq(1L)))
                    .thenAnswer(call -> {
                        scheduled.add(call.getArgument(1));
                        return null;
                    });
            UserWardrobeManager manager = new UserWardrobeManager(user, wardrobe);
            when(user.getWardrobeManager()).thenReturn(manager);
            manager.setWardrobeStatus(UserWardrobeManager.WardrobeStatus.RUNNING);

            when(view.getTopInventory()).thenReturn(inventory);
            assertTrue(manager.requestMenuOpen(player));
            scheduled.remove().run();
            verifyNoInteractions(defaultMenu);

            when(view.getTopInventory()).thenReturn(personalInventory);
            assertTrue(manager.requestMenuOpen(player));
            assertTrue(manager.requestMenuOpen(player));
            assertEquals(1, scheduled.size());
            verifyNoInteractions(defaultMenu);
            scheduled.remove().run();
            verify(defaultMenu).openMenu(user);

            assertTrue(manager.requestMenuOpen(player));
            manager.setWardrobeStatus(UserWardrobeManager.WardrobeStatus.STOPPING);
            scheduled.remove().run();
            assertFalse(manager.requestMenuOpen(player));
            verifyNoMoreInteractions(defaultMenu);
        }
    }

    @Test
    void spawnsBackpackForTheMannequinBeforeEquippingAndMountingIt() throws ClassNotFoundException {
        try (var ignored = mockConstruction(ItemStack.class)) {
            Class.forName("com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic");
        }

        World world = mock(World.class);
        Location location = new Location(world, 0, 64, 0);
        Wardrobe wardrobe = new Wardrobe("test", new WardrobeLocation(location, location, location), null, 0, null, null);
        CosmeticUser user = mock(CosmeticUser.class);
        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);
        when(user.getEntity()).thenReturn(player);
        CosmeticBackpackType backpack = mock(CosmeticBackpackType.class);
        ItemStack firstItem = mock(ItemStack.class);
        ItemStack replacement = mock(ItemStack.class);
        when(firstItem.clone()).thenReturn(firstItem);
        when(replacement.clone()).thenReturn(replacement);
        when(user.getCosmetic(CosmeticSlot.BACKPACK)).thenReturn(backpack);
        when(user.getUserCosmeticItem(backpack)).thenReturn(firstItem);

        NMSHandler handler = mock(NMSHandler.class);
        NMSPacketBuilder builder = mock(NMSPacketBuilder.class);
        NMSPacketSender sender = mock(NMSPacketSender.class);
        when(handler.getPacketBuilder()).thenReturn(builder);
        when(handler.getPacketSender()).thenReturn(sender);
        PacketWrapper spawn = mock(PacketWrapper.class);
        PacketWrapper metadata = mock(PacketWrapper.class);
        PacketWrapper equipment = mock(PacketWrapper.class);
        PacketWrapper replaceEquipment = mock(PacketWrapper.class);
        PacketWrapper mount = mock(PacketWrapper.class);
        PacketWrapper rotate = mock(PacketWrapper.class);
        PacketWrapper rotateHead = mock(PacketWrapper.class);
        when(builder.buildEntityEquipmentSlotUpdatePacket(3, Map.of(EquipmentSlot.HEAD, firstItem))).thenReturn(equipment);
        when(builder.buildEntityEquipmentSlotUpdatePacket(3, Map.of(EquipmentSlot.HEAD, replacement))).thenReturn(replaceEquipment);
        when(builder.buildEntityMountPacket(eq(1), aryEq(new int[]{3}))).thenReturn(mount);
        when(builder.buildEntityRotatePacket(3, location, false)).thenReturn(rotate);
        when(builder.buildEntityRotateHeadPacket(3, location)).thenReturn(rotateHead);

        try (MockedStatic<NMSHandlers> handlers = mockStatic(NMSHandlers.class);
             MockedStatic<ServerUtils> server = mockStatic(ServerUtils.class);
             MockedStatic<HMCCPacketManager> packets = mockStatic(HMCCPacketManager.class)) {
            handlers.when(NMSHandlers::getHandler).thenReturn(handler);
            server.when(() -> ServerUtils.getNextEntityId(world)).thenReturn(1, 2, 3);
            packets.when(() -> HMCCPacketManager.getInvisibleArmorStand(eq(3), eq(location), any()))
                    .thenReturn(List.of(spawn, metadata));

            UserWardrobeManager manager = new UserWardrobeManager(user, wardrobe);
            World otherWorld = mock(World.class);
            when(player.getWorld()).thenReturn(otherWorld);
            manager.updateBackpackPreview(player, location);
            verifyNoInteractions(sender);

            when(player.getWorld()).thenReturn(world);
            manager.updateBackpackPreview(player, location);
            verify(sender).sendBundle(List.of(spawn, metadata, equipment, mount, rotate, rotateHead), List.of(player));

            when(user.getUserCosmeticItem(backpack)).thenReturn(replacement);
            manager.updateBackpackPreview(player, location);
            verify(sender).sendBundle(List.of(replaceEquipment, rotate, rotateHead), List.of(player));

            when(user.getCosmetic(CosmeticSlot.BACKPACK)).thenReturn(null);
            manager.updateBackpackPreview(player, location);
            packets.verify(() -> HMCCPacketManager.sendEntityDestroyPacket(3, List.of(player)));
            verifyNoMoreInteractions(sender);
        }
    }
}
