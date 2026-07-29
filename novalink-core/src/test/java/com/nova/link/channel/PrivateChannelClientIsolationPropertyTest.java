package com.nova.link.channel;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for Private Channel Client Isolation.
 * 
 * **Feature: starchat-starlink, Property 10: Private Channel Client Isolation**
 * 
 * For any private channel, only players connected through the same client 
 * as the channel owner should be able to join.
 * 
 * **Validates: Requirements 7.4, 7.6**
 */
public class PrivateChannelClientIsolationPropertyTest {

    /**
     * **Feature: starchat-starlink, Property 10: Private Channel Client Isolation**
     * 
     * For any private channel and any player from a DIFFERENT client,
     * access should be denied regardless of password correctness.
     * 
     * **Validates: Requirements 7.4, 7.6**
     */
    @Property(tries = 100)
    void playersFromDifferentClientsShouldBeDeniedAccess(
            @ForAll @StringLength(min = 1, max = 20) String ownerClientId,
            @ForAll @StringLength(min = 1, max = 20) String playerClientId,
            @ForAll @StringLength(min = 1, max = 20) String channelName,
            @ForAll @StringLength(min = 6, max = 20) String password
    ) {
        // Filter out invalid inputs
        Assume.that(ownerClientId != null && !ownerClientId.trim().isEmpty());
        Assume.that(playerClientId != null && !playerClientId.trim().isEmpty());
        Assume.that(channelName != null && !channelName.trim().isEmpty());
        // Password must not be whitespace-only (treated as "no password" by implementation)
        Assume.that(password != null && !password.trim().isEmpty());
        
        // CRITICAL: Ensure the clients are DIFFERENT
        Assume.that(!ownerClientId.equals(playerClientId));

        // Setup
        ChannelManager channelManager = new ChannelManager();
        PrivateChannelManager privateChannelManager = new PrivateChannelManager(channelManager);
        
        UUID ownerId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        
        // Create a private channel under ownerClientId
        PrivateChannelManager.PrivateChannelCreationResult result = 
                privateChannelManager.createPrivateChannel(channelName, ownerClientId, ownerId, password);
        
        String channelId = result.getChannelId();
        
        // PROPERTY: Player from different client should be denied access
        // even with the correct password
        PrivateChannelManager.PrivateChannelAccessResult accessResult = 
                privateChannelManager.validateAccess(channelId, playerClientId, password);
        
        assertThat(accessResult.isGranted())
                .as("Player from client '%s' should be denied access to channel owned by client '%s'",
                        playerClientId, ownerClientId)
                .isFalse();
        
        assertThat(accessResult.getErrorCode())
                .as("Error code should indicate forbidden access (NC-403)")
                .isEqualTo("NC-403");
    }

    /**
     * **Feature: starchat-starlink, Property 10: Private Channel Client Isolation**
     * 
     * For any private channel and any player from the SAME client,
     * access should be granted when the correct password is provided.
     * 
     * **Validates: Requirements 7.4, 7.6**
     */
    @Property(tries = 100)
    void playersFromSameClientShouldBeGrantedAccessWithCorrectPassword(
            @ForAll @StringLength(min = 1, max = 20) String clientId,
            @ForAll @StringLength(min = 1, max = 20) String channelName,
            @ForAll @StringLength(min = 6, max = 20) String password
    ) {
        // Filter out invalid inputs
        Assume.that(clientId != null && !clientId.trim().isEmpty());
        Assume.that(channelName != null && !channelName.trim().isEmpty());
        // Password must not be whitespace-only (treated as "no password" by implementation)
        Assume.that(password != null && !password.trim().isEmpty());
        
        // Setup
        ChannelManager channelManager = new ChannelManager();
        PrivateChannelManager privateChannelManager = new PrivateChannelManager(channelManager);
        
        UUID ownerId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        // Create a private channel
        PrivateChannelManager.PrivateChannelCreationResult result = 
                privateChannelManager.createPrivateChannel(channelName, clientId, ownerId, password);
        
        String channelId = result.getChannelId();
        
        // PROPERTY: Player from same client should be granted access with correct password
        PrivateChannelManager.PrivateChannelAccessResult accessResult = 
                privateChannelManager.validateAccess(channelId, clientId, password);
        
        assertThat(accessResult.isGranted())
                .as("Player from same client '%s' should be granted access with correct password", clientId)
                .isTrue();
        
        assertThat(accessResult.getChannel())
                .as("Access result should contain the channel")
                .isNotNull();
        
        assertThat(accessResult.getChannel().getId())
                .as("Access result should contain the correct channel")
                .isEqualTo(channelId);
    }

    /**
     * **Feature: starchat-starlink, Property 10: Private Channel Client Isolation**
     * 
     * For any private channel and any player from the SAME client,
     * access should be denied when an incorrect password is provided.
     * 
     * **Validates: Requirements 7.4**
     */
    @Property(tries = 100)
    void playersFromSameClientShouldBeDeniedAccessWithWrongPassword(
            @ForAll @StringLength(min = 1, max = 20) String clientId,
            @ForAll @StringLength(min = 1, max = 20) String channelName,
            @ForAll @StringLength(min = 6, max = 20) String correctPassword,
            @ForAll @StringLength(min = 6, max = 20) String wrongPassword
    ) {
        // Filter out invalid inputs
        Assume.that(clientId != null && !clientId.trim().isEmpty());
        Assume.that(channelName != null && !channelName.trim().isEmpty());
        // Passwords must not be whitespace-only (treated as "no password" by implementation)
        Assume.that(correctPassword != null && !correctPassword.trim().isEmpty());
        Assume.that(wrongPassword != null && !wrongPassword.trim().isEmpty());
        
        // Ensure passwords are different
        Assume.that(!correctPassword.equals(wrongPassword));
        
        // Setup
        ChannelManager channelManager = new ChannelManager();
        PrivateChannelManager privateChannelManager = new PrivateChannelManager(channelManager);
        
        UUID ownerId = UUID.randomUUID();

        // Create a private channel with the correct password
        PrivateChannelManager.PrivateChannelCreationResult result = 
                privateChannelManager.createPrivateChannel(channelName, clientId, ownerId, correctPassword);
        
        String channelId = result.getChannelId();
        
        // PROPERTY: Player from same client should be denied access with wrong password
        PrivateChannelManager.PrivateChannelAccessResult accessResult = 
                privateChannelManager.validateAccess(channelId, clientId, wrongPassword);
        
        assertThat(accessResult.isGranted())
                .as("Player from same client '%s' should be denied access with wrong password", clientId)
                .isFalse();

        // PROPERTY: For a same-client private channel with a non-empty password, supplying a
        // different non-empty password must be rejected at the password check. The wrong
        // password matches neither the empty-password path nor the correct-password path,
        // so the rejection must surface as the incorrect-password error code (NC-434).
        assertThat(accessResult.getErrorCode())
                .as("Error code should indicate incorrect password (NC-434) for same-client wrong password")
                .isEqualTo("NC-434");
    }

    /**
     * **Feature: starchat-starlink, Property 10: Private Channel Client Isolation**
     * 
     * For any private channel, the isClientMember check should correctly
     * identify players from the same vs different clients.
     * 
     * **Validates: Requirements 7.6**
     */
    @Property(tries = 100)
    void isClientMemberShouldCorrectlyIdentifyClientMembership(
            @ForAll @StringLength(min = 1, max = 20) String ownerClientId,
            @ForAll @StringLength(min = 1, max = 20) String otherClientId,
            @ForAll @StringLength(min = 1, max = 20) String channelName
    ) {
        // Filter out invalid inputs
        Assume.that(ownerClientId != null && !ownerClientId.trim().isEmpty());
        Assume.that(otherClientId != null && !otherClientId.trim().isEmpty());
        Assume.that(channelName != null && !channelName.trim().isEmpty());
        
        // Setup
        ChannelManager channelManager = new ChannelManager();
        PrivateChannelManager privateChannelManager = new PrivateChannelManager(channelManager);
        
        UUID ownerId = UUID.randomUUID();
        
        // Create a private channel
        PrivateChannelManager.PrivateChannelCreationResult result = 
                privateChannelManager.createPrivateChannel(channelName, ownerClientId, ownerId, null);
        
        String channelId = result.getChannelId();

        // PROPERTY: Player from same client should be identified as client member
        assertThat(privateChannelManager.isClientMember(channelId, ownerClientId))
                .as("Player from owner client '%s' should be identified as client member", ownerClientId)
                .isTrue();
        
        // PROPERTY: Player from different client should NOT be identified as client member
        // (only when clients are actually different)
        if (!ownerClientId.equals(otherClientId)) {
            assertThat(privateChannelManager.isClientMember(channelId, otherClientId))
                    .as("Player from different client '%s' should NOT be identified as client member", otherClientId)
                    .isFalse();
        }
    }

    /**
     * **Feature: starchat-starlink, Property 10: Private Channel Client Isolation**
     * 
     * For any private channel, joining via joinPrivateChannel should enforce
     * both client isolation and password verification.
     * 
     * **Validates: Requirements 7.4, 7.6**
     */
    @Property(tries = 100)
    void joinPrivateChannelShouldEnforceBothClientAndPasswordChecks(
            @ForAll @StringLength(min = 1, max = 20) String ownerClientId,
            @ForAll @StringLength(min = 1, max = 20) String playerClientId,
            @ForAll @StringLength(min = 1, max = 20) String channelName,
            @ForAll @StringLength(min = 6, max = 20) String password
    ) {
        // Filter out invalid inputs
        Assume.that(ownerClientId != null && !ownerClientId.trim().isEmpty());
        Assume.that(playerClientId != null && !playerClientId.trim().isEmpty());
        Assume.that(channelName != null && !channelName.trim().isEmpty());
        // Password must not be whitespace-only (treated as "no password" by implementation)
        Assume.that(password != null && !password.trim().isEmpty());
        
        // Setup
        ChannelManager channelManager = new ChannelManager();
        PrivateChannelManager privateChannelManager = new PrivateChannelManager(channelManager);
        
        UUID ownerId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        
        // Create a private channel
        PrivateChannelManager.PrivateChannelCreationResult result = 
                privateChannelManager.createPrivateChannel(channelName, ownerClientId, ownerId, password);
        
        String channelId = result.getChannelId();
        
        // Attempt to join
        PrivateChannelManager.PrivateChannelAccessResult joinResult = 
                privateChannelManager.joinPrivateChannel(channelId, playerId, playerClientId, password);

        boolean sameClient = ownerClientId.equals(playerClientId);
        
        if (sameClient) {
            // PROPERTY: Same client + correct password = access granted
            assertThat(joinResult.isGranted())
                    .as("Player from same client with correct password should be able to join")
                    .isTrue();
            
            // Verify player was actually added to the channel
            Channel channel = channelManager.getChannel(channelId);
            assertThat(channel.isMember(playerId))
                    .as("Player should be a member of the channel after joining")
                    .isTrue();
        } else {
            // PROPERTY: Different client = access denied (regardless of password)
            assertThat(joinResult.isGranted())
                    .as("Player from different client should be denied access")
                    .isFalse();
            
            assertThat(joinResult.getErrorCode())
                    .as("Error code should indicate forbidden access (NC-403)")
                    .isEqualTo("NC-403");
            
            // Verify player was NOT added to the channel
            Channel channel = channelManager.getChannel(channelId);
            assertThat(channel.isMember(playerId))
                    .as("Player should NOT be a member of the channel")
                    .isFalse();
        }
    }

    /**
     * **Feature: starchat-starlink, Property 10: Private Channel Client Isolation**
     * 
     * For any set of private channels across multiple clients, each channel
     * should only be accessible by players from its owning client.
     * 
     * **Validates: Requirements 7.4, 7.6**
     */
    @Property(tries = 100)
    void multipleChannelsAcrossClientsShouldMaintainIsolation(
            @ForAll @Size(min = 2, max = 5) List<@StringLength(min = 1, max = 15) String> clientIds
    ) {
        // Filter out invalid inputs
        for (String clientId : clientIds) {
            Assume.that(clientId != null && !clientId.trim().isEmpty());
        }
        
        // Ensure we have at least 2 unique clients
        Set<String> uniqueClients = new HashSet<>(clientIds);
        Assume.that(uniqueClients.size() >= 2);
        
        // Setup
        ChannelManager channelManager = new ChannelManager();
        PrivateChannelManager privateChannelManager = new PrivateChannelManager(channelManager);

        // Create one channel per unique client
        Map<String, String> channelToClient = new HashMap<>();
        Map<String, String> channelPasswords = new HashMap<>();
        
        for (String clientId : uniqueClients) {
            UUID ownerId = UUID.randomUUID();
            String password = "pass" + clientId.hashCode();
            
            PrivateChannelManager.PrivateChannelCreationResult result = 
                    privateChannelManager.createPrivateChannel("Channel-" + clientId, clientId, ownerId, password);
            
            channelToClient.put(result.getChannelId(), clientId);
            channelPasswords.put(result.getChannelId(), password);
        }
        
        // PROPERTY: For each channel, verify isolation
        for (Map.Entry<String, String> entry : channelToClient.entrySet()) {
            String channelId = entry.getKey();
            String owningClient = entry.getValue();
            String password = channelPasswords.get(channelId);
            
            for (String testClient : uniqueClients) {
                UUID testPlayerId = UUID.randomUUID();
                
                PrivateChannelManager.PrivateChannelAccessResult accessResult = 
                        privateChannelManager.validateAccess(channelId, testClient, password);
                
                if (testClient.equals(owningClient)) {
                    // Same client - should be granted
                    assertThat(accessResult.isGranted())
                            .as("Player from owning client '%s' should access channel '%s'", 
                                    testClient, channelId)
                            .isTrue();
                } else {
                    // Different client - should be denied
                    assertThat(accessResult.isGranted())
                            .as("Player from client '%s' should NOT access channel '%s' owned by '%s'", 
                                    testClient, channelId, owningClient)
                            .isFalse();
                    
                    assertThat(accessResult.getErrorCode())
                            .isEqualTo("NC-403");
                }
            }
        }
    }
}
