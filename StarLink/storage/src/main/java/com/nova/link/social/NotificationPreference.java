package com.nova.link.social;

import java.util.Objects;
import java.util.UUID;

/**
 * Per-player notification preferences (§11.6 Project 18 / PANEL proposal 08 —
 * social relations & ignore).
 *
 * <p>Scope: this object holds ONLY the notification-pref knobs that proposal 08
 * introduces (mentions opt-in). It deliberately does <strong>NOT</strong>
 * duplicate {@code PlayerState.isDmEnabled()} — DM opt-out already lives on
 * {@code PlayerState} (schema v6 {@code dm_enabled}) and is governed by the
 * existing permission/audit path; folding it in here would split a single
 * concept across two stores. Do NOT add a {@code dmsEnabled} field here.
 *
 * <p>Preferences are keyed by player id and upserted as a whole row. When a
 * player has no persisted preference the provider returns
 * {@link #defaults(UUID)} rather than null, so callers can read the fields
 * unconditionally.
 *
 * <p>Requirements: §11.6 item-18 (social relations & ignore)
 */
public final class NotificationPreference {

    private final UUID playerId;
    private final boolean mentionsEnabled;
    private final long updatedAt;

    /**
     * Full-field constructor used by the store layer when hydrating persisted
     * rows.
     *
     * @param playerId        the player these preferences belong to (not null)
     * @param mentionsEnabled whether the player accepts mention notifications
     * @param updatedAt       epoch millis when the row was last touched
     */
    public NotificationPreference(UUID playerId, boolean mentionsEnabled, long updatedAt) {
        this.playerId = playerId;
        this.mentionsEnabled = mentionsEnabled;
        this.updatedAt = updatedAt;
    }

    /**
     * Returns the default preferences for a player who has nothing persisted.
     * Mentions default to enabled (the opt-in is the suppression, not the
     * enabling). {@code updatedAt} is stamped to now.
     *
     * @param playerId the player id (not null)
     * @return a fresh default preference, never null
     */
    public static NotificationPreference defaults(UUID playerId) {
        return new NotificationPreference(playerId, true, System.currentTimeMillis());
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public boolean isMentionsEnabled() {
        return mentionsEnabled;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NotificationPreference that = (NotificationPreference) o;
        return Objects.equals(playerId, that.playerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId);
    }

    @Override
    public String toString() {
        return "NotificationPreference{playerId=" + playerId
                + ", mentionsEnabled=" + mentionsEnabled
                + ", updatedAt=" + updatedAt + '}';
    }
}
