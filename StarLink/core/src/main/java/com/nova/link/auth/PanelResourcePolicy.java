package com.nova.link.auth;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import io.jsonwebtoken.Claims;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resource-level authorization shared by the panel REST and WebSocket APIs.
 *
 * <p>Panel accounts are operators rather than game players, so there is no
 * trustworthy mapping from a panel username to a private-channel member UUID.
 * Private channel access is therefore reserved for {@link PanelRole#SUPER_ADMIN}.
 */
public final class PanelResourcePolicy {

    private final AuthManager authManager;
    private final ChannelManager channelManager;

    public PanelResourcePolicy(AuthManager authManager, ChannelManager channelManager) {
        this.authManager = authManager;
        this.channelManager = channelManager;
    }

    /** Resolves the effective role, preferring the current account role over a stale JWT claim. */
    public PanelRole resolveRole(Claims claims) {
        if (claims == null) {
            return null;
        }
        return resolveRole(claims.get("username", String.class), claims.get("role", String.class));
    }

    /** Resolves the effective role for an authenticated WebSocket session. */
    public PanelRole resolveRole(String username, String tokenRole) {
        PanelUserCredentials current = authManager != null ? authManager.getPanelUser(username) : null;
        if (current != null) {
            return current.getRole();
        }
        return PanelRole.fromString(tokenRole);
    }

    public boolean canViewChannel(PanelRole role, Channel channel) {
        if (role == null || channel == null) {
            return false;
        }
        return canViewScope(role, channel.getScope());
    }

    public boolean canViewScope(PanelRole role, ChannelScope scope) {
        if (role == null || scope == null) {
            return false;
        }
        return switch (role) {
            case VIEWER -> scope == ChannelScope.GLOBAL;
            case ADMIN -> scope == ChannelScope.GLOBAL || scope == ChannelScope.SERVER;
            case SUPER_ADMIN -> true;
        };
    }

    public boolean canSubscribe(PanelRole role, Channel channel) {
        return canViewChannel(role, channel);
    }

    public boolean canSend(PanelRole role, Channel channel) {
        return role != null && role.atLeast(PanelRole.ADMIN) && canViewChannel(role, channel);
    }

    public boolean canManageChannel(PanelRole role, Channel channel) {
        return role != null && role.atLeast(PanelRole.ADMIN) && canViewChannel(role, channel);
    }

    public boolean canManageScope(PanelRole role, ChannelScope scope) {
        return role != null && role.atLeast(PanelRole.ADMIN) && canViewScope(role, scope);
    }

    /** Server/client source metadata is operational data and requires ADMIN or above. */
    public boolean canViewInfrastructureSource(PanelRole role) {
        return role != null && role.atLeast(PanelRole.ADMIN);
    }

    /** Connection identifiers and remote addresses are restricted to SUPER_ADMIN. */
    public boolean canViewConnectionDetails(PanelRole role) {
        return role == PanelRole.SUPER_ADMIN;
    }

    public Set<String> visibleChannelIds(PanelRole role) {
        Set<String> ids = new LinkedHashSet<>();
        if (channelManager == null) {
            return ids;
        }
        for (Channel channel : channelManager.getAllChannels()) {
            if (canViewChannel(role, channel)) {
                ids.add(channel.getId());
            }
        }
        return ids;
    }
}
