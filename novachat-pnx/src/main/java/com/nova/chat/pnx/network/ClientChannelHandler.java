package com.nova.chat.pnx.network;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.pnx.NovaChatPNX;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Netty channel handler for processing packets from NovaLink backend.
 * Uses novachat-common protocol for packet handling.
 * 
 * Requirements: 28.3, 28.5
 */
public class ClientChannelHandler extends SimpleChannelInboundHandler<Packet> {

    private final NetworkClient networkClient;
    private final NovaChatPNX plugin;

    public ClientChannelHandler(NetworkClient networkClient, NovaChatPNX plugin) {
        this.networkClient = networkClient;
        this.plugin = plugin;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        plugin.debug("Channel active, connection established");
        // Handshake is sent by NetworkClient after connection
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
        // Delegate to NetworkClient for registered handlers
        networkClient.handlePacket(packet);
        
        // Handle specific packets that need immediate processing
        if (packet instanceof ChatMessagePacket) {
            handleChatMessage((ChatMessagePacket) packet);
        } else if (packet instanceof TitlePacket) {
            handleTitleMessage((TitlePacket) packet);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        plugin.debug("Channel inactive, connection lost");
        networkClient.onDisconnect();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        plugin.getLogger().warning("Network error: " + cause.getMessage());
        if (plugin.isDebugMode()) {
            cause.printStackTrace();
        }
        ctx.close();
    }

    /**
     * Handle incoming chat message packet.
     */
    private void handleChatMessage(ChatMessagePacket packet) {
        String senderName = packet.getSenderName();
        String channelId = packet.getChannelId();
        String content = packet.getContent();
        
        // Forward to chat interceptor for display
        // Run on main thread for thread safety
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            plugin.getChatInterceptor().displayIncomingMessage(
                senderName, 
                channelId, 
                content,
                packet.getPlaceholders()
            );
        });
    }

    /**
     * Handle incoming title message packet.
     */
    private void handleTitleMessage(TitlePacket packet) {
        String title = packet.getTitle();
        String subtitle = packet.getSubtitle();
        int fadeIn = packet.getFadeIn();
        int stay = packet.getStay();
        int fadeOut = packet.getFadeOut();

        // Send title to all players on main thread
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            // Apply color codes
            String coloredTitle = plugin.getMessageFormatter().colorize(title);
            String coloredSubtitle = plugin.getMessageFormatter().colorize(subtitle);
            
            plugin.getServer().getOnlinePlayers().values().forEach(player -> {
                player.sendTitle(coloredTitle, coloredSubtitle, fadeIn, stay, fadeOut);
            });
        });
    }
}
