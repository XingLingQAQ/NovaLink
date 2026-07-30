package com.nova.chat.client.format;

import java.util.Map;

/**
 * Platform-agnostic message formatting helpers shared across the 8 NovaChat
 * platform {@code MessageFormatter} implementations.
 *
 * <p>This service owns the pure-string half of message formatting: hex color
 * expansion (delegated to {@link LegacyColorCodes}), color-code stripping, and
 * the template-string assembly for system/error/success messages. It never
 * touches Adventure / Bungee / Bukkit / Nukkit color APIs — the resulting
 * strings still carry raw {@code &}/{@code §} codes and are handed to a
 * platform-supplied {@link ColorRenderer} for the final platform-object
 * conversion.
 *
 * <p>Two hex expansion modes are offered because platform color APIs disagree
 * on the introducer character:
 * <ul>
 *   <li>{@link #convertHexToSection(String)} — {@code &#RRGGBB} → {@code §x§r…}
 *       for Bungee / Sponge / Bukkit / Folia / MultiPaper (section-sign input)</li>
 *   <li>{@link #convertHexToAmpersand(String)} — {@code &#RRGGBB} → {@code &x&r…}
 *       for Velocity (Adventure serializer configured with {@code &})</li>
 * </ul>
 * Nukkit / PNX use a Bedrock nearest-color approximation and therefore do not
 * call the hex expansion path; they keep their own approximation logic.
 *
 * <p>Stateless; all methods are null-safe.
 */
public final class MessageFormatService {

    private MessageFormatService() {
    }

    /**
     * Converts {@code &#RRGGBB} hex sequences to section-sign form {@code §x§r§r§g§g§b§b}.
     *
     * @param text input text; null → null
     * @return converted text, or null if input was null
     */
    public static String convertHexToSection(String text) {
        return LegacyColorCodes.toSectionX(text);
    }

    /**
     * Converts {@code &#RRGGBB} hex sequences to ampersand form {@code &x&r&r&g&g&b&b}.
     *
     * @param text input text; null → null
     * @return converted text, or null if input was null
     */
    public static String convertHexToAmpersand(String text) {
        return LegacyColorCodes.toAmpersandX(text);
    }

    /**
     * Strips hash-hex, ampersand-x, section-x, and simple {@code &}/{@code §} color codes.
     *
     * @param text input text; null → null
     * @return text without color markup, or null if input was null
     */
    public static String stripColors(String text) {
        return LegacyColorCodes.strip(text);
    }

    /**
     * Assembles a system message: {@code prefix + message} (no color translation).
     *
     * @param prefix  the configured prefix (may carry color codes); null → ""
     * @param message the message body; null → ""
     * @return the assembled string, never null
     */
    public static String buildSystemMessage(String prefix, String message) {
        return nullToEmpty(prefix) + nullToEmpty(message);
    }

    /**
     * Assembles an error message: {@code prefix + errorFormat} with {@code {message}}
     * replaced by the body, using the shared {@link FormatTemplateEngine}.
     *
     * @param prefix      the configured prefix; null → ""
     * @param errorFormat the error format template containing {@code {message}}; null → ""
     * @param message     the error body; null → ""
     * @return the assembled string, never null
     */
    public static String buildError(String prefix, String errorFormat, String message) {
        return nullToEmpty(prefix)
                + FormatTemplateEngine.apply(errorFormat,
                        Map.of(FormatTemplateEngine.KEY_MESSAGE, nullToEmpty(message)));
    }

    /**
     * Assembles a success message: {@code prefix + successFormat} with {@code {message}}
     * replaced by the body, using the shared {@link FormatTemplateEngine}.
     *
     * @param prefix        the configured prefix; null → ""
     * @param successFormat the success format template containing {@code {message}}; null → ""
     * @param message       the success body; null → ""
     * @return the assembled string, never null
     */
    public static String buildSuccess(String prefix, String successFormat, String message) {
        return nullToEmpty(prefix)
                + FormatTemplateEngine.apply(successFormat,
                        Map.of(FormatTemplateEngine.KEY_MESSAGE, nullToEmpty(message)));
    }

    /**
     * Assembles a typed system message (error/success/custom) using the shared
     * {@link FormatTemplateEngine}.
     *
     * <p>{@code type} is matched case-insensitively: {@code "error"} →
     * {@code errorFormat}, {@code "success"} → {@code successFormat}, anything
     * else → literal {@code "{message}"}. This mirrors the existing
     * {@code formatSystemMessage(type, message)} behavior on bukkit/nukkit/sponge/folia/multipaper.
     *
     * @param prefix        the configured prefix; null → ""
     * @param errorFormat   the error format template; null → ""
     * @param successFormat the success format template; null → ""
     * @param type          the message type; null → treated as custom
     * @param message       the message body; null → ""
     * @return the assembled string, never null
     */
    public static String buildTypedSystem(String prefix, String errorFormat,
                                          String successFormat, String type, String message) {
        String format;
        if (type == null) {
            format = "{message}";
        } else {
            switch (type.toLowerCase()) {
                case "error":
                    format = errorFormat;
                    break;
                case "success":
                    format = successFormat;
                    break;
                default:
                    format = "{message}";
            }
        }
        return nullToEmpty(prefix)
                + FormatTemplateEngine.apply(format,
                        Map.of(FormatTemplateEngine.KEY_MESSAGE, nullToEmpty(message)));
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
