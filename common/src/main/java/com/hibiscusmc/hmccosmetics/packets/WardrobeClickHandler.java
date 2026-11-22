package com.hibiscusmc.hmccosmetics.packets;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.NoSuchElementException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

public final class WardrobeClickHandler extends ChannelInboundHandlerAdapter {
    private static final String SPECTATOR_ACTION = "net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket";
    private final Class<?> spectatorActionType;
    private final BooleanSupplier openMenu;

    WardrobeClickHandler(Class<?> spectatorActionType, BooleanSupplier openMenu) {
        this.spectatorActionType = spectatorActionType;
        this.openMenu = openMenu;
    }

    public static @Nullable Runnable register(Player player, BooleanSupplier openMenu) {
        // Modern spectator clients send this packet for both a hit and a click in empty space.
        Class<?> packetType;
        try {
            packetType = Class.forName(SPECTATOR_ACTION, false, player.getClass().getClassLoader());
        } catch (ClassNotFoundException ignored) {
            return null;
        }

        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object gameListener = handle.getClass().getField("connection").get(handle);
            Object connection = gameListener.getClass().getField("connection").get(gameListener);
            Channel channel = (Channel) connection.getClass().getField("channel").get(connection);
            if (channel == null || !channel.isOpen()) return null;
            ChannelPipeline pipeline = channel.pipeline();
            WardrobeClickHandler handler = new WardrobeClickHandler(packetType, openMenu);
            pipeline.addBefore("packet_handler", null, handler);
            return () -> channel.eventLoop().execute(() -> {
                if (pipeline.context(handler) != null) pipeline.remove(handler);
            });
        } catch (ReflectiveOperationException | IllegalStateException | NoSuchElementException exception) {
            HMCCosmeticsPlugin.getInstance().getLogger().log(Level.WARNING, "Could not enable spectator clicks for the wardrobe", exception);
            return null;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {
        if (spectatorActionType.isInstance(packet) && openMenu.getAsBoolean()) return;
        super.channelRead(context, packet);
    }
}
