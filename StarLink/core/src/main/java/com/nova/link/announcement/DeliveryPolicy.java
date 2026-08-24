package com.nova.link.announcement;

/**
 * Delivery policy for a campaign, controlling how the campaign content is
 * surfaced on target platforms that lack rich rendering capabilities.
 *
 * <p>§11.6 提案 06: platform degradation — platforms that do not support
 * title/actionbar fall back to a channel message; platforms without the
 * target range only allow public channels and show degradation in preview.
 *
 * <ul>
 *   <li>{@link #INSTANT} — deliver as a normal channel message immediately.</li>
 *   <li>{@link #TITLE_FALLBACK} — prefer title display; fall back to channel
 *       message when the target platform has no title support.</li>
 *   <li>{@link #ACTIONBAR_FALLBACK} — prefer action-bar display; fall back to
 *       channel message when the target platform has no action-bar support.</li>
 * </ul>
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
