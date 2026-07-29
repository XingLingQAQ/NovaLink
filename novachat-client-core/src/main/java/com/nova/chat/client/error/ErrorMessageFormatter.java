package com.nova.chat.client.error;

/**
 * Formats {@link ErrorCode} values into player-facing multi-line messages.
 *
 * <p>Output shape (two lines, aligned with the historical bukkit
 * {@code ErrorMessageHandler}):
 * <pre>
 *   [NC-434] 密码错误
 *   提示: 请检查频道密码是否正确
 * </pre>
 *
 * <p>Pure functions, no platform dependencies — safe to call from any thread.
 * Platforms supply their own colorization / send wrapper; this class only owns
 * the shared text contract.
 *
 * <p>Architecture B: plugin-only.
 */
public final class ErrorMessageFormatter {

    /** Prefix used for the suggestion line. */
    public static final String SUGGESTION_PREFIX = "提示: ";

    private ErrorMessageFormatter() {
    }

    /**
     * Formats a known {@link ErrorCode} into a two-line player message.
     *
     * @param code the error code (not null)
     * @return the formatted message, never null
     */
    public static String format(ErrorCode code) {
        return format(code.getCode(), code.getMessage(), code.getSuggestion());
    }

    /**
     * Formats a raw code string into a two-line player message, resolving the
     * message/suggestion via {@link ErrorCode#fromCode(String)}.
     *
     * <p>Unknown codes resolve to NC-500 text (never throws).
     *
     * @param code the code string (e.g., "NC-434"); null treated as NC-500
     * @return the formatted message, never null
     */
    public static String format(String code) {
        ErrorCode resolved = ErrorCode.fromCode(code);
        return format(resolved);
    }

    /**
     * Formats an error code with a custom message override (e.g. when the
     * backend supplies extra context like a channel name). The suggestion is
     * still pulled from the shared {@link ErrorCode}.
     *
     * @param code            the error code (not null)
     * @param messageOverride custom message; if null/blank, falls back to the code's message
     * @return the formatted message, never null
     */
    public static String format(ErrorCode code, String messageOverride) {
        String msg = (messageOverride == null || messageOverride.isBlank())
                ? code.getMessage() : messageOverride;
        return format(code.getCode(), msg, code.getSuggestion());
    }

    /**
     * Builds the canonical two-line form from raw parts.
     */
    private static String format(String code, String message, String suggestion) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(code).append("] ").append(message);
        if (suggestion != null && !suggestion.isBlank()) {
            sb.append('\n').append(SUGGESTION_PREFIX).append(suggestion);
        }
        return sb.toString();
    }
}
