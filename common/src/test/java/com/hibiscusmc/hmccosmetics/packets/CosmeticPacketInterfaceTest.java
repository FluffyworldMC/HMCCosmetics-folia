package com.hibiscusmc.hmccosmetics.packets;

import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticBackpackType;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.user.CosmeticUsers;
import com.hibiscusmc.hmccosmetics.user.manager.UserBackpackManager;
import me.lojosho.hibiscuscommons.packets.PacketAction;
import me.lojosho.hibiscuscommons.packets.data.PassengerWrapper;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class CosmeticPacketInterfaceTest {
    @BeforeAll
    static void initializeCosmeticDisplayItem() throws ClassNotFoundException {
        // Cosmetic creates a fallback menu item in its static initializer. Packet tests do
        // not need item metadata or a running server's material registry.
        try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
            Class.forName("com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic");
        }
    }

    @Test
    void restoresBackpackWhenServerSendsEmptyPassengersAfterWorldChange() {
        assertPassengers(List.of(), List.of(42));
    }

    @Test
    void preservesRealPassengersWhenAddingBackpack() {
        assertPassengers(List.of(7, 8), List.of(7, 8, 42));
    }

    @Test
    void doesNotDuplicateBackpackWhenInterceptingItsOwnMountPacket() {
        assertPassengers(List.of(7, 42), List.of(7, 42));
    }

    private void assertPassengers(List<Integer> original, List<Integer> expected) {
        Player player = mock(Player.class);
        CosmeticUser user = mock(CosmeticUser.class);
        CosmeticBackpackType cosmetic = mock(CosmeticBackpackType.class);
        UserBackpackManager manager = mock(UserBackpackManager.class);
        PassengerWrapper wrapper = mock(PassengerWrapper.class);
        UUID ownerId = UUID.randomUUID();

        when(player.getEntityId()).thenReturn(1);
        when(user.getPlayer()).thenReturn(player);
        when(user.getUniqueId()).thenReturn(ownerId);
        when(user.getCosmetic(CosmeticSlot.BACKPACK)).thenReturn(cosmetic);
        when(user.getUserBackpackManager()).thenReturn(manager);
        when(manager.getFirstArmorStandId()).thenReturn(42);
        when(wrapper.getOwner()).thenReturn(1);
        when(wrapper.getPassengers()).thenReturn(original);

        try (MockedStatic<Settings> settings = mockStatic(Settings.class);
             MockedStatic<CosmeticUsers> users = mockStatic(CosmeticUsers.class)) {
            settings.when(Settings::isBackpackInterceptPassengerPacket).thenReturn(true);
            users.when(() -> CosmeticUsers.getUser(player)).thenReturn(user);
            users.when(CosmeticUsers::values).thenReturn(Set.of(user));

            assertEquals(PacketAction.CHANGED,
                    new CosmeticPacketInterface().writePassengerContent(player, wrapper));
            verify(wrapper).setPassengers(expected);
        }
    }
}
