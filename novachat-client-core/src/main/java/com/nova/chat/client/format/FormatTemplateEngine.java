package com.nova.chat.client.format;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure string format template engine for NovaChat message formats.
 *
 * <p>Replaces curly-brace placeholders of the form {@code {key}} in a template.
 * Well-known keys used across platforms:
 * <ul>
 *   <li>{@code {player}} – sender name</li>
 *   <li>{@code {channel}} – channel id</li>
 *   <li>{@code {channel_name}} – channel display name</li>
 *   <li>{@code {message}} – raw message content</li>
 * </ul>
 *
 * <p>Additional keys may be supplied via a map (e.g. {@code display_name}, {@code world},
 * {@code server}). Map values win over the dedicated well-known parameters when the same
 * key is provided in both places (map is applied last).
 *
 * <h2>Missing-key policy</h2>
 * <p><strong>Unreplaced.</strong> Placeholders whose keys are absent from the supplied
 * values are left as literal text (e.g. {@code {unknown}} stays {@code {unknown}}).
 * This never throws and makes misconfiguration visible in output.
 *
 * <h2>Null-safety</h2>
 * <ul>
 *   <li>{@code null} template → {@code ""}</li>
 *   <li>{@code null} placeholder values → replaced with {@code ""}</li>
 *   <li>{@code null} map → treated as empty</li>
 *   <li>{@code null} map keys are skipped; {@code null} map values become {@code ""}</li>
 * </ul>
 *
 * <p>This class has <strong>no</strong> dependency on Minecraft, Adventure, or platform APIs.
 * Color-code translation is out of scope; see {@link LegacyColorCodes} for pure hex transforms.
 */
public final class FormatTemplateEngine {

    /** Well-known placeholder key: message sender name. */
    public static final String KEY_PLAYER = "player";

    /** Well-known placeholder key: channel id. */
    public static final String KEY_CHANNEL = "channel";

    /** Well-known placeholder key: channel display name. */
    public static final String KEY_CHANNEL_NAME = "channel_name";

    /** Well-known placeholder key: message body. */
    public static final String KEY_MESSAGE = "message";

    /**
     * Well-known placeholder key: channel color.
     *
     * <p>Resolved deterministically from the {@code {channel}} value via
     * {@link ChannelColorResolver#resolveColor(String)} when the template contains
     * {@code {channel_color}} and no explicit override is supplied. See
     * {@link #apply(String, Map)} for the auto-resolution rule.
     */
    public static final String KEY_CHANNEL_COLOR = "channel_color";

    private FormatTemplateEngine() {
    }

    /**
     * Applies only the placeholders present in {@code placeholders}.
     * Missing keys are left unreplaced.
     *
     * @param template     format string, may be null
     * @param placeholders key → value map (keys without braces); may be null
     * @return formatted string (never null)
     */
    public static String apply(String template, Map<String, String> placeholders) {
        if (template == null) {
            return "";
        }
        boolean needsChannelColor = template.contains("{channel_color}");
        if (!needsChannelColor && (placeholders == null || placeholders.isEmpty())) {
            return template;
        }
        Map<String, String> values = placeholders != null ? placeholders : new LinkedHashMap<>();
        // Auto-resolve {channel_color} from the {channel} value when the template
        // references it and the caller didn't supply an explicit channel_color.
        if (needsChannelColor && !values.containsKey(KEY_CHANNEL_COLOR)) {
            String channel = values.get(KEY_CHANNEL);
            String color = ChannelColorResolver.resolveColor(channel);
            Map<String, String> copy = new LinkedHashMap<>(values);
            copy.put(KEY_CHANNEL_COLOR, color);
            values = copy;
        }
        if (values.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty()) {
                continue;
            }
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace("{" + key + "}", value);
        }
        return result;
    }

    /**
     * Applies the four well-known chat placeholders.
     * Any {@code null} value is treated as empty string. Missing keys do not apply.
     *
     * @param template    format string, may be null
     * @param player      value for {@code {player}}
     * @param channel     value for {@code {channel}}
     * @param channelName value for {@code {channel_name}}
     * @param message     value for {@code {message}}
     * @return formatted string (never null)
     */
    public static String apply(String template,
                               String player,
                               String channel,
                               String channelName,
                               String message) {
        return apply(template, player, channel, channelName, message, null);
    }

    /**
     * Applies well-known chat placeholders, then any extra map entries.
     *
     * <p>Order: well-known keys first, then map extras (map overrides well-known on key clash).
     * Missing keys remain unreplaced. Null values become empty strings.
     *
     * @param template     format string, may be null
     * @param player       value for {@code {player}}
     * @param channel      value for {@code {channel}}
     * @param channelName  value for {@code {channel_name}}
     * @param message      value for {@code {message}}
     * @param extras       additional placeholders; may be null
     * @return formatted string (never null)
     */
    public static String apply(String template,
                               String player,
                               String channel,
                               String channelName,
                               String message,
                               Map<String, String> extras) {
        Map<String, String> values = new LinkedHashMap<>(8);
        values.put(KEY_PLAYER, nullToEmpty(player));
        values.put(KEY_CHANNEL, nullToEmpty(channel));
        values.put(KEY_CHANNEL_NAME, nullToEmpty(channelName));
        values.put(KEY_MESSAGE, nullToEmpty(message));
        if (extras != null) {
            for (Map.Entry<String, String> entry : extras.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isEmpty()) {
                    continue;
                }
                values.put(entry.getKey(), nullToEmpty(entry.getValue()));
            }
        }
        return apply(template, values);
    }

    /**
     * Builds an unmodifiable map of the four well-known placeholders.
     * Null values become empty strings.
     *
     * @param player      {@code {player}}
     * @param channel     {@code {channel}}
     * @param channelName {@code {channel_name}}
     * @param message     {@code {message}}
     * @return unmodifiable map
     */
    public static Map<String, String> standardPlaceholders(String player,
                                                           String channel,
                                                           String channelName,
                                                           String message) {
        Map<String, String> map = new LinkedHashMap<>(4);
        map.put(KEY_PLAYER, nullToEmpty(player));
        map.put(KEY_CHANNEL, nullToEmpty(channel));
        map.put(KEY_CHANNEL_NAME, nullToEmpty(channelName));
        map.put(KEY_MESSAGE, nullToEmpty(message));
        return Collections.unmodifiableMap(map);
    }

    /**
     * Returns whether {@code template} contains the placeholder {@code {key}}.
     *
     * @param template format string; null → false
     * @param key      placeholder key without braces; null/blank → false
     * @return true if the placeholder token is present
     */
    public static boolean containsPlaceholder(String template, String key) {
        if (template == null || key == null || key.isEmpty()) {
            return false;
        }
        return template.contains("{" + key + "}");
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
