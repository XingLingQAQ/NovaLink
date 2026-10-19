package com.nova.chat.bukkit.channel;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.channel.ProxyChannelException;
import com.nova.chat.channel.ProxyChannelProtocol;
import com.nova.chat.common.protocol.Packet;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Sub-server half of the {@code novachat:main} plugin-messaging channel.
 *
 * <p>Registers the channel with Bukkit, sends upstream packets (chat, commands
 * and player state in later phases) to the proxy, and decodes downstream
 * fan-out for local rendering.
 *
 * <p><b>No players, no delivery.</b> Plugin messaging writes through a player
 * connection, so a server with nobody online cannot reach the proxy at all.
 * {@link #send} returns {@code false} in that case and logs at debug level; the
 * caller falls back to its other transport (the backend link) instead of
 * assuming success. This is an inherent limit of plugin messaging, not a
 * transient error.
 *
 * <p><b>Identity.</b> Upstream envelopes carry {@code plugin-messaging.server-id}
 * (or {@code backend.username} when unset). The proxy rejects any envelope whose
 * declared id does not match the server the payload actually came from.
 *
 * <p><b>Threading.</b> {@link #send} is safe from the async chat thread: the
 * only shared state is the volatile config snapshot read through
 * {@link NovaChatBukkit#getNovaChatConfig()}, and Bukkit's plugin-message write
 * is a Netty write. {@link #onPluginMessageReceived} is invoked on the server
 * main thread; the dispatch target performs its own main-thread hops.
 */
public class ProxyChannelClient implements PluginMessageListener {

    /** Receives decoded downstream packets. */
    public interface PacketSink {

        /**
         * @param senderId the proxy's identity from the envelope (never null)
         * @param packet the decoded packet
         * @param excludedRecipients players the proxy already rendered it to
         */
        void accept(String senderId, Packet packet, Set<UUID> excludedRecipients);
    }

    private final NovaChatBukkit plugin;
    private final PacketSink sink;

    /** Set once registration succeeded; guards double registration on reload. */
    private volatile boolean registered;

    /**
     * @param plugin the plugin instance
     * @param sink receives decoded downstream packets (may be null: packets are
     *             logged and dropped)
     */
    public ProxyChannelClient(NovaChatBukkit plugin, PacketSink sink) {
        this.plugin = plugin;
        this.sink = sink;
    }

    // ------------------------------------------------------------ registration

    /**
     * Registers the channel with Bukkit's messenger.
     *
     * @return {@code true} when this call performed the registration
     */
    public boolean register() {
        if (registered) {
            return false;
        }
        try {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(
                    plugin, ProxyChannelProtocol.CHANNEL_ID);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(
                    plugin, ProxyChannelProtocol.CHANNEL_ID, this);
            registered = true;
            plugin.debug("Registered plugin-messaging channel "
                    + ProxyChannelProtocol.CHANNEL_ID);
            return true;
        } catch (RuntimeException e) {
            registered = false;
            plugin.getLogger().warning("Failed to register the plugin-messaging channel "
                    + ProxyChannelProtocol.CHANNEL_ID + ": " + e.getMessage());
            return false;
        }
    }

    /** Unregisters both directions; safe when never registered. */
    public void unregister() {
        if (!registered) {
            return;
        }
        try {
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(
                    plugin, ProxyChannelProtocol.CHANNEL_ID, this);
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(
                    plugin, ProxyChannelProtocol.CHANNEL_ID);
        } catch (RuntimeException e) {
            plugin.debug("Failed to unregister the plugin-messaging channel: "
                    + e.getMessage());
        } finally {
            registered = false;
        }
    }

    // ----------------------------------------------------------------- sending

    /**
     * @return whether the bridge is enabled in the live config snapshot
     */
    public boolean isEnabled() {
        NovaChatConfig config = plugin.getNovaChatConfig();
        return config != null && config.isPluginMessagingEnabled();
    }

    /**
     * @return the identity declared in upstream envelopes (never blank)
     */
    public String serverId() {
        NovaChatConfig config = plugin.getNovaChatConfig();
        String id = config != null ? config.getPluginMessagingServerId() : null;
        return id != null && !id.isBlank() ? id : "unknown-server";
    }

    /**
     * Sends one packet to the proxy through any online player's connection.
     *
     * @param packet the packet to encode and send
     * @return {@code false} when the bridge is disabled, nobody is online (the
     *         message cannot be delivered at all), or the payload cannot be
     *         encoded — never a silent success
     */
    public boolean send(Packet packet) {
        if (packet == null) {
            return false;
        }
        if (!isEnabled()) {
            plugin.debug("Plugin-messaging bridge is disabled; not sending "
                    + packet.getClass().getSimpleName());
            return false;
        }
        if (!registered) {
            // Bukkit rejects writes on an unregistered outgoing channel with an
            // unchecked exception; report the drop instead of throwing from the
            // async chat event.
            plugin.debug("Plugin-messaging channel is not registered; dropping "
                    + packet.getClass().getSimpleName());
            return false;
        }

        // Plugin messaging has no server-addressed write: the payload must ride
        // a player connection. With nobody online there is no proxy link on
        // this server at all, so the message is genuinely undeliverable.
        Collection<? extends Player> online = plugin.getServer().getOnlinePlayers();
        if (online.isEmpty()) {
            plugin.debug("No players online: dropping " + packet.getClass().getSimpleName()
                    + " instead of pretending it reached the proxy");
            return false;
        }
        Player carrier = online.iterator().next();

        byte[] payload;
        try {
            payload = ProxyChannelProtocol.encode(serverId(), packet);
        } catch (ProxyChannelException e) {
            plugin.getLogger().warning("Dropped " + packet.getClass().getSimpleName()
                    + " for the proxy: " + e.getMessage());
            return false;
        }

        try {
            carrier.sendPluginMessage(plugin, ProxyChannelProtocol.CHANNEL_ID, payload);
        } catch (RuntimeException e) {
            // The carrier may have disconnected between the roster snapshot and
            // the write; that is a drop, not a reason to fail the chat event.
            plugin.debug("Plugin-message write failed through " + carrier.getName()
                    + ": " + e.getMessage());
            return false;
        }
        plugin.debug("Sent " + packet.getClass().getSimpleName()
                + " to the proxy through " + carrier.getName());
        return true;
    }

    // --------------------------------------------------------------- receiving

    /**
     * Decodes a downstream payload and hands it to the sink.
     *
     * <p>Malformed and oversized payloads are dropped with a debug line: the
     * channel is client-reachable, so nothing about the bytes is trusted.
     *
     * @param channel the channel the payload arrived on
     * @param player the player connection that carried it
     * @param message the raw payload
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!ProxyChannelProtocol.CHANNEL_ID.equals(channel)) {
            return;
        }
        ProxyChannelProtocol.Decoded decoded;
        try {
            decoded = ProxyChannelProtocol.decode(message);
        } catch (ProxyChannelException e) {
            plugin.debug("Dropped malformed novachat:main payload: " + e.getMessage());
            return;
        }

        PacketSink target = sink;
        if (target == null) {
            plugin.debug("No sink for downstream " + decoded.getPacket().getClass().getSimpleName());
            return;
        }
        target.accept(decoded.getSenderId(), decoded.getPacket(),
                decoded.getExcludedRecipients());
    }
}
