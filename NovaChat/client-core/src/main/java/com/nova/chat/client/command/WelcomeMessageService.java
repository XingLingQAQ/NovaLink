package com.nova.chat.client.command;

import com.nova.chat.client.i18n.I18n;

/**
 * Shared, platform-agnostic first-join welcome message (UX-DESIGN §8.1).
 *
 * <p>Each platform listens for its player-join event, decides whether the
 * player is first-timing, and — if so — pushes a single, non-intrusive
 * system message (action bar / chat, never a title) pointing them at
 * {@code /nc help} and {@code /nc list}. Keeping the copy here keeps every
 * platform plugin in sync instead of drifting wording.
 *
 * <p>The welcome line is resolved through {@link I18n} (key
 * {@code chat.welcome.line}) so it follows the configured default locale.
 * The returned strings are plain text with {@code &}-style color codes
 * suitable for the legacy-format platforms; platforms that use component
 * text (velocity / sponge) strip or translate them as needed.
 */
public final class WelcomeMessageService {

    private WelcomeMessageService() {
        // Utility class — no instances.
    }

    /**
     * Returns the first-join welcome line, resolved through {@link I18n}
     * in the current default locale.
     *
     * @return the welcome message with {@code &}-style color codes
     */
    public static String getWelcomeLine() {
        return I18n.tr("chat.welcome.line");
    }
}
