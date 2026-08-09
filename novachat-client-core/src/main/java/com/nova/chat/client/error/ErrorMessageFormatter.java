package com.nova.chat.client.error;

import com.nova.chat.client.i18n.I18n;

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
 * <p>All natural-language text (message, suggestion, and the suggestion
 * prefix) is resolved through {@link I18n} so the output follows the
 * configured default locale. The bracketed {@code [NC-XXX]} code is
 * locale-independent. Color codes are not added here; platforms colorize.
 *
 * <p>Pure functions, no platform dependencies — safe to call from any thread.
 * Platforms supply their own colorization / send wrapper; this class only owns
 * the shared text contract.
 *
 * <p>Architecture B: plugin-only.
 */
public final class ErrorMessageFormatter {

    /**
     * Prefix used for the suggestion line, resolved from the default locale
     * at class-load (key {@code error.suggestion_prefix}). Kept as a
     * {@code public static final String} for backward compatibility; the
     * {@link #format(ErrorCode)} path re-resolves it via {@link I18n} so a
     * locale change after startup is honored.
     */
    public static final String SUGGESTION_PREFIX = I18n.tr("error.suggestion_prefix");

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
     * Builds the canonical two-line form from raw parts. The suggestion
     * prefix is resolved through {@link I18n} so it follows the current
     * default locale.
     */
    private static String format(String code, String message, String suggestion) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(code).append("] ").append(message);
        if (suggestion != null && !suggestion.isBlank()) {
            sb.append('\n').append(I18n.tr("error.suggestion_prefix")).append(' ').append(suggestion);
        }
        return sb.toString();
    }
}
