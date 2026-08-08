package com.nova.chat.client.command;

/**
 * Shared message prefix literals used by the platform {@code MessageHelper}
 * implementations (bukkit / folia / nukkit). Centralizing these
 * keeps the platform copies in sync without a cross-module reference.
 */
public final class MessagePrefixes {

    /** Standard message prefix. */
    public static final String PREFIX = "&8[&bNovaChat&8]&r ";

    /** Error message prefix. */
    public static final String ERROR_PREFIX = "&8[&cNovaChat&8]&r ";

    /** Success message prefix. */
    public static final String SUCCESS_PREFIX = "&8[&aNovaChat&8]&r ";

    private MessagePrefixes() {
    }
}
