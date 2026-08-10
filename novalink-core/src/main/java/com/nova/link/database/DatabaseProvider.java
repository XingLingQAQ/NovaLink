package com.nova.link.database;

import com.nova.link.channel.Channel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface for database operations in the NovaLink system.
 * Provides abstraction for different storage backends (MySQL, Redis, Memory).
 * 
 * Requirements: 22.1, 22.2, 22.3, 22.5
 */
public interface DatabaseProvider {

    /**
     * Initializes the database connection and creates necessary tables/structures.
     *
     * @throws DatabaseException if initialization fails
     */
    void initialize() throws DatabaseException;

    /**
     * Closes the database connection and releases resources.
     */
    void shutdown();

    /**
     * Checks if the database is connected and operational.
     *
     * @return true if the database is available
     */
    boolean isConnected();

    // ==================== Player State Operations ====================

    /**
     * Saves a player's state to the database.
     *
     * @param state the player state to save
     * @throws DatabaseException if the save operation fails
     */
    void savePlayerState(PlayerState state) throws DatabaseException;

    /**
     * Loads a player's state from the database.
     *
     * @param playerId the player UUID
     * @return the player state, or empty if not found
     * @throws DatabaseException if the load operation fails
     */
    Optional<PlayerState> loadPlayerState(UUID playerId) throws DatabaseException;

    /**
     * Deletes a player's state from the database.
     *
     * @param playerId the player UUID
     * @throws DatabaseException if the delete operation fails
     */
    void deletePlayerState(UUID playerId) throws DatabaseException;

    /**
     * Gets all player states (for migration or backup purposes).
     *
     * @return list of all player states
     * @throws DatabaseException if the operation fails
     */
    List<PlayerState> getAllPlayerStates() throws DatabaseException;

    // ==================== Channel Operations ====================

    /**
     * Saves a channel configuration to the database.
     *
     * @param channel the channel to save
     * @throws DatabaseException if the save operation fails
     */
    void saveChannel(Channel channel) throws DatabaseException;

    /**
     * Loads a channel from the database.
     *
     * @param channelId the channel ID
     * @return the channel, or empty if not found
     * @throws DatabaseException if the load operation fails
     */
    Optional<Channel> loadChannel(String channelId) throws DatabaseException;

    /**
     * Deletes a channel from the database.
     *
     * @param channelId the channel ID
     * @throws DatabaseException if the delete operation fails
     */
    void deleteChannel(String channelId) throws DatabaseException;

    /**
     * Gets all channels from the database.
     *
     * @return list of all channels
     * @throws DatabaseException if the operation fails
     */
    List<Channel> getAllChannels() throws DatabaseException;

    // ==================== Mute Operations ====================

    /**
     * Saves a mute record.
     *
     * @param playerId the player UUID
     * @param muteInfo the mute information
     * @throws DatabaseException if the save operation fails
     */
    void saveMute(UUID playerId, MuteInfo muteInfo) throws DatabaseException;

    /**
     * Loads all mutes for a player.
     *
     * @param playerId the player UUID
     * @return list of mute records
     * @throws DatabaseException if the load operation fails
     */
    List<MuteInfo> loadMutes(UUID playerId) throws DatabaseException;

    /**
     * Deletes a mute record.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID (null for global mute)
     * @throws DatabaseException if the delete operation fails
     */
    void deleteMute(UUID playerId, String channelId) throws DatabaseException;

    /**
     * Deletes all expired mutes.
     *
     * @return number of mutes deleted
     * @throws DatabaseException if the operation fails
     */
    int cleanupExpiredMutes() throws DatabaseException;

    // ==================== Ban Operations ====================

    /**
     * Saves a ban record.
     *
     * @param playerId the player UUID
     * @param banInfo the ban information
     * @throws DatabaseException if the save operation fails
     */
    void saveBan(UUID playerId, BanInfo banInfo) throws DatabaseException;

    /**
     * Loads all bans for a player.
     *
     * @param playerId the player UUID
     * @return list of ban records
     * @throws DatabaseException if the load operation fails
     */
    List<BanInfo> loadBans(UUID playerId) throws DatabaseException;

    /**
     * Deletes a ban record.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID (null for global ban)
     * @throws DatabaseException if the delete operation fails
     */
    void deleteBan(UUID playerId, String channelId) throws DatabaseException;

    /**
     * Deletes all expired bans.
     *
     * @return number of bans deleted
     * @throws DatabaseException if the operation fails
     */
    int cleanupExpiredBans() throws DatabaseException;

    // ==================== Notification Operations ====================

    /**
     * Saves a notification record. The provider assigns the id and returns the
     * persisted notification (with the generated id) to the caller via the
     * store layer.
     *
     * @param notification the notification to save
     * @throws DatabaseException if the save operation fails
     */
    void saveNotification(Notification notification) throws DatabaseException;

    /**
     * Loads notifications with pagination.
     *
     * @param offset the offset (0-based)
     * @param limit the maximum number of notifications to return
     * @param unreadOnly when true, only unread notifications are returned
     * @return list of notifications ordered by created_at descending
     * @throws DatabaseException if the load operation fails
     */
    List<Notification> getNotifications(int offset, int limit, boolean unreadOnly) throws DatabaseException;

    /**
     * Marks a notification as read.
     *
     * @param id the notification id
     * @throws DatabaseException if the operation fails
     */
    void markNotificationRead(long id) throws DatabaseException;

    /**
     * Marks all unread notifications as read.
     *
     * @throws DatabaseException if the operation fails
     */
    void markAllNotificationsRead() throws DatabaseException;

    /**
     * Deletes all notifications.
     *
     * @return number of notifications deleted
     * @throws DatabaseException if the operation fails
     */
    int clearNotifications() throws DatabaseException;

    /**
     * Gets the count of unread notifications.
     *
     * @return the unread count
     * @throws DatabaseException if the operation fails
     */
    int getUnreadCount() throws DatabaseException;

    // ==================== Invitation Operations ====================

    /**
     * Saves an invitation code.
     *
     * @param invitation the invitation to save
     * @throws DatabaseException if the save operation fails
     */
    void saveInvitation(Invitation invitation) throws DatabaseException;

    /**
     * Loads an invitation by code.
     *
     * @param code the invitation code
     * @return the invitation, or empty if not found
     * @throws DatabaseException if the load operation fails
     */
    Optional<Invitation> loadInvitation(String code) throws DatabaseException;

    /**
     * Marks an invitation as used.
     *
     * @param code the invitation code
     * @param usedBy the UUID of the player who used it
     * @throws DatabaseException if the operation fails
     */
    void markInvitationUsed(String code, UUID usedBy) throws DatabaseException;

    /**
     * Deletes an invitation.
     *
     * @param code the invitation code
     * @throws DatabaseException if the delete operation fails
     */
    void deleteInvitation(String code) throws DatabaseException;

    /**
     * Deletes all expired invitations.
     *
     * @return number of invitations deleted
     * @throws DatabaseException if the operation fails
     */
    int cleanupExpiredInvitations() throws DatabaseException;

    /**
     * Gets the database provider type name.
     *
     * @return the provider type (e.g., "MySQL", "Redis", "Memory")
     */
    String getProviderType();
}
