package com.nova.link.network;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PlatformType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a client connection to the NovaLink server.
 * Wraps a Netty channel and provides methods for sending packets.
 */
public class ClientConnection {

    private final Channel channel;
    private final String connectionId;
    private final long connectedAt;

    // Authentication state
    private volatile boolean authenticated = false;
    private volatile String clientId;
    private volatile UUID superAdminUuid;

    // Runtime metadata reported by the client (for panel display)
    private volatile PlatformType platform;
    private volatile String serverVersion;
    private volatile long ping;
    private volatile long lastPingAt;

    public ClientConnection(Channel channel) {
        this.channel = channel;
        this.connectionId = UUID.randomUUID().toString().substring(0, 8);
        this.connectedAt = System.currentTimeMillis();
    }

    /**
     * Sends a packet to this client.
     *
     * @param packet the packet to send
     * @return a CompletableFuture that completes when the packet is written
     */
    public CompletableFuture<Void> sendPacket(Packet packet) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        if (!channel.isActive()) {
            future.completeExceptionally(new IllegalStateException("Channel is not active"));
            return future;
        }

        ChannelFuture writeFuture = channel.writeAndFlush(packet);
        writeFuture.addListener(f -> {
            if (f.isSuccess()) {
                future.complete(null);
            } else {
                future.completeExceptionally(f.cause());
            }
        });

        return future;
    }

    /**
     * Closes this connection.
     *
     * @return a CompletableFuture that completes when the connection is closed
     */
    public CompletableFuture<Void> close() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        channel.close().addListener(f -> {
            if (f.isSuccess()) {
                future.complete(null);
            } else {
                future.completeExceptionally(f.cause());
            }
        });
        return future;
    }

    /**
     * Gets the remote address of this connection.
     *
     * @return the remote address as a string
     */
    public String getRemoteAddress() {
        InetSocketAddress address = (InetSocketAddress) channel.remoteAddress();
        if (address != null) {
            return address.getAddress().getHostAddress();
        }
        return "unknown";
    }

    /**
     * Gets the remote port of this connection.
     *
     * @return the remote port
     */
    public int getRemotePort() {
        InetSocketAddress address = (InetSocketAddress) channel.remoteAddress();
        if (address != null) {
            return address.getPort();
        }
        return -1;
    }

    /**
     * Checks if this connection is active.
     *
     * @return true if the connection is active
     */
    public boolean isActive() {
        return channel.isActive();
    }

    /**
     * Gets the unique connection ID.
     *
     * @return the connection ID
     */
    public String getConnectionId() {
        return connectionId;
    }

    /**
     * Gets the timestamp when this connection was established.
     *
     * @return the connection timestamp in milliseconds
     */
    public long getConnectedAt() {
        return connectedAt;
    }

    /**
     * Checks if this connection is authenticated.
     *
     * @return true if authenticated
     */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * Sets the authentication state.
     *
     * @param authenticated the authentication state
     */
    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    /**
     * Gets the client ID (set after authentication).
     *
     * @return the client ID, or null if not authenticated
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * Sets the client ID.
     *
     * @param clientId the client ID
     */
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /**
     * Gets the super admin UUID if this connection has super admin privileges.
     *
     * @return the super admin UUID, or null if not a super admin
     */
    public UUID getSuperAdminUuid() {
        return superAdminUuid;
    }

    /**
     * Sets the super admin UUID.
     *
     * @param superAdminUuid the super admin UUID
     */
    public void setSuperAdminUuid(UUID superAdminUuid) {
        this.superAdminUuid = superAdminUuid;
    }

    /**
     * Gets the underlying Netty channel.
     *
     * @return the channel
     */
    public Channel getChannel() {
        return channel;
    }

    /**
     * Gets the platform reported by this client during handshake.
     *
     * @return the platform, or null if not reported
     */
    public PlatformType getPlatform() {
        return platform;
    }

    /**
     * Sets the platform reported by this client.
     *
     * @param platform the platform
     */
    public void setPlatform(PlatformType platform) {
        this.platform = platform;
    }

    /**
     * Gets the Minecraft server version reported by this client during handshake.
     *
     * @return the server version string (e.g. "1.20.4"), or empty/null if not reported
     */
    public String getServerVersion() {
        return serverVersion;
    }

    /**
     * Sets the Minecraft server version reported by this client.
     *
     * @param serverVersion the server version string
     */
    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    /**
     * Gets the last computed round-trip latency (ping) in milliseconds.
     *
     * @return the ping in ms, or 0 if no keepalive has been received yet
     */
    public long getPing() {
        return ping;
    }

    /**
     * Sets the last computed round-trip latency (ping) in milliseconds.
     *
     * @param ping the ping in ms
     */
    public void setPing(long ping) {
        this.ping = ping;
    }

    /**
     * Gets the timestamp of the last keepalive received from this client.
     *
     * @return the timestamp in ms, or 0 if none received
     */
    public long getLastPingAt() {
        return lastPingAt;
    }

    /**
     * Sets the timestamp of the last keepalive received from this client.
     *
     * @param lastPingAt the timestamp in ms
     */
    public void setLastPingAt(long lastPingAt) {
        this.lastPingAt = lastPingAt;
    }

    @Override
    public String toString() {
        return "ClientConnection{" +
                "connectionId='" + connectionId + '\'' +
                ", remoteAddress='" + getRemoteAddress() + '\'' +
                ", authenticated=" + authenticated +
                ", clientId='" + clientId + '\'' +
                ", platform=" + platform +
                ", serverVersion='" + serverVersion + '\'' +
                ", ping=" + ping +
                '}';
    }
}
