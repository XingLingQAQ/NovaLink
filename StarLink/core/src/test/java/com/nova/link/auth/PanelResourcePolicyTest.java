package com.nova.link.auth;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Panel resource capability policy")
class PanelResourcePolicyTest {

    private PanelResourcePolicy policy;
    private Channel global;
    private Channel server;
    private Channel privateChannel;

    @BeforeEach
    void setUp() {
        policy = new PanelResourcePolicy(new AuthManager(), new ChannelManager());
        global = new Channel("global", "Global", ChannelScope.GLOBAL, null);
        server = new Channel("survival", "Survival", ChannelScope.SERVER, "Survival");
        privateChannel = new Channel("private", "Private", ChannelScope.PRIVATE, "Survival");
    }

    @Test
    @DisplayName("VIEWER sees/subscribes only GLOBAL and cannot send")
    void viewerMatrix() {
        assertThat(policy.canViewChannel(PanelRole.VIEWER, global)).isTrue();
        assertThat(policy.canSubscribe(PanelRole.VIEWER, global)).isTrue();
        assertThat(policy.canViewChannel(PanelRole.VIEWER, server)).isFalse();
        assertThat(policy.canViewChannel(PanelRole.VIEWER, privateChannel)).isFalse();
        assertThat(policy.canSend(PanelRole.VIEWER, global)).isFalse();
    }

    @Test
    @DisplayName("ADMIN sees/subscribes/sends GLOBAL and SERVER but not PRIVATE")
    void adminMatrix() {
        assertThat(policy.canSend(PanelRole.ADMIN, global)).isTrue();
        assertThat(policy.canSend(PanelRole.ADMIN, server)).isTrue();
        assertThat(policy.canViewChannel(PanelRole.ADMIN, privateChannel)).isFalse();
        assertThat(policy.canSubscribe(PanelRole.ADMIN, privateChannel)).isFalse();
        assertThat(policy.canSend(PanelRole.ADMIN, privateChannel)).isFalse();
        assertThat(policy.canManageChannel(PanelRole.ADMIN, privateChannel)).isFalse();
        assertThat(policy.canManageScope(PanelRole.ADMIN, ChannelScope.PRIVATE)).isFalse();
    }

    @Test
    @DisplayName("SUPER_ADMIN has all channel capabilities and sensitive fields")
    void superAdminMatrix() {
        assertThat(policy.canViewChannel(PanelRole.SUPER_ADMIN, privateChannel)).isTrue();
        assertThat(policy.canSubscribe(PanelRole.SUPER_ADMIN, privateChannel)).isTrue();
        assertThat(policy.canSend(PanelRole.SUPER_ADMIN, privateChannel)).isTrue();
        assertThat(policy.canManageChannel(PanelRole.SUPER_ADMIN, privateChannel)).isTrue();
        assertThat(policy.canManageScope(PanelRole.SUPER_ADMIN, ChannelScope.PRIVATE)).isTrue();
        assertThat(policy.canViewConnectionDetails(PanelRole.SUPER_ADMIN)).isTrue();
        assertThat(policy.canViewConnectionDetails(PanelRole.ADMIN)).isFalse();
    }
}
