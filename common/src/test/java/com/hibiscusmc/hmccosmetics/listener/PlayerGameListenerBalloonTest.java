package com.hibiscusmc.hmccosmetics.listener;

import com.hibiscusmc.hmccosmetics.api.events.PlayerCosmeticPostEquipEvent;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticBalloonType;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.user.manager.UserBalloonManager;
import com.hibiscusmc.hmccosmetics.user.manager.UserBalloonPufferfish;
import com.hibiscusmc.hmccosmetics.user.manager.UserWardrobeManager;
import com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.List;

import static org.mockito.Mockito.*;

class PlayerGameListenerBalloonTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wardrobeBalloonUsesTheConfiguredLeadVisibility(boolean showLead) throws ClassNotFoundException {
        try (var ignored = mockConstruction(ItemStack.class)) {
            Class.forName("com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic");
        }

        World world = mock(World.class);
        Location mannequinLocation = new Location(world, 3, 64, 7);
        Vector offset = new Vector(1, 2, 0);
        Location balloonLocation = mannequinLocation.clone().add(offset);
        Player player = mock(Player.class);
        CosmeticUser user = mock(CosmeticUser.class);
        UserWardrobeManager wardrobe = mock(UserWardrobeManager.class);
        UserBalloonManager balloon = mock(UserBalloonManager.class);
        UserBalloonPufferfish pufferfish = mock(UserBalloonPufferfish.class);
        CosmeticBalloonType cosmetic = mock(CosmeticBalloonType.class);
        when(user.isInWardrobe()).thenReturn(true);
        when(user.getPlayer()).thenReturn(player);
        when(user.getWardrobeManager()).thenReturn(wardrobe);
        when(user.getBalloonManager()).thenReturn(balloon);
        when(wardrobe.getNpcLocation()).thenReturn(mannequinLocation);
        when(wardrobe.getNPC_ID()).thenReturn(5);
        when(balloon.getPufferfish()).thenReturn(pufferfish);
        when(balloon.getPufferfishBalloonId()).thenReturn(6);
        when(cosmetic.getSlot()).thenReturn(CosmeticSlot.BALLOON);
        when(cosmetic.getBalloonOffset()).thenReturn(offset);
        when(cosmetic.isShowLead()).thenReturn(showLead);

        try (MockedStatic<HMCCPacketManager> packets = mockStatic(HMCCPacketManager.class)) {
            new PlayerGameListener().onPlayerCosmeticEquip(new PlayerCosmeticPostEquipEvent(user, cosmetic));

            verify(balloon).setLocation(balloonLocation);
            if (showLead) {
                verify(pufferfish).spawnPufferfish(balloonLocation, List.of(player));
                packets.verify(() -> HMCCPacketManager.sendLeashPacket(6, 5, List.of(player)));
            } else {
                verifyNoInteractions(pufferfish);
                packets.verifyNoInteractions();
            }
        }
    }
}
