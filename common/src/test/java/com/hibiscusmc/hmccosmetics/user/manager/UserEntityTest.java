package com.hibiscusmc.hmccosmetics.user.manager;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.user.CosmeticUsers;
import com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager;
import com.hibiscusmc.hmccosmetics.util.search.PlayerSearchManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class UserEntityTest {
    @Test
    void keepsOwnerWhenNearbyPlayerSearchIsEmptyDuringWorldHandoff() {
        UUID id = UUID.randomUUID();
        Player player = mock(Player.class);
        World world = mock(World.class);
        Location location = new Location(world, 0, 64, 0);
        when(player.getWorld()).thenReturn(world);
        when(player.getUniqueId()).thenReturn(id);
        HMCCosmeticsPlugin plugin = mock(HMCCosmeticsPlugin.class);
        PlayerSearchManager search = mock(PlayerSearchManager.class);
        when(plugin.getPlayerSearchManager()).thenReturn(search);
        when(search.getPlayersInRange(any(), anyDouble())).thenReturn(List.of());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<HMCCosmeticsPlugin> plugins = mockStatic(HMCCosmeticsPlugin.class);
             MockedStatic<Settings> settings = mockStatic(Settings.class);
             MockedStatic<CosmeticUsers> users = mockStatic(CosmeticUsers.class);
             MockedStatic<HMCCPacketManager> packets = mockStatic(HMCCPacketManager.class)) {
            bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(player);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            plugins.when(HMCCosmeticsPlugin::getInstance).thenReturn(plugin);
            UserEntity entity = new UserEntity(id);
            entity.setIds(List.of(42));

            assertEquals(List.of(player), entity.refreshViewers(location));
            assertEquals(List.of(), entity.refreshViewers(location));
            assertEquals(List.of(player), entity.getViewers());
            packets.verifyNoInteractions();
        }
    }
}
