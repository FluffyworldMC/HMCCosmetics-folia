package com.hibiscusmc.hmccosmetics.packets;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WardrobeClickHandlerTest {
    @Test
    void handlesSpectatorClicksWithOrWithoutATarget() {
        BooleanSupplier openMenu = mock(BooleanSupplier.class);
        when(openMenu.getAsBoolean()).thenReturn(true, true, false);
        EmbeddedChannel channel = new EmbeddedChannel(new WardrobeClickHandler(SpectatorClick.class, openMenu));
        try {
            Object unrelated = new Object();
            assertTrue(channel.writeInbound(unrelated));
            assertSame(unrelated, channel.readInbound());
            verifyNoInteractions(openMenu);

            assertFalse(channel.writeInbound(new SpectatorClick(OptionalInt.empty())));
            assertFalse(channel.writeInbound(new SpectatorClick(OptionalInt.of(42))));
            verify(openMenu, times(2)).getAsBoolean();

            SpectatorClick afterExit = new SpectatorClick(OptionalInt.empty());
            assertTrue(channel.writeInbound(afterExit));
            assertSame(afterExit, channel.readInbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private record SpectatorClick(OptionalInt target) {}
}
