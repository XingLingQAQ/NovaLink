package com.nova.link.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the four-level permission hierarchy for NovaChat & NovaLink.
 * 
 * Requirements:
 * - 2.1: Four-level permission hierarchy: SuperAdmin > ClientAdmin > ChannelAdmin > Player
 * - 2.2: Super admin authentication via `/nc auth <password>`
 * - 2.3: novachat.admin permission node identifies ClientAdmin
 * - 2.5: Private channel creator becomes ChannelAdmin
 * - 2.6: Uses player UUID for permission state storage
 * - 2.7: Returns NC-403 for insufficient permissions
 */
public class PermissionManager {

    private static final Logger logger = LoggerFactory.getLogger(PermissionManager.class);

    // Super admin credentials: UUID -> credentials
    private final Map<UUID, SuperAdminCredentials> superAdminCredentials = new ConcurrentHashMap<>();

    // Temporary super admin sessions: UUID -> expiration timestamp
    private final Map<UUID, Long> superAdminSessions = new ConcurrentHashMap<>();

    // Channel admin assignments: channelId -> set of admin UUIDs
    private final Map<String, Set<UUID>> channelAdmins = new ConcurrentHashMap<>();

    // Client admin cache: UUID -> clientId (players with novachat.admin permission)
    private final Map<UUID, String> clientAdmins = new ConcurrentHashMap<>();

    // Default session duration: 1 hour
    private static final long DEFAULT_SESSION_DURATION_MS = 60 * 60 * 1000;

    private long sessionDurationMs = DEFAULT_SESSION_DURATION_MS;

    /**
     * Registers super admin credentials from backend configuration.
     *
     * @param credentials the super admin credentials
     */
    public void registerSuperAdmin(SuperAdminCredentials credentials) {
        if (credentials == null || credentials.getUuid() == null) {
            throw new IllegalArgumentException("Credentials and UUID cannot be null");
        }
        superAdminCredentials.put(credentials.getUuid(), credentials);
        logger.info("Registered super admin: {}", credentials.getUuid());
    }

    /**
     * Unregisters super admin credentials.
     *
     * @param uuid the super admin UUID
     */
    public void unregisterSuperAdmin(UUID uuid) {
        superAdminCredentials.remove(uuid);
        superAdminSessions.remove(uuid);
        logger.info("Unregistered super admin: {}", uuid);
    }

    /**
     * Authenticates a player as super admin using password.
     * Creates a temporary session upon successful authentication.
     * 
     * Requirements:
     * - 2.2: Super admin authentication via password
     *
     * @param playerId     the player UUID
     * @param passwordHash the SHA-256 hash of the password
     * @return the authentication result
     */
    public AuthResult authenticateSuperAdmin(UUID playerId, String passwordHash) {
        if (playerId == null) {
            return AuthResult.unauthorized("Player ID is required");
        }
        if (passwordHash == null || passwordHash.isEmpty()) {
            return AuthResult.unauthorized("Password is required");
        }

        SuperAdminCredentials credentials = superAdminCredentials.get(playerId);
        if (credentials == null) {
            logger.warn("Super admin authentication failed: UUID {} not registered", playerId);
            return AuthResult.unauthorized("Not authorized as super admin");
        }

        if (!credentials.getPasswordHash().equalsIgnoreCase(passwordHash)) {
            logger.warn("Super admin authentication failed: password mismatch for UUID {}", playerId);
            return AuthResult.unauthorized("Invalid password");
        }

        // Create temporary session
        long expirationTime = System.currentTimeMillis() + sessionDurationMs;
        superAdminSessions.put(playerId, expirationTime);
        logger.info("Super admin session created for UUID {}, expires at {}", playerId, expirationTime);

        return AuthResult.success(null);
    }

    /**
     * Revokes a super admin session.
     *
     * @param playerId the player UUID
     */
    public void revokeSuperAdminSession(UUID playerId) {
        superAdminSessions.remove(playerId);
        logger.info("Super admin session revoked for UUID {}", playerId);
    }

    /**
     * Checks if a player has an active super admin session.
     *
     * @param playerId the player UUID
     * @return true if the player has an active super admin session
     */
    public boolean hasSuperAdminSession(UUID playerId) {
        Long expiration = superAdminSessions.get(playerId);
        if (expiration == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiration) {
            // Session expired, remove it
            superAdminSessions.remove(playerId);
            logger.debug("Super admin session expired for UUID {}", playerId);
            return false;
        }
        return true;
    }

    /**
     * Registers a player as client admin (has novachat.admin permission).
     * 
     * Requirements:
     * - 2.3: novachat.admin permission node identifies ClientAdmin
     *
     * @param playerId the player UUID
     * @param clientId the client ID the player belongs to
     */
    public void registerClientAdmin(UUID playerId, String clientId) {
        if (playerId == null || clientId == null) {
            throw new IllegalArgumentException("Player ID and client ID cannot be null");
        }
        clientAdmins.put(playerId, clientId);
        logger.debug("Registered client admin: {} for client {}", playerId, clientId);
    }

    /**
     * Unregisters a player as client admin.
     *
     * @param playerId the player UUID
     */
    public void unregisterClientAdmin(UUID playerId) {
        clientAdmins.remove(playerId);
        logger.debug("Unregistered client admin: {}", playerId);
    }

    /**
     * Checks if a player is a client admin.
     *
     * @param playerId the player UUID
     * @return true if the player is a client admin
     */
    public boolean isClientAdmin(UUID playerId) {
        return clientAdmins.containsKey(playerId);
    }

    /**
     * Gets the client ID for a client admin.
     *
     * @param playerId the player UUID
     * @return the client ID, or null if not a client admin
     */
    public String getClientAdminClientId(UUID playerId) {
        return clientAdmins.get(playerId);
    }

    /**
     * Grants channel admin permission to a player.
     * 
     * Requirements:
     * - 2.5: Private channel creator becomes ChannelAdmin
     *
     * @param channelId the channel ID
     * @param playerId  the player UUID
     */
    public void grantChannelAdmin(String channelId, UUID playerId) {
        if (channelId == null || playerId == null) {
            throw new IllegalArgumentException("Channel ID and player ID cannot be null");
        }
        channelAdmins.computeIfAbsent(channelId, k -> ConcurrentHashMap.newKeySet()).add(playerId);
        logger.debug("Granted channel admin: {} for channel {}", playerId, channelId);
    }

    /**
     * Revokes channel admin permission from a player.
     *
     * @param channelId the channel ID
     * @param playerId  the player UUID
     */
    public void revokeChannelAdmin(String channelId, UUID playerId) {
        Set<UUID> admins = channelAdmins.get(channelId);
        if (admins != null) {
            admins.remove(playerId);
            if (admins.isEmpty()) {
                channelAdmins.remove(channelId);
            }
        }
        logger.debug("Revoked channel admin: {} for channel {}", playerId, channelId);
    }

    /**
     * Checks if a player is a channel admin for a specific channel.
     *
     * @param channelId the channel ID
     * @param playerId  the player UUID
     * @return true if the player is a channel admin for the channel
     */
    public boolean isChannelAdmin(String channelId, UUID playerId) {
        Set<UUID> admins = channelAdmins.get(channelId);
        return admins != null && admins.contains(playerId);
    }

    /**
     * Gets the permission level for a player.
     * 
     * Requirements:
     * - 2.1: Four-level permission hierarchy
     * - 2.6: Uses player UUID for permission state storage
     *
     * @param playerId  the player UUID
     * @param channelId the channel ID (optional, for channel-specific checks)
     * @return the player's permission level
     */
    public PermissionLevel getPermissionLevel(UUID playerId, String channelId) {
        if (playerId == null) {
            return PermissionLevel.PLAYER;
        }

        // Check super admin session first (highest priority)
        if (hasSuperAdminSession(playerId)) {
            return PermissionLevel.SUPER_ADMIN;
        }

        // Check client admin
        if (isClientAdmin(playerId)) {
            return PermissionLevel.CLIENT_ADMIN;
        }

        // Check channel admin (if channel specified)
        if (channelId != null && isChannelAdmin(channelId, playerId)) {
            return PermissionLevel.CHANNEL_ADMIN;
        }

        return PermissionLevel.PLAYER;
    }

    /**
     * Checks if a player has the required permission level.
     * 
     * Requirements:
     * - 2.7: Returns NC-403 for insufficient permissions
     *
     * @param playerId  the player UUID
     * @param channelId the channel ID (optional)
     * @param required  the required permission level
     * @return the permission check result
     */
    public PermissionResult checkPermission(UUID playerId, String channelId, PermissionLevel required) {
        PermissionLevel actual = getPermissionLevel(playerId, channelId);
        
        if (actual.hasAtLeast(required)) {
            return PermissionResult.allowed();
        }

        String message = String.format(
            "Insufficient permissions. Required: %s, Current: %s",
            required.name(), actual.name()
        );
        logger.debug("Permission denied for player {}: {}", playerId, message);
        return PermissionResult.denied(message);
    }

    /**
     * Sets the session duration for super admin sessions.
     *
     * @param durationMs the duration in milliseconds
     */
    public void setSessionDurationMs(long durationMs) {
        this.sessionDurationMs = durationMs;
    }

    /**
     * Gets the session duration for super admin sessions.
     *
     * @return the duration in milliseconds
     */
    public long getSessionDurationMs() {
        return sessionDurationMs;
    }

    /**
     * Clears all permission data (for testing).
     */
    public void clear() {
        superAdminCredentials.clear();
        superAdminSessions.clear();
        channelAdmins.clear();
        clientAdmins.clear();
    }

    /**
     * Removes all channel admins for a channel (when channel is deleted).
     *
     * @param channelId the channel ID
     */
    public void clearChannelAdmins(String channelId) {
        channelAdmins.remove(channelId);
    }
}
