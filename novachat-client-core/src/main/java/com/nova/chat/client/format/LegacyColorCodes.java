package com.nova.chat.client.format;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure string transforms for Minecraft-style color codes.
 *
 * <p>No Adventure, no platform APIs. Intended as a shared intermediate step that
 * platform formatters can call before handing off to Adventure / ChatColor / TextFormat.
 *
 * <h2>Supported inputs</h2>
 * <ul>
 *   <li>{@code &#RRGGBB} – common hex form used in NovaChat configs</li>
 *   <li>{@code &x&R&R&G&G&B&B} – already-expanded intermediate form (left as-is by
 *       {@link #toAmpersandX(String)}; recognized by strip helpers)</li>
 * </ul>
 *
 * <h2>What this does <em>not</em> do</h2>
 * <ul>
 *   <li>Does not translate {@code &a}/{@code &l} legacy codes to section-sign (§)</li>
 *   <li>Does not produce Adventure {@code Component}s</li>
 *   <li>Does not approximate hex for Bedrock palettes</li>
 * </ul>
 */
public final class LegacyColorCodes {

    /** Matches {@code &#RRGGBB} (case-insensitive hex digits). */
    public static final Pattern HASH_HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    /**
     * Matches expanded ampersand-x form {@code &x&R&R&G&G&B&B}
     * (case-insensitive hex digits).
     */
    public static final Pattern AMPERSAND_X_PATTERN =
            Pattern.compile("&[xX](&[A-Fa-f0-9]){6}");

    /**
     * Matches expanded section-sign-x form {@code §x§R§R§G§G§B§B}
     * (case-insensitive hex digits).
     */
    public static final Pattern SECTION_X_PATTERN =
            Pattern.compile("§[xX](§[A-Fa-f0-9]){6}");

    /** Matches simple legacy ampersand codes {@code &0}-{@code &f}, formats, reset. */
    public static final Pattern AMPERSAND_LEGACY_PATTERN =
            Pattern.compile("&[0-9a-fk-orA-FK-OR]");

    /** Matches simple section-sign legacy codes. */
    public static final Pattern SECTION_LEGACY_PATTERN =
            Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    private LegacyColorCodes() {
    }

    /**
     * Converts {@code &#RRGGBB} sequences to intermediate {@code &x&R&R&G&G&B&B} form.
     * Digits are lowercased. Already-expanded {@code &x&…} sequences are left unchanged.
     *
     * @param text input text; null → null
     * @return converted text, or null if input was null
     */
    public static String toAmpersandX(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = HASH_HEX_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder(text.length() + 16);
        while (matcher.find()) {
            String hex = matcher.group(1).toLowerCase();
            StringBuilder replacement = new StringBuilder(14).append("&x");
            for (int i = 0; i < hex.length(); i++) {
                replacement.append('&').append(hex.charAt(i));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Converts {@code &#RRGGBB} sequences to section-sign form {@code §x§R§R§G§G§B§B}.
     * Digits are lowercased. Useful for platforms that expect §-prefixed hex.
     *
     * @param text input text; null → null
     * @return converted text, or null if input was null
     */
    public static String toSectionX(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = HASH_HEX_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder(text.length() + 16);
        while (matcher.find()) {
            String hex = matcher.group(1).toLowerCase();
            StringBuilder replacement = new StringBuilder(14).append("§x");
            for (int i = 0; i < hex.length(); i++) {
                replacement.append('§').append(hex.charAt(i));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Converts intermediate {@code &x&R&R&G&G&B&B} (and simple {@code &X}) to section-sign
     * form by replacing every ampersand color introducer with {@code §}.
     *
     * <p>Also runs {@link #toAmpersandX(String)} first so {@code &#RRGGBB} is expanded.
     * Non-color ampersands that are not followed by a valid code char are left alone
     * (this method only rewrites known color sequences).
     *
     * @param text input text; null → null
     * @return text with color sequences using {@code §}
     */
    public static String ampersandToSection(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String expanded = toAmpersandX(text);
        // Rewrite &x&R… hex runs
        Matcher hexMatcher = AMPERSAND_X_PATTERN.matcher(expanded);
        StringBuilder buf = new StringBuilder(expanded.length());
        while (hexMatcher.find()) {
            String run = hexMatcher.group();
            hexMatcher.appendReplacement(buf, Matcher.quoteReplacement(run.replace('&', '§')));
        }
        hexMatcher.appendTail(buf);
        // Rewrite simple &X codes
        Matcher legacy = AMPERSAND_LEGACY_PATTERN.matcher(buf.toString());
        StringBuilder out = new StringBuilder(buf.length());
        while (legacy.find()) {
            String run = legacy.group();
            legacy.appendReplacement(out, Matcher.quoteReplacement("§" + run.substring(1)));
        }
        legacy.appendTail(out);
        return out.toString();
    }

    /**
     * Strips hash-hex, ampersand-x, section-x, and simple {@code &}/{@code §} color codes.
     * Does not throw; null → null.
     *
     * @param text input text
     * @return text without color markup
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = HASH_HEX_PATTERN.matcher(text).replaceAll("");
        result = AMPERSAND_X_PATTERN.matcher(result).replaceAll("");
        result = SECTION_X_PATTERN.matcher(result).replaceAll("");
        result = AMPERSAND_LEGACY_PATTERN.matcher(result).replaceAll("");
        result = SECTION_LEGACY_PATTERN.matcher(result).replaceAll("");
        return result;
    }
}
