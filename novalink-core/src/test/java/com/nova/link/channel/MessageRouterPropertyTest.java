package com.nova.link.channel;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for MessageRouter.
 * 
 * Tests correctness properties defined in the design document.
 * 
 * Note: These tests focus on the routing logic without network dependencies.
 * We test the calculateRecipients and canSendToChannel methods which contain
 * the core routing logic, avoiding the need to mock Netty channels.
 */
public class MessageRouterPropertyTest {

    /**
     * **Feature: starchat-starlink, Property 5: Message Routing Scope Isolation**
     * 
     * For any SERVER-scoped channel, messages should only be delivered to players
     * connected through the same client, never crossing client boundaries.
     * 
     * This test verifies that canSendToChannel correctly enforces client isolation
     * for SERVER-scoped channels.
     * 
     * **Validates: Requirements 3.2, 3.5, 5.3**
     */
    @Property(tries = 100)
    void serverScopedChannelOnlyAllowsSameClientToSend(
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String ownerClientId,
            @ForAll @StringLength(min = 1, max = 20) String otherClientId
    ) {
        // Ensure we have two different clients
        Assume.that(!ownerClientId.equals(otherClientId));
        
        // Setup
        ChannelManager channelManager = new ChannelManager();
        
        // Create a SERVER-scoped channel owned by ownerClientId
        ChannelConfig config = ChannelConfig.builder()
                .id(channelId)
                .displayName("Test Channel")
                .scope(ChannelScope.SERVER)
                .clientId(ownerClientId)
                .build();
        Channel channel = channelManager.createChannel(config);
        
        // Create a minimal router (no network handler needed for canSendToChannel)
        MessageRouter router = new MessageRouter(channelManager, new TestableServerNetworkHandler());
        
        // PROPERTY: Only the owning client can send to SERVER channels
        assertThat(router.canSendToChannel(channel, ownerClientId))
                .as("Owner client should be able to send to SERVER channel")
                .isTrue();
        
        assertThat(router.canSendToChannel(channel, otherClientId))
                .as("Other clients should NOT be able to send to SERVER channel")
                .isFalse();
    }

    /**
     * Property 5 (continued): PRIVATE-scoped channels also enforce client isolation.
     * 
     * **Validates: Requirements 3.2, 3.5, 5.3**
     */
    @Property(tries = 100)
    void privateScopedChannelOnlyAllowsSameClientToSend(
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String ownerClientId,
            @ForAll @StringLength(min = 1, max = 20) String otherClientId
    ) {
        // Ensure we have two different clients
        Assume.that(!ownerClientId.equals(otherClientId));
        
        // Setup
        ChannelManager channelManager = new ChannelManager();
        
        // Create a PRIVATE-scoped channel owned by ownerClientId
        ChannelConfig config = ChannelConfig.builder()
                .id(channelId)
                .displayName("Private Channel")
                .scope(ChannelScope.PRIVATE)
                .clientId(ownerClientId)
                .ownerId(UUID.randomUUID())
                .build();
        Channel channel = channelManager.createChannel(config);
        
        MessageRouter router = new MessageRouter(channelManager, new TestableServerNetworkHandler());
        
        // PROPERTY: Only the owning client can send to PRIVATE channels
        assertThat(router.canSendToChannel(channel, ownerClientId))
                .as("Owner client should be able to send to PRIVATE channel")
                .isTrue();
        
        assertThat(router.canSendToChannel(channel, otherClientId))
                .as("Other clients should NOT be able to send to PRIVATE channel")
                .isFalse();
    }

    /**
     * Property: GLOBAL-scoped channels allow any client to send.
     * This is the opposite of SERVER/PRIVATE - global channels have no client restriction.
     * 
     * **Validates: Requirements 4.3**
     */
    @Property(tries = 100)
    void globalScopedChannelAllowsAnyClientToSend(
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String clientId1,
            @ForAll @StringLength(min = 1, max = 20) String clientId2
    ) {
        // Ensure we have two different clients
        Assume.that(!clientId1.equals(clientId2));
        
        // Setup
        ChannelManager channelManager = new ChannelManager();
        
        // Create a GLOBAL-scoped channel (no clientId)
        ChannelConfig config = ChannelConfig.builder()
                .id(channelId)
                .displayName("Global Channel")
                .scope(ChannelScope.GLOBAL)
                .build();
        Channel channel = channelManager.createChannel(config);
        
        MessageRouter router = new MessageRouter(channelManager, new TestableServerNetworkHandler());
        
        // PROPERTY: Any client can send to GLOBAL channels
        assertThat(router.canSendToChannel(channel, clientId1))
                .as("Any client should be able to send to GLOBAL channel")
                .isTrue();
        
        assertThat(router.canSendToChannel(channel, clientId2))
                .as("Any client should be able to send to GLOBAL channel")
                .isTrue();
    }

    /**
     * Property: SERVER channel scope is correctly identified and isolated.
     * The channel's clientId must match for SERVER scope.
     * 
     * **Validates: Requirements 3.5, 5.3**
     */
    @Property(tries = 100)
    void serverChannelClientIdMustMatchForAccess(
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String channelClientId,
            @ForAll @StringLength(min = 1, max = 20) String senderClientId
    ) {
        // Setup
        ChannelManager channelManager = new ChannelManager();
        
        // Create a SERVER-scoped channel
        ChannelConfig config = ChannelConfig.builder()
                .id(channelId)
                .displayName("Test Channel")
                .scope(ChannelScope.SERVER)
                .clientId(channelClientId)
                .build();
        Channel channel = channelManager.createChannel(config);
        
        MessageRouter router = new MessageRouter(channelManager, new TestableServerNetworkHandler());
        
        // PROPERTY: canSendToChannel returns true IFF clientIds match
        boolean canSend = router.canSendToChannel(channel, senderClientId);
        boolean clientIdsMatch = channelClientId.equals(senderClientId);
        
        assertThat(canSend)
                .as("canSendToChannel should return true iff clientIds match for SERVER scope")
                .isEqualTo(clientIdsMatch);
    }

    /**
     * Property: PRIVATE channel scope is correctly identified and isolated.
     * The channel's clientId must match for PRIVATE scope.
     * 
     * **Validates: Requirements 7.4, 7.6**
     */
    @Property(tries = 100)
    void privateChannelClientIdMustMatchForAccess(
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String channelClientId,
            @ForAll @StringLength(min = 1, max = 20) String senderClientId
    ) {
        // Setup
        ChannelManager channelManager = new ChannelManager();
        
        // Create a PRIVATE-scoped channel
        ChannelConfig config = ChannelConfig.builder()
                .id(channelId)
                .displayName("Private Channel")
                .scope(ChannelScope.PRIVATE)
                .clientId(channelClientId)
                .ownerId(UUID.randomUUID())
                .build();
        Channel channel = channelManager.createChannel(config);
        
        MessageRouter router = new MessageRouter(channelManager, new TestableServerNetworkHandler());
        
        // PROPERTY: canSendToChannel returns true IFF clientIds match
        boolean canSend = router.canSendToChannel(channel, senderClientId);
        boolean clientIdsMatch = channelClientId.equals(senderClientId);
        
        assertThat(canSend)
                .as("canSendToChannel should return true iff clientIds match for PRIVATE scope")
                .isEqualTo(clientIdsMatch);
    }

    /**
     * Property: Channel scope determines routing behavior consistently.
     * 
     * **Validates: Requirements 3.2**
     */
    @Property(tries = 100)
    void channelScopeDeterminesRoutingBehavior(
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String clientId
    ) {
        ChannelManager channelManager = new ChannelManager();
        MessageRouter router = new MessageRouter(channelManager, new TestableServerNetworkHandler());
        
        // Create channels of each scope
        Channel globalChannel = channelManager.createChannel(
                ChannelConfig.builder()
                        .id(channelId + "_global")
                        .scope(ChannelScope.GLOBAL)
                        .build()
        );
        
        Channel serverChannel = channelManager.createChannel(
                ChannelConfig.builder()
                        .id(channelId + "_server")
                        .scope(ChannelScope.SERVER)
                        .clientId(clientId)
                        .build()
        );
        
        Channel privateChannel = channelManager.createChannel(
                ChannelConfig.builder()
                        .id(channelId + "_private")
                        .scope(ChannelScope.PRIVATE)
                        .clientId(clientId)
                        .ownerId(UUID.randomUUID())
                        .build()
        );
        
        // PROPERTY: GLOBAL channels always allow sending
        assertThat(router.canSendToChannel(globalChannel, clientId)).isTrue();
        assertThat(router.canSendToChannel(globalChannel, "any_other_client")).isTrue();
        
        // PROPERTY: SERVER channels only allow same client
        assertThat(router.canSendToChannel(serverChannel, clientId)).isTrue();
        assertThat(router.canSendToChannel(serverChannel, "different_client")).isFalse();
        
        // PROPERTY: PRIVATE channels only allow same client
        assertThat(router.canSendToChannel(privateChannel, clientId)).isTrue();
        assertThat(router.canSendToChannel(privateChannel, "different_client")).isFalse();
    }

    /**
     * **Feature: starchat-starlink, Property 7: Global Channel Cross-Client Routing**
     * 
     * For any GLOBAL-scoped channel message, all online players with the required 
     * permission across all clients should receive the message.
     * 
     * This test verifies that:
     * 1. All connected clients receive global channel messages when no permission is required
     * 2. Only clients with the required permission receive messages when permission is set
     * 
     * **Validates: Requirements 4.3**
     */
    @Property(tries = 100)
    void globalChannelRoutesToAllConnectedClientsWithPermission(
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @Size(min = 1, max = 10) List<@StringLength(min = 1, max = 20) String> clientIds,
            @ForAll @StringLength(min = 1, max = 30) String permissionNode
    ) {
        // Ensure unique client IDs
        Set<String> uniqueClientIds = new HashSet<>(clientIds);
        Assume.that(uniqueClientIds.size() >= 1);
        
        // Setup
        ChannelManager channelManager = new ChannelManager();
        TestableServerNetworkHandlerWithClients networkHandler = new TestableServerNetworkHandlerWithClients();
        
        // Register all clients as connected
        for (String clientId : uniqueClientIds) {
            networkHandler.addConnectedClient(clientId);
        }
        
        // Create a GLOBAL channel with permission requirement
        ChannelConfig config = ChannelConfig.builder()
                .id(channelId)
                .displayName("Global Test Channel")
                .scope(ChannelScope.GLOBAL)
                .permission(permissionNode)
                .build();
        Channel channel = channelManager.createChannel(config);
        
        MessageRouter router = new MessageRouter(channelManager, networkHandler);
        
        // Define which clients have permission (first half have permission)
        List<String> clientList = new ArrayList<>(uniqueClientIds);
        Set<String> clientsWithPermission = new HashSet<>();
        for (int i = 0; i < clientList.size() / 2 + 1; i++) {
            clientsWithPermission.add(clientList.get(i));
        }
        
        // Set permission checker
        router.setPermissionChecker((clientId, permission) -> 
                clientsWithPermission.contains(clientId));
        
        // Calculate recipients
        Set<String> recipients = router.calculateRecipients(channel, clientList.get(0));
        
        // PROPERTY: Only clients with permission should receive the message
        assertThat(recipients)
                .as("Recipients should only include clients with permission")
                .containsExactlyInAnyOrderElementsOf(clientsWithPermission);
        
        // PROPERTY: All clients with permission should receive the message
        for (String clientId : clientsWithPermission) {
            assertThat(recipients)
                    .as("Client with permission should receive the message")
                    .contains(clientId);
        }
        
        // PROPERTY: No clients without permission should receive the message
        for (String clientId : uniqueClientIds) {
            if (!clientsWithPermission.contains(clientId)) {
                assertThat(recipients)
                        .as("Client without permission should NOT receive the message")
                        .doesNotContain(clientId);
            }
        }
    }

    /**
     * Property 7 (continued): Global channels without permission requirement 
     * route to ALL connected clients.
     * 
     * **Validates: Requirements 4.3**
     */
    @Property(tries = 100)
    void globalChannelWithoutPermissionRoutesToAllClients(
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @Size(min = 1, max = 10) List<@StringLength(min = 1, max = 20) String> clientIds
    ) {
        // Ensure unique client IDs
        Set<String> uniqueClientIds = new HashSet<>(clientIds);
        Assume.that(uniqueClientIds.size() >= 1);
        
        // Setup
        ChannelManager channelManager = new ChannelManager();
        TestableServerNetworkHandlerWithClients networkHandler = new TestableServerNetworkHandlerWithClients();
        
        // Register all clients as connected
        for (String clientId : uniqueClientIds) {
            networkHandler.addConnectedClient(clientId);
        }
        
        // Create a GLOBAL channel WITHOUT permission requirement
        ChannelConfig config = ChannelConfig.builder()
                .id(channelId)
                .displayName("Global Test Channel")
                .scope(ChannelScope.GLOBAL)
                // No permission set
                .build();
        Channel channel = channelManager.createChannel(config);
        
        MessageRouter router = new MessageRouter(channelManager, networkHandler);
        
        // Calculate recipients
        Set<String> recipients = router.calculateRecipients(channel, "any_sender");
        
        // PROPERTY: ALL connected clients should receive the message
        assertThat(recipients)
                .as("All connected clients should receive global channel message when no permission required")
                .containsExactlyInAnyOrderElementsOf(uniqueClientIds);
    }

    /**
     * A testable ServerNetworkHandler that doesn't require actual network connections.
     * Used for testing routing logic without network dependencies.
     */
    private static class TestableServerNetworkHandler extends com.nova.link.network.ServerNetworkHandler {
        public TestableServerNetworkHandler() {
            super(1, false);
        }
    }

    /**
     * A testable ServerNetworkHandler that simulates connected clients.
     * Used for testing global channel routing across multiple clients.
     */
    private static class TestableServerNetworkHandlerWithClients extends com.nova.link.network.ServerNetworkHandler {
        private final Map<String, MockClientConnection> mockConnections = new HashMap<>();

        public TestableServerNetworkHandlerWithClients() {
            super(1, false);
        }

        public void addConnectedClient(String clientId) {
            MockClientConnection connection = new MockClientConnection(clientId);
            mockConnections.put(clientId, connection);
        }

        @Override
        public Set<com.nova.link.network.ClientConnection> getConnections() {
            return new HashSet<>(mockConnections.values());
        }

        @Override
        public com.nova.link.network.ClientConnection findByClientId(String clientId) {
            return mockConnections.get(clientId);
        }
    }

    /**
     * Mock client connection for testing without actual Netty channels.
     */
    private static class MockClientConnection extends com.nova.link.network.ClientConnection {
        private final String mockClientId;
        private boolean mockAuthenticated = true;
        private boolean mockActive = true;

        public MockClientConnection(String clientId) {
            super(null); // No actual channel needed for testing
            this.mockClientId = clientId;
        }

        @Override
        public String getClientId() {
            return mockClientId;
        }

        @Override
        public boolean isAuthenticated() {
            return mockAuthenticated;
        }

        @Override
        public boolean isActive() {
            return mockActive;
        }
    }
}
