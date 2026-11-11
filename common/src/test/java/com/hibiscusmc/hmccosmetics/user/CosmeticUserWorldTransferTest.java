package com.hibiscusmc.hmccosmetics.user;

import com.hibiscusmc.hmccosmetics.config.Settings;
import me.lojosho.hibiscuscommons.hooks.Hooks;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

class CosmeticUserWorldTransferTest {
    private CosmeticUser user;
    private Entity entity;
    private World source;
    private World destination;

    @BeforeEach
    void setUp() {
        user = spy(new CosmeticUser(UUID.randomUUID()));
        entity = mock(Entity.class);
        source = world("source");
        destination = world("destination");
        doReturn(entity).when(user).getEntity();
        when(entity.getWorld()).thenReturn(source);
        doNothing().when(user).respawnBackpack();
        doNothing().when(user).respawnBalloon();
        doNothing().when(user).hideCosmetics(any());
        doNothing().when(user).showCosmetics(any());
    }

    @Test
    void rebuildsModelsWithoutAWorldChangeEventAndOnlyOncePerTransfer() {
        try (MockedStatic<Settings> settings = settings()) {
            user.refreshWorldState();
            verify(user, never()).respawnBackpack();

            when(entity.getWorld()).thenReturn(destination);
            user.refreshWorldState();
            user.refreshWorldState();

            verify(user).respawnBackpack();
            verify(user).respawnBalloon();
            verify(user).showCosmetics(CosmeticUser.HiddenReason.WORLD);
        }
    }

    @Test
    void cosmeticTickRecoversWorldTransferWithoutAnyTeleportEvents() {
        try (MockedStatic<Settings> settings = settings();
             MockedStatic<Hooks> hooks = mockStatic(Hooks.class)) {
            doNothing().when(user).updateCosmetic();
            user.refreshWorldState();
            when(entity.getWorld()).thenReturn(destination);
            user.tick();
            verify(user).respawnBackpack();
        }
    }

    @Test
    void tracksInitialWorldEvenWhenCosmeticTickingIsDisabled() {
        try (MockedStatic<Settings> settings = settings()) {
            settings.when(Settings::getTickPeriod).thenReturn(0);
            user.startTicking();
            when(entity.getWorld()).thenReturn(destination);
            user.refreshWorldState();
            verify(user).respawnBackpack();
        }
    }

    @Test
    void retriesDetectionAfterEntityIsTemporarilyUnavailable() {
        try (MockedStatic<Settings> settings = settings()) {
            user.refreshWorldState();
            doReturn(null).when(user).getEntity();
            user.refreshWorldState();
            verify(user, never()).respawnBackpack();

            doReturn(entity).when(user).getEntity();
            when(entity.getWorld()).thenReturn(destination);
            user.refreshWorldState();
            verify(user).respawnBackpack();
        }
    }

    @Test
    void updatesDisabledWorldStateBeforeRebuildingModels() {
        try (MockedStatic<Settings> settings = settings()) {
            settings.when(Settings::getDisabledWorlds).thenReturn(List.of("destination"));
            user.refreshWorldState();
            when(entity.getWorld()).thenReturn(destination);
            user.refreshWorldState();

            var order = inOrder(user);
            order.verify(user).hideCosmetics(CosmeticUser.HiddenReason.WORLD);
            order.verify(user).respawnBackpack();
        }
    }

    private MockedStatic<Settings> settings() {
        MockedStatic<Settings> settings = mockStatic(Settings.class);
        settings.when(Settings::getDisabledWorlds).thenReturn(List.of());
        return settings;
    }

    private World world(String name) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn(name);
        return world;
    }
}
