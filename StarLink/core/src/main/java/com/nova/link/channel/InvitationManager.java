package com.nova.link.channel;

import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.Invitation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Manages channel invitations including code generation, validation, and acceptance.
 * 
 * Requirements: 8.1, 8.2, 8.3, 8.4, 8.5
 */
public class InvitationManager {

    private static final Logger logger = LoggerFactory.getLogger(InvitationManager.class);

    /** Characters used for generating invitation codes (excluding ambiguous chars) */
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    
    /** Length of invitation codes */
    private static final int CODE_LENGTH = 6;
    
    /** Default TTL for invitations: 24 hours */
    public static final long DEFAULT_TTL_MILLIS = TimeUnit.HOURS.toMillis(24);

    private final DatabaseProvider databaseProvider;
    private final ChannelManager channelManager;
    private final SecureRandom random;

    public InvitationManager(DatabaseProvider databaseProvider, ChannelManager channelManager) {
        this.databaseProvider = Objects.requireNonNull(databaseProvider, "Database provider cannot be null");
        this.channelManager = Objects.requireNonNull(channelManager, "Channel manager cannot be null");
        this.random = new SecureRandom();
    }

    /**
     * Generates a unique 6-character alphanumeric invitation code.
     * Uses SecureRandom for cryptographic randomness.
     *
     * @return a unique invitation code
     */
    public String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }


    /**
     * Creates a new invitation for a channel.
     *
     * @param channelId the channel ID to invite to
     * @param inviterId the UUID of the player creating the invitation
     * @return the created invitation
     * @throws IllegalArgumentException if the channel doesn't exist
     * @throws DatabaseException if saving fails
     */
    public Invitation createInvitation(String channelId, UUID inviterId) throws DatabaseException {
        return createInvitation(channelId, inviterId, DEFAULT_TTL_MILLIS);
    }

    /**
     * Creates a new invitation for a channel with custom TTL.
     *
     * @param channelId the channel ID to invite to
     * @param inviterId the UUID of the player creating the invitation
     * @param ttlMillis the time-to-live in milliseconds
     * @return the created invitation
     * @throws IllegalArgumentException if the channel doesn't exist
     * @throws DatabaseException if saving fails
     */
    public Invitation createInvitation(String channelId, UUID inviterId, long ttlMillis) throws DatabaseException {
        Objects.requireNonNull(channelId, "Channel ID cannot be null");
        Objects.requireNonNull(inviterId, "Inviter ID cannot be null");
        
        // Verify channel exists
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found: " + channelId);
        }
        
        // Generate unique code
        String code = generateUniqueCode();
        
        // Calculate expiration time
        long expireTime = System.currentTimeMillis() + ttlMillis;
        
        // Create and save invitation
        Invitation invitation = new Invitation(code, channelId, inviterId, expireTime);
        databaseProvider.saveInvitation(invitation);
        
        logger.info("Created invitation {} for channel {} by {}", code, channelId, inviterId);
        return invitation;
    }

    /**
     * Generates a unique invitation code that doesn't already exist.
     *
     * @return a unique code
     * @throws DatabaseException if checking existing codes fails
     */
    private String generateUniqueCode() throws DatabaseException {
        int attempts = 0;
        String code;
        do {
            code = generateCode();
            attempts++;
            if (attempts > 100) {
                throw new IllegalStateException("Unable to generate unique invitation code after 100 attempts");
            }
        } while (databaseProvider.loadInvitation(code).isPresent());
        return code;
    }


    /**
     * Validates an invitation code.
     *
     * @param code the invitation code
     * @return the validation result
     * @throws DatabaseException if loading fails
     */
    public InvitationResult validateInvitation(String code) throws DatabaseException {
        if (code == null || code.isEmpty()) {
            return InvitationResult.invalid("NC-400", "Invitation code cannot be empty");
        }
        
        Optional<Invitation> optInvitation = databaseProvider.loadInvitation(code);
        if (optInvitation.isEmpty()) {
            return InvitationResult.invalid("NC-404", "Invitation not found");
        }
        
        Invitation invitation = optInvitation.get();
        
        if (invitation.isUsed()) {
            return InvitationResult.invalid("NC-411", "Invitation has already been used");
        }
        
        if (invitation.isExpired()) {
            return InvitationResult.invalid("NC-410", "Invitation has expired");
        }
        
        return InvitationResult.valid(invitation);
    }

    /**
     * Accepts an invitation and adds the player to the channel.
     *
     * @param code the invitation code
     * @param playerId the UUID of the player accepting
     * @param playerClientId the client ID the player is connected through
     * @return the acceptance result
     * @throws DatabaseException if database operations fail
     */
    public InvitationResult acceptInvitation(String code, UUID playerId, String playerClientId) throws DatabaseException {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        
        // First validate the invitation
        InvitationResult validation = validateInvitation(code);
        if (!validation.isSuccess()) {
            return validation;
        }
        
        Invitation invitation = validation.getInvitation();
        String channelId = invitation.getChannelId();
        
        // Get the channel
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return InvitationResult.invalid("NC-404", "Channel no longer exists");
        }
        
        // For private channels, verify client isolation
        if (channel.getScope() == ChannelScope.PRIVATE) {
            if (playerClientId == null || !playerClientId.equals(channel.getClientId())) {
                return InvitationResult.invalid("NC-403", "Cannot join private channel from different client");
            }
        }
        
        // Check channel capacity
        if (channel.getMaxCapacity() > 0 && channel.getMemberCount() >= channel.getMaxCapacity()) {
            return InvitationResult.invalid("NC-409", "Channel is full");
        }
        
        // Mark invitation as used. The provider makes the used=false → used=true
        // flip atomic; a false return means another caller already consumed the
        // invitation, so we must NOT add this player to the channel.
        boolean marked = databaseProvider.markInvitationUsed(code, playerId);
        if (!marked) {
            return InvitationResult.invalid("NC-411", "Invitation has already been used");
        }

        // Add player to channel
        boolean added = channelManager.addMember(channelId, playerId);
        if (!added) {
            return InvitationResult.invalid("NC-500", "Failed to add member to channel");
        }
        
        logger.info("Player {} accepted invitation {} and joined channel {}", playerId, code, channelId);
        return InvitationResult.accepted(invitation, channelId);
    }


    /**
     * Revokes an invitation.
     *
     * @param code the invitation code to revoke
     * @param revokerId the UUID of the player revoking (must be inviter or admin)
     * @return true if revoked successfully
     * @throws DatabaseException if database operations fail
     */
    public boolean revokeInvitation(String code, UUID revokerId) throws DatabaseException {
        if (code == null || code.isEmpty()) {
            return false;
        }
        
        Optional<Invitation> optInvitation = databaseProvider.loadInvitation(code);
        if (optInvitation.isEmpty()) {
            return false;
        }
        
        Invitation invitation = optInvitation.get();
        
        // Only the inviter can revoke (admin check should be done at higher level)
        if (!invitation.getInviterId().equals(revokerId)) {
            logger.warn("Player {} attempted to revoke invitation {} created by {}", 
                    revokerId, code, invitation.getInviterId());
            return false;
        }
        
        databaseProvider.deleteInvitation(code);
        logger.info("Invitation {} revoked by {}", code, revokerId);
        return true;
    }

    /**
     * Force revokes an invitation (for admins).
     *
     * @param code the invitation code to revoke
     * @return true if revoked successfully
     * @throws DatabaseException if database operations fail
     */
    public boolean forceRevokeInvitation(String code) throws DatabaseException {
        if (code == null || code.isEmpty()) {
            return false;
        }
        
        Optional<Invitation> optInvitation = databaseProvider.loadInvitation(code);
        if (optInvitation.isEmpty()) {
            return false;
        }
        
        databaseProvider.deleteInvitation(code);
        logger.info("Invitation {} force revoked", code);
        return true;
    }

    /**
     * Gets an invitation by code.
     *
     * @param code the invitation code
     * @return the invitation, or empty if not found
     * @throws DatabaseException if loading fails
     */
    public Optional<Invitation> getInvitation(String code) throws DatabaseException {
        return databaseProvider.loadInvitation(code);
    }

    /**
     * Cleans up expired invitations.
     *
     * @return the number of invitations cleaned up
     * @throws DatabaseException if cleanup fails
     */
    public int cleanupExpired() throws DatabaseException {
        int count = databaseProvider.cleanupExpiredInvitations();
        if (count > 0) {
            logger.info("Cleaned up {} expired invitations", count);
        }
        return count;
    }

    /**
     * Checks if an invitation code is valid (exists, not used, not expired).
     *
     * @param code the invitation code
     * @return true if valid
     * @throws DatabaseException if loading fails
     */
    public boolean isValidCode(String code) throws DatabaseException {
        Optional<Invitation> optInvitation = databaseProvider.loadInvitation(code);
        return optInvitation.map(Invitation::isValid).orElse(false);
    }
}
