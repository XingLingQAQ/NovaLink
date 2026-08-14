package com.nova.link.auth;

import java.util.Locale;

/**
 * Web-panel RBAC roles, ordered from least to most privileged:
 * {@code VIEWER < ADMIN < SUPER_ADMIN}.
 *
 * <p>This is the role carried in the JWT {@code role} claim and used by the
 * REST permission matrix:
 * <ul>
 *   <li><b>VIEWER</b> — all GET endpoints + read-only WebSocket queries.</li>
 *   <li><b>ADMIN</b> — VIEWER + player punishments (mute/unmute/kick/ban/unban),
 *       channel CRUD + invite, POST /api/messages, notification management.</li>
 *   <li><b>SUPER_ADMIN</b> — ADMIN + console commands, client disconnect,
 *       config reload, settings update, webhook create/delete.</li>
 * </ul>
 */
public enum PanelRole {

    VIEWER(0),
    ADMIN(1),
    SUPER_ADMIN(2);

    private final int level;

    PanelRole(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    /**
     * @return true when this role is at least as privileged as {@code other}
     */
    public boolean atLeast(PanelRole other) {
        return other != null && this.level >= other.level;
    }

    /**
     * Parses a role name (case-insensitive).
     *
     * @return the matching role, or null when unknown/null
     */
    public static PanelRole fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return PanelRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
