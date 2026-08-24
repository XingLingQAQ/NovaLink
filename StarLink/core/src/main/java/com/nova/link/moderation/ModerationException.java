package com.nova.link.moderation;

/**
 * Unchecked exception raised by {@link ModerationManager} when a moderation
 * operation cannot proceed. Carries a stable error code that the REST layer
 * maps to an HTTP status, mirroring the {@code NC-###} convention already used
 * by {@code MuteResult}/{@code BanResult}:
 * <ul>
 *   <li>{@code NC-400} — validation failure (bad request).</li>
 *   <li>{@code NC-403} — forbidden; notably the appeal-reviewer-must-differ-
 *       from-case-moderator rule (PANEL-007 hard 403, not a silent fallback).</li>
 *   <li>{@code NC-404} — case or appeal not found.</li>
 *   <li>{@code NC-500} — persistence failure.</li>
 * </ul>
 *
 * <p>The exception is unchecked so it can be thrown from the deep validation
 * path without polluting signatures; the REST handler catches it at the route
 * boundary and converts it to a JSON error + audit record.
 *
 * <p>Requirements: PANEL-007 moderation case/appeal workflow
 */
public class ModerationException extends RuntimeException {

    private final String errorCode;

    /**
     * @param errorCode the stable {@code NC-###} code (never null/blank)
     * @param message   the human-readable detail message
     */
    public ModerationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * @return the stable {@code NC-###} error code for HTTP-status mapping
     */
    public String getErrorCode() {
        return errorCode;
    }
}
