package com.nova.link.announcement;

/**
 * Delivery policy for a campaign. Persisted per campaign and exposed via REST,
 * but currently has no behavior impact beyond storage and contract round-trip.
 *
 * <p><b>Current reality:</b> all campaigns are delivered as ordinary channel
 * messages regardless of the policy value. The {@code TITLE_FALLBACK} and
 * {@code ACTIONBAR_FALLBACK} values exist in the DB schema and the panel
 * dropdown but are never consulted by the delivery path. The title/actionbar
 * degradation semantic described in §11.6 提案 06 is a deferred design and is
 * NOT wired up.
 *
 * <ul>
 *   <li>{@link #INSTANT} — deliver as a normal channel message (only behavior
 *       actually implemented).</li>
 *   <li>{@link #TITLE_FALLBACK} — <b>reserved</b>. Stored and round-tripped
 *       faithfully but currently equivalent to {@link #INSTANT} in effect.
 *       Intended future semantic: prefer title display; fall back to channel
 *       message when the target platform has no title support.</li>
 *   <li>{@link #ACTIONBAR_FALLBACK} — <b>reserved</b>. Same caveat; intended
 *       to prefer action-bar display with channel-message fallback.</li>
 * </ul>
 *
 * <p>Do not remove enum values without a DB migration — existing rows may
 * reference them.
 */
public enum DeliveryPolicy {
    INSTANT,
    TITLE_FALLBACK,
    ACTIONBAR_FALLBACK;

    /**
     * Parses an external (database or REST) policy value, case-insensitive.
     *
     * @param value the external value ("INSTANT", "TITLE_FALLBACK",
     *              "ACTIONBAR_FALLBACK"); null/blank maps to {@link #INSTANT}
     * @return the matching policy, or {@link #INSTANT} when unrecognized/null
     */
    public static DeliveryPolicy fromDbValue(String value) {
        if (value == null || value.isBlank()) {
            return INSTANT;
        }
        try {
            return DeliveryPolicy.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return INSTANT;
        }
    }

    /** External contract value. */
    public String dbValue() {
        return name();
    }
}
