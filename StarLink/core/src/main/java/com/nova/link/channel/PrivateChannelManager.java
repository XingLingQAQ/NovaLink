package com.nova.link.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages private channel creation, lifecycle, and access control.
 * Handles unique ID generation (NC-XXXX format), auto-password generation,
 * and enforces client isolation and password verification.
 * 
 * Requirements: 7.1, 7.2, 7.3, 7.4, 7.6
 */
public class PrivateChannelManager {

    private static final Logger logger = LoggerFactory.getLogger(PrivateChannelManager.class);
    
    /** Characters used for generating private channel IDs (hex) */
    private static final String ID_CHARS = "0123456789ABCDEF";
    
    /** Prefix for private channel IDs */
    private static final String PRIVATE_ID_PREFIX = "NC-";
    
    /** Length of the random part of private channel IDs */
    private static final int PRIVATE_ID_LENGTH = 4;
    
    /** Characters used for password generation (excluding ambiguous chars) */
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    
    /** Length of auto-generated passwords */
    private static final int PASSWORD_LENGTH = 6;
    
    /** Maximum attempts to generate a unique ID */
    private static final int MAX_ID_GENERATION_ATTEMPTS = 1000;
    
    /** The channel manager for channel operations */
    private final ChannelManager channelManager;
    
    /** Random generator for ID and password generation */
    private final SecureRandom random;
    
    /** Set of all generated private channel IDs for uniqueness tracking */
    private final Set<String> generatedIds;

    /**
     * Creates a new PrivateChannelManager.
     *
     * @param channelManager the channel manager to use for channel operations
     */
    public PrivateChannelManager(ChannelManager channelManager) {
        this.channelManager = Objects.requireNonNull(channelManager, "ChannelManager cannot be null");
        this.random = new SecureRandom();
        this.generatedIds = ConcurrentHashMap.newKeySet();
    }

    /**
     * Creates a private channel with the given parameters.
     * Generates a unique ID in NC-XXXX format and auto-generates password if not provided.
     *
     * @param displayName the display name for the channel
     * @param clientId the client ID this channel belongs to
     * @param ownerId the UUID of the channel owner
     * @param password the password (null to auto-generate)
     * @return the result containing the created channel and generated password
     * @throws IllegalArgumentException if required parameters are invalid
     * @throws IllegalStateException if unable to generate unique ID
     * 
     * Requirements: 7.1, 7.2, 7.3
     */
    public PrivateChannelCreationResult createPrivateChannel(
            String displayName, 
            String clientId, 
            UUID ownerId, 
            String password) {
        
        // Validate required parameters
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalArgumentException("Client ID is required for private channels");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("Owner ID is required for private channels");
        }
        
        // Generate unique ID (Requirement 7.2)
        String channelId = generateUniqueId();
        
        // Auto-generate password if not provided (Requirement 7.3)
        String finalPassword = password;
        boolean passwordGenerated = false;
        if (finalPassword == null || finalPassword.trim().isEmpty()) {
            finalPassword = generatePassword();
            passwordGenerated = true;
        }
        
        // Build channel configuration
        ChannelConfig config = ChannelConfig.builder()
                .id(channelId)
                .displayName(displayName != null ? displayName : channelId)
                .scope(ChannelScope.PRIVATE)
                .clientId(clientId)
                .password(finalPassword)
                .ownerId(ownerId)
                .maxCapacity(50) // Default capacity for private channels
                .build();
        
        // Create the channel
        Channel channel = channelManager.createChannel(config);
        
        // Add owner as first member
        channel.addMember(ownerId);
        
        // Track the generated ID
        generatedIds.add(channelId);
        
        logger.info("Created private channel: {} (owner={}, client={}, passwordGenerated={})", 
                channelId, ownerId, clientId, passwordGenerated);
        
        return new PrivateChannelCreationResult(channel, finalPassword, passwordGenerated);
    }

    /**
     * Generates a unique private channel ID in NC-XXXX format.
     * Uses hexadecimal characters for the random part.
     *
     * @return a unique channel ID
     * @throws IllegalStateException if unable to generate unique ID after max attempts
     * 
     * Requirement: 7.2
     */
    public String generateUniqueId() {
        int attempts = 0;
        String id;
        
        do {
            id = generateIdCandidate();
            attempts++;
            
            if (attempts > MAX_ID_GENERATION_ATTEMPTS) {
                throw new IllegalStateException(
                        "Unable to generate unique channel ID after " + MAX_ID_GENERATION_ATTEMPTS + " attempts");
            }
        } while (isIdTaken(id));
        
        return id;
    }

    /**
     * Generates a candidate ID without checking uniqueness.
     *
     * @return a candidate ID in NC-XXXX format
     */
    private String generateIdCandidate() {
        StringBuilder sb = new StringBuilder(PRIVATE_ID_PREFIX);
        for (int i = 0; i < PRIVATE_ID_LENGTH; i++) {
            sb.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * Checks if an ID is already taken.
     *
     * @param id the ID to check
     * @return true if the ID is already in use
     */
    private boolean isIdTaken(String id) {
        return generatedIds.contains(id) || channelManager.channelExists(id);
    }

    /**
     * Generates a random 6-character password.
     * Uses alphanumeric characters excluding ambiguous ones (0, O, 1, l, I).
     *
     * @return a 6-character password
     * 
     * Requirement: 7.3
     */
    public String generatePassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * Gets the total number of private channels created.
     *
     * @return count of generated IDs
     */
    public int getGeneratedIdCount() {
        return generatedIds.size();
    }

    /**
     * Checks if an ID was generated by this manager.
     *
     * @param id the ID to check
     * @return true if the ID was generated by this manager
     */
    public boolean wasIdGenerated(String id) {
        return generatedIds.contains(id);
    }

    /**
     * Removes a tracked ID when a channel is deleted.
     *
     * @param id the ID to remove from tracking
     */
    public void removeTrackedId(String id) {
        generatedIds.remove(id);
    }

    /**
     * Clears all tracked IDs. Used for testing.
     */
    public void clear() {
        generatedIds.clear();
    }

    /**
     * Validates access to a private channel.
     * Checks both client membership (player must be from the same client) and password.
     *
     * @param channelId the private channel ID
     * @param playerClientId the client ID the player is connected through
     * @param providedPassword the password provided by the player
     * @return the access result containing success/failure and reason
     * 
     * Requirements: 7.4, 7.6
     */
    public PrivateChannelAccessResult validateAccess(String channelId, String playerClientId, String providedPassword) {
        // Validate parameters
        if (channelId == null || channelId.trim().isEmpty()) {
            return PrivateChannelAccessResult.denied("Channel ID is required", "NC-400");
        }
        if (playerClientId == null || playerClientId.trim().isEmpty()) {
            return PrivateChannelAccessResult.denied("Player client ID is required", "NC-400");
        }
        
        // Get the channel
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return PrivateChannelAccessResult.denied("Channel not found: " + channelId, "NC-404");
        }
        
        // Verify it's a private channel
        if (channel.getScope() != ChannelScope.PRIVATE) {
            return PrivateChannelAccessResult.denied("Channel is not a private channel", "NC-400");
        }
        
        // Verify client membership (Requirement 7.4, 7.6)
        // Player must be connected through the same client as the channel
        String channelClientId = channel.getClientId();
        if (!playerClientId.equals(channelClientId)) {
            logger.debug("Access denied to channel {}: player client {} does not match channel client {}", 
                    channelId, playerClientId, channelClientId);
            return PrivateChannelAccessResult.denied(
                    "Access denied: you must be connected to the same server as the channel", 
                    "NC-403");
        }
        
        // Verify password (Requirement 7.4)
        String channelPassword = channel.getPassword();
        if (channelPassword != null && !channelPassword.isEmpty()) {
            if (providedPassword == null || !channelPassword.equals(providedPassword)) {
                logger.debug("Access denied to channel {}: incorrect password", channelId);
                return PrivateChannelAccessResult.denied("Incorrect password", "NC-434");
            }
        }
        
        logger.debug("Access granted to channel {} for player from client {}", channelId, playerClientId);
        return PrivateChannelAccessResult.granted(channel);
    }

    /**
     * Checks if a player can access a private channel (client membership only, no password check).
     * This is useful for checking if a player is eligible to even attempt joining.
     *
     * @param channelId the private channel ID
     * @param playerClientId the client ID the player is connected through
     * @return true if the player is from the same client as the channel
     * 
     * Requirement: 7.6
     */
    public boolean isClientMember(String channelId, String playerClientId) {
        if (channelId == null || playerClientId == null) {
            return false;
        }
        
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null || channel.getScope() != ChannelScope.PRIVATE) {
            return false;
        }
        
        return playerClientId.equals(channel.getClientId());
    }

    /**
     * Verifies if the provided password matches the channel's password.
     *
     * @param channelId the private channel ID
     * @param providedPassword the password to verify
     * @return true if the password matches or channel has no password
     * 
     * Requirement: 7.4
     */
    public boolean verifyPassword(String channelId, String providedPassword) {
        if (channelId == null) {
            return false;
        }
        
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null || channel.getScope() != ChannelScope.PRIVATE) {
            return false;
        }
        
        String channelPassword = channel.getPassword();
        
        // No password set - allow access
        if (channelPassword == null || channelPassword.isEmpty()) {
            return true;
        }
        
        // Check password match
        return channelPassword.equals(providedPassword);
    }

    /**
     * Attempts to join a player to a private channel after validating access.
     *
     * @param channelId the private channel ID
     * @param playerId the player UUID
     * @param playerClientId the client ID the player is connected through
     * @param providedPassword the password provided by the player
     * @return the join result containing success/failure and reason
     * 
     * Requirements: 7.4, 7.6
     */
    public PrivateChannelAccessResult joinPrivateChannel(
            String channelId, 
            UUID playerId, 
            String playerClientId, 
            String providedPassword) {
        
        if (playerId == null) {
            return PrivateChannelAccessResult.denied("Player ID is required", "NC-400");
        }
        
        // Validate access first
        PrivateChannelAccessResult accessResult = validateAccess(channelId, playerClientId, providedPassword);
        if (!accessResult.isGranted()) {
            return accessResult;
        }
        
        // Add player to channel
        Channel channel = accessResult.getChannel();
        if (channel.isMember(playerId)) {
            return PrivateChannelAccessResult.granted(channel); // Already a member
        }
        
        boolean added = channel.addMember(playerId);
        if (!added) {
            return PrivateChannelAccessResult.denied("Channel is at maximum capacity", "NC-431");
        }
        
        logger.info("Player {} joined private channel {} (client={})", playerId, channelId, playerClientId);
        return PrivateChannelAccessResult.granted(channel);
    }

    /**
     * Result object for private channel creation.
     * Contains the created channel, the password (generated or provided), 
     * and whether the password was auto-generated.
     */
    public static class PrivateChannelCreationResult {
        private final Channel channel;
        private final String password;
        private final boolean passwordGenerated;

        public PrivateChannelCreationResult(Channel channel, String password, boolean passwordGenerated) {
            this.channel = channel;
            this.password = password;
            this.passwordGenerated = passwordGenerated;
        }

        public Channel getChannel() {
            return channel;
        }

        public String getPassword() {
            return password;
        }

        public boolean isPasswordGenerated() {
            return passwordGenerated;
        }

        /**
         * Gets the channel ID.
         *
         * @return the channel ID
         */
        public String getChannelId() {
            return channel.getId();
        }
    }

    /**
     * Result object for private channel access validation.
     * Contains whether access was granted, the channel (if granted),
     * and error information (if denied).
     * 
     * Requirements: 7.4, 7.6
     */
    public static class PrivateChannelAccessResult {
        private final boolean granted;
        private final Channel channel;
        private final String errorMessage;
        private final String errorCode;

        private PrivateChannelAccessResult(boolean granted, Channel channel, String errorMessage, String errorCode) {
            this.granted = granted;
            this.channel = channel;
            this.errorMessage = errorMessage;
            this.errorCode = errorCode;
        }

        /**
         * Creates a granted access result.
         *
         * @param channel the channel access was granted to
         * @return the access result
         */
        public static PrivateChannelAccessResult granted(Channel channel) {
            return new PrivateChannelAccessResult(true, channel, null, null);
        }

        /**
         * Creates a denied access result.
         *
         * @param errorMessage the reason for denial
         * @param errorCode the error code (e.g., NC-403)
         * @return the access result
         */
        public static PrivateChannelAccessResult denied(String errorMessage, String errorCode) {
            return new PrivateChannelAccessResult(false, null, errorMessage, errorCode);
        }

        /**
         * Checks if access was granted.
         *
         * @return true if access was granted
         */
        public boolean isGranted() {
            return granted;
        }

        /**
         * Gets the channel (only available if access was granted).
         *
         * @return the channel, or null if access was denied
         */
        public Channel getChannel() {
            return channel;
        }

        /**
         * Gets the error message (only available if access was denied).
         *
         * @return the error message, or null if access was granted
         */
        public String getErrorMessage() {
            return errorMessage;
        }

        /**
         * Gets the error code (only available if access was denied).
         *
         * @return the error code, or null if access was granted
         */
        public String getErrorCode() {
            return errorCode;
        }

        @Override
        public String toString() {
            if (granted) {
                return "PrivateChannelAccessResult{granted=true, channel=" + channel.getId() + "}";
            } else {
                return "PrivateChannelAccessResult{granted=false, errorCode=" + errorCode + ", errorMessage=" + errorMessage + "}";
            }
        }
    }
}
