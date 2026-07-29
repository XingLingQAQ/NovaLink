package com.nova.link.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which permission nodes a connected game client (server node) is
 * allowed to receive on GLOBAL channels.
 * <p>
 * NovaLink authenticates <em>clients</em> (server processes), not individual
 * Minecraft players, on the TCP wire. GLOBAL channel permission nodes from
 * config (e.g. {@code novachat.channel.staff}) are therefore enforced at the
 * client-node level: only clients that have been granted the node receive
 * fan-out for that channel.
 * <p>
 * Player-level LuckPerms checks remain on the edge plugins; this registry is
 * the backend's defense-in-depth for staff/admin GLOBAL channels.
 */
public class ClientPermissionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ClientPermissionRegistry.class);

    /** Wildcard grant that satisfies any required permission. */
    public static final String WILDCARD = "*";

    private final ConcurrentHashMap<String, Set<String>> grants = new ConcurrentHashMap<>();

    /**
     * When true, a client with no registered grants is treated as permitted
     * for any required node (legacy / migration mode).
     * When false (default), required permissions deny clients with no grants.
     */
    private volatile boolean allowWhenUnregistered = false;

    public void setAllowWhenUnregistered(boolean allowWhenUnregistered) {
        this.allowWhenUnregistered = allowWhenUnregistered;
    }

    public boolean isAllowWhenUnregistered() {
        return allowWhenUnregistered;
    }

    /**
     * Grants a permission node to a client.
     *
     * @param clientId   authenticated client id
     * @param permission permission node (case-sensitive, trimmed)
     */
    public void grant(String clientId, String permission) {
        if (clientId == null || clientId.isBlank() || permission == null || permission.isBlank()) {
            return;
        }
        String node = permission.trim();
        grants.computeIfAbsent(clientId, k -> ConcurrentHashMap.newKeySet()).add(node);
        logger.debug("Granted permission '{}' to client '{}'", node, clientId);
    }

    /**
     * Grants multiple permission nodes to a client.
     */
    public void grantAll(String clientId, Iterable<String> permissions) {
        if (clientId == null || clientId.isBlank() || permissions == null) {
            return;
        }
        for (String permission : permissions) {
            grant(clientId, permission);
        }
    }

    /**
     * Revokes a single permission node from a client.
     *
     * @return true if the node was present
     */
    public boolean revoke(String clientId, String permission) {
        if (clientId == null || permission == null) {
            return false;
        }
        Set<String> nodes = grants.get(clientId);
        if (nodes == null) {
            return false;
        }
        boolean removed = nodes.remove(permission.trim());
        if (nodes.isEmpty()) {
            grants.remove(clientId, nodes);
        }
        return removed;
    }

    /**
     * Clears all grants for a client (e.g. on disconnect).
     */
    public void clearClient(String clientId) {
        if (clientId != null) {
            grants.remove(clientId);
        }
    }

    /**
     * Clears every grant (tests / full reload).
     */
    public void clearAll() {
        grants.clear();
    }

    /**
     * Checks whether {@code clientId} may receive traffic for a channel that
     * requires {@code permission}.
     * <p>
     * Rules:
     * <ul>
     *   <li>blank/null permission → always allowed</li>
     *   <li>client has {@code *} → allowed</li>
     *   <li>client has exact node → allowed</li>
     *   <li>client has no grants → {@link #allowWhenUnregistered}</li>
     *   <li>otherwise → denied</li>
     * </ul>
     */
    public boolean hasPermission(String clientId, String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        if (clientId == null || clientId.isBlank()) {
            return false;
        }

        Set<String> nodes = grants.get(clientId);
        if (nodes == null || nodes.isEmpty()) {
            return allowWhenUnregistered;
        }
        if (nodes.contains(WILDCARD)) {
            return true;
        }
        return nodes.contains(permission.trim());
    }

    /**
     * BiPredicate adapter for {@link com.nova.link.channel.MessageRouter#setPermissionChecker}.
     */
    public java.util.function.BiPredicate<String, String> asChecker() {
        return this::hasPermission;
    }

    /**
     * Snapshot of grants for a client (unmodifiable, empty if none).
     */
    public Set<String> getGrants(String clientId) {
        Set<String> nodes = grants.get(clientId);
        if (nodes == null || nodes.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(nodes);
    }

    public int getTrackedClientCount() {
        return grants.size();
    }

    @Override
    public String toString() {
        return "ClientPermissionRegistry{clients=" + grants.size()
                + ", allowWhenUnregistered=" + allowWhenUnregistered + '}';
    }
}
