package com.nova.link.integration;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import org.junit.jupiter.api.*;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for channel join and leave flow verification.
 * 
 * Requirements: 23.8 - Verify channel join and leave flow
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChannelOperationsTest {

    private static IntegrationTestHelper helper;
    private static final int TEST_PORT = 18896;

    @BeforeAll
    static void setUp() throws Exception {
        helper = new IntegrationTestHelper();
        helper.startServer(TEST_PORT);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (helper != null) {
            helper.stopServer();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Client should successfully join a channel")
    void testChannelJoin() throws Exception {
        helper.registerClient("JoinClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("JoinClient", "password").get(5, TimeUnit.SECONDS);
        
        ChannelActionPacket joinAction = new ChannelActionPacket(ChannelAction.JOIN, "global");
        client.sendPacket(joinAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.JOIN);
        assertThat(response.getChannelId()).isEqualTo("global");
        assertThat(response.getErrorCode()).isEmpty();
        
        client.disconnect();
    }

    @Test
    @Order(2)
    @DisplayName("Client should successfully leave a channel")
    void testChannelLeave() throws Exception {
        helper.registerClient("LeaveClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("LeaveClient", "password").get(5, TimeUnit.SECONDS);
        
        // First join
        ChannelActionPacket joinAction = new ChannelActionPacket(ChannelAction.JOIN, "test-channel");
        client.sendPacket(joinAction);
        client.waitForPacket(ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS);
        
        // Then leave
        ChannelActionPacket leaveAction = new ChannelActionPacket(ChannelAction.LEAVE, "test-channel");
        client.sendPacket(leaveAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.LEAVE);
        assertThat(response.getChannelId()).isEqualTo("test-channel");
        
        client.disconnect();
    }

    @Test
    @Order(3)
    @DisplayName("Client should be able to join multiple channels")
    void testJoinMultipleChannels() throws Exception {
        helper.registerClient("MultiChannelClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("MultiChannelClient", "password").get(5, TimeUnit.SECONDS);
        
        String[] channels = {"global", "local", "staff", "vip"};
        
        for (String channelId : channels) {
            ChannelActionPacket joinAction = new ChannelActionPacket(ChannelAction.JOIN, channelId);
            client.sendPacket(joinAction);
            
            ChannelActionResponsePacket response = client.waitForPacket(
                ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
            );
            
            assertThat(response.isSuccess())
                .as("Should successfully join channel: " + channelId)
                .isTrue();
            assertThat(response.getChannelId()).isEqualTo(channelId);
        }
        
        client.disconnect();
    }

    @Test
    @Order(4)
    @DisplayName("Channel join with password should work")
    void testChannelJoinWithPassword() throws Exception {
        helper.registerClient("PasswordClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("PasswordClient", "password").get(5, TimeUnit.SECONDS);
        
        ChannelActionPacket joinAction = new ChannelActionPacket(
            ChannelAction.JOIN, 
            "private-channel",
            "channel-password"
        );
        client.sendPacket(joinAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.JOIN);
        
        client.disconnect();
    }

    @Test
    @Order(5)
    @DisplayName("Unauthenticated client should not perform channel actions")
    void testUnauthenticatedChannelAction() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        // Do NOT authenticate
        
        ChannelActionPacket joinAction = new ChannelActionPacket(ChannelAction.JOIN, "global");
        client.sendPacket(joinAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-401");
        
        client.disconnect();
    }

    @Test
    @Order(6)
    @DisplayName("Channel create action should work")
    void testChannelCreate() throws Exception {
        helper.registerClient("CreateClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("CreateClient", "password").get(5, TimeUnit.SECONDS);
        
        ChannelActionPacket createAction = new ChannelActionPacket(ChannelAction.CREATE, "new-channel");
        createAction.addExtra("displayName", "New Channel");
        createAction.addExtra("scope", "GLOBAL");
        client.sendPacket(createAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.CREATE);
        assertThat(response.getChannelId()).isEqualTo("new-channel");
        
        client.disconnect();
    }

    @Test
    @Order(7)
    @DisplayName("Channel delete action should work")
    void testChannelDelete() throws Exception {
        helper.registerClient("DeleteClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("DeleteClient", "password").get(5, TimeUnit.SECONDS);
        
        // First create a channel
        ChannelActionPacket createAction = new ChannelActionPacket(ChannelAction.CREATE, "temp-channel");
        client.sendPacket(createAction);
        client.waitForPacket(ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS);
        
        // Then delete it
        ChannelActionPacket deleteAction = new ChannelActionPacket(ChannelAction.DELETE, "temp-channel");
        client.sendPacket(deleteAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.DELETE);
        
        client.disconnect();
    }

    @Test
    @Order(8)
    @DisplayName("Channel invite action should work")
    void testChannelInvite() throws Exception {
        helper.registerClient("InviteClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("InviteClient", "password").get(5, TimeUnit.SECONDS);
        
        ChannelActionPacket inviteAction = new ChannelActionPacket(ChannelAction.INVITE, "private-channel");
        inviteAction.addExtra("targetPlayer", "InvitedPlayer");
        client.sendPacket(inviteAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.INVITE);
        
        client.disconnect();
    }

    @Test
    @Order(9)
    @DisplayName("Channel accept action should work")
    void testChannelAccept() throws Exception {
        helper.registerClient("AcceptClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("AcceptClient", "password").get(5, TimeUnit.SECONDS);
        
        ChannelActionPacket acceptAction = new ChannelActionPacket(ChannelAction.ACCEPT, "invited-channel");
        acceptAction.addExtra("inviteCode", "ABC123");
        client.sendPacket(acceptAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.ACCEPT);
        
        client.disconnect();
    }

    @Test
    @Order(10)
    @DisplayName("All channel actions should include extra data in response")
    void testChannelActionExtraData() throws Exception {
        helper.registerClient("ExtraDataClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("ExtraDataClient", "password").get(5, TimeUnit.SECONDS);
        
        ChannelActionPacket joinAction = new ChannelActionPacket(ChannelAction.JOIN, "data-channel");
        joinAction.addExtra("customKey", "customValue");
        client.sendPacket(joinAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        // Response may contain extra data from server
        assertThat(response.getExtra()).isNotNull();
        
        client.disconnect();
    }
}
