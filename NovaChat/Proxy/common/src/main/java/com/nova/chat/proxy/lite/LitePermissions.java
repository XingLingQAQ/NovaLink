package com.nova.chat.proxy.lite;

import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;

import java.util.UUID;

/**
 * The moderation gate for {@code lite} mode.
 *
 * <p>Lite has no permission hierarchy: the backend resolves a four-level
 * {@code PermissionManager} role plus a console sentinel, none of which exists
 * here. Each action therefore checks the node its platform command already
 * gates on — {@code novachat.mute}, {@code novachat.kick}, and so on — and the
 * console identity is always allowed.
 *
 * <p>The channel's join {@code permission} is deliberately *not* usable here:
 * it is a membership gate, not a moderation gate, and reusing it would let
 * every member of an open channel moderate everyone else.
 */
final class LitePermissions {

    /** The sentinel the platform commands use for console-originated actions. */
    private static final UUID CONSOLE_ID = new UUID(0L, 0L);

    private final ProxyPlatform platform;

    /**
     * @param platform the platform adapter (player lookup)
     */
    LitePermissions(ProxyPlatform platform) {
        this.platform = platform;
    }

    /**
     * Whether this operator may perform a moderation action.
     *
     * @param operatorId the resolved operator
     * @param permission the node that gates this action
     * @return {@code true} when the operator may perform it
     */
    boolean canModerate(UUID operatorId, String permission) {
        if (operatorId == null) {
            return false;
        }
        if (operatorId.equals(CONSOLE_ID)) {
            return true;
        }
        ProxyPlayer operator = platform.getPlayer(operatorId);
        return operator != null && operator.hasPermission(permission);
    }
}
