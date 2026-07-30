package com.nova.chat.client.command;

/**
 * Shared, platform-agnostic first-join welcome message (UX-DESIGN §8.1).
 *
 * <p>Each platform listens for its player-join event, decides whether the
 * player is first-timing, and — if so — pushes a single, non-intrusive
 * system message (action bar / chat, never a title) pointing them at
 * {@code /nc help} and {@code /nc list}. Keeping the copy here keeps every
 * platform plugin in sync instead of drifting wording.
 *
 * <p>The returned strings are plain text with {@code &}-style color codes
 * suitable for the legacy-format platforms; platforms that use component
 * text (velocity / sponge) strip or translate them as needed.
 */
public final class WelcomeMessageService {

    /**
     * Welcome line shown once to a first-time player.
     *
     * <p>Deliberately a single line (no title, no multi-line spam) so it
     * does not disturb the join experience.
     */
    public static final String WELCOME_LINE =
            "&6欢迎！&r输入 &e/nc help &r查看聊天频道，&e/nc list &r列出可用频道";

    private WelcomeMessageService() {
        // Utility class — no instances.
    }

    /**
     * Returns the first-join welcome line.
     *
     * @return the welcome message with {@code &}-style color codes
     */
    public static String getWelcomeLine() {
        return WELCOME_LINE;
    }
}
