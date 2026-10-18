package com.nova.link.announcement;

/**
 * Lifecycle status of a campaign (§11.6 提案 06).
 *
 * <p>State machine:
 * <pre>
 *   PREVIEW → SCHEDULED → ACTIVE → { EXPIRED, REVOKED }
 *                       ↘
 *                        ACTIVE → { EXPIRED, REVOKED }
 *   (any non-terminal) → REVOKED
 * </pre>
 * {@code EXPIRED} and {@code REVOKED} are terminal: no further transition is
 * allowed. A campaign may be revoked from any non-terminal state (PREVIEW,
 * SCHEDULED, ACTIVE).
 */
public enum CampaignStatus {
    /** Draft, not yet scheduled. May be edited. */
    PREVIEW,
    /** Scheduled to activate at {@code startAt}; not yet delivering. */
    SCHEDULED,
    /** Actively delivering (or eligible to deliver) until endAt/revoke. */
    ACTIVE,
    /** Reached {@code endAt} naturally; terminal. */
    EXPIRED,
    /** Manually revoked; terminal. */
    REVOKED;

    /**
     * Validates whether a transition from {@code from} to {@code to} is legal
     * under the campaign state machine.
     *
     * <p>Rules:
     * <ul>
     *   <li>Terminal states (EXPIRED, REVOKED) cannot transition anywhere.</li>
     *   <li>PREVIEW → SCHEDULED is legal (deferred activation).</li>
     *   <li>PREVIEW → ACTIVE is legal (immediate activation, startAt=0).</li>
     *   <li>SCHEDULED → ACTIVE is legal.</li>
     *   <li>ACTIVE → EXPIRED is legal (natural expiry).</li>
     *   <li>Any non-terminal → REVOKED is legal (manual revoke).</li>
     *   <li>Everything else is illegal.</li>
     * </ul>
     *
     * @param from the current status
     * @param to   the desired status
     * @return true if the transition is legal
     */
    public static boolean isValidTransition(CampaignStatus from, CampaignStatus to) {
        if (from == null || to == null) {
            return false;
        }
        // Terminal states cannot transition anywhere.
        if (from == EXPIRED || from == REVOKED) {
            return false;
        }
        // Any non-terminal may be revoked.
        if (to == REVOKED) {
            return true;
        }
        switch (from) {
            case PREVIEW:
                return to == SCHEDULED || to == ACTIVE;
            case SCHEDULED:
                return to == ACTIVE;
            case ACTIVE:
                return to == EXPIRED;
            default:
                return false;
        }
    }
}
