package com.hibiscusmc.hmccosmetics.listener;

import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.user.CosmeticUsers;
import com.hibiscusmc.hmccosmetics.util.HMCCServerUtils;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayerGameListenerTeleportTest {
    @Test
    void crossWorldTeleportRestoresBackpackWithoutChangedWorldEvent() {
        Player player = mock(Player.class);
        CosmeticUser user = mock(CosmeticUser.class);
        World source = mock(World.class);
        World destination = mock(World.class);
        PlayerTeleportEvent event = mock(PlayerTeleportEvent.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getWorld()).thenReturn(destination);
        when(destination.getName()).thenReturn("destination");
        when(user.getPlayer()).thenReturn(player);
        when(user.getEntity()).thenReturn(player);
        when(user.getUniqueId()).thenReturn(id);
        when(event.getPlayer()).thenReturn(player);
        when(event.getFrom()).thenReturn(new Location(source, 0, 64, 0));
        when(event.getTo()).thenReturn(new Location(destination, 0, 64, 0));
        AtomicReference<Runnable> delayed = new AtomicReference<>();

        try (MockedStatic<CosmeticUsers> users = mockStatic(CosmeticUsers.class);
             MockedStatic<Settings> settings = mockStatic(Settings.class);
             MockedStatic<HMCCServerUtils> scheduler = mockStatic(HMCCServerUtils.class)) {
            users.when(() -> CosmeticUsers.getUser(id)).thenReturn(user);
            settings.when(Settings::getDisabledWorlds).thenReturn(List.of());
            scheduler.when(() -> HMCCServerUtils.runOnEntityLater(eq(player), any(Runnable.class), eq(4L)))
                    .thenAnswer(call -> {
                        delayed.set(call.getArgument(1));
                        return null;
                    });

            new PlayerGameListener().onPlayerTeleport(event);
            assertNotNull(delayed.get());
            // Only the teleport callback is delivered, as on affected Folia versions.
            delayed.get().run();
            verify(user).respawnBackpack();
            verify(user).updateCosmetic();
        }
    }
}
