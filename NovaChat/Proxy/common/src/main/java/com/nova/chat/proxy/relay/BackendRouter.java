package com.nova.chat.proxy.relay;

import com.nova.chat.client.network.AbstractPlatformNetworkClient;

/**
 * Resolves which backend link carries a downstream server's traffic.
 *
 * <p>Relay used to open exactly one backend connection for the whole proxy, so
 * every sub-server's players were attributed to that one client id on the
 * backend. The backend keys player delivery on
 * {@code PlayerState#clientId} (mention, title, private message) and enforces
 * SERVER/PRIVATE channel ownership against the sending connection, so the
 * single link made all sub-servers look like one client.
 *
 * <p>This is the seam the shared proxy cores use instead of talking to a single
 * network client: chat, commands and the server-switch path ask for the link of
 * the player's current server, and the packet's {@code clientId} is stamped
 * with {@link #identityFor(String)} so it matches the connection the backend
 * sees.
 *
 * <p>Implementations are expected to be null-tolerant: an unknown server name
 * resolves to the fallback link (the legacy {@code backend.username}
 * connection), and {@code null} is returned only when the deployment has no
 * link at all for that traffic.
 */
public interface BackendRouter {

    /**
     * @param serverName the downstream server, or {@code null} when unknown
     * @return the link that must carry that server's traffic, or {@code null}
     *         when no link exists (packets are then dropped with a log line)
     */
    AbstractPlatformNetworkClient clientFor(String serverName);

    /**
     * @param serverName the downstream server, or {@code null} when unknown
     * @return the backend client identity stamped on packets for that server;
     *         never null for a configured deployment
     */
    String identityFor(String serverName);
}
