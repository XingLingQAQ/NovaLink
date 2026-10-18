package com.nova.chat.proxy.config;

import java.util.Locale;

/**
 * Operating mode of a NovaChat proxy plugin.
 *
 * <p>{@link #RELAY} (the default) connects to a NovaLink backend over the
 * NovaProtocol network link; {@link #LITE} keeps the proxy self-contained and
 * never opens that link. The mode is selected by the root {@code mode} key of
 * the platform configuration ({@code config.toml} / {@code config.yml}) and is
 * matched case-insensitively.
 *
 * <p>Lite mode is currently a configuration and dispatch concern only: the
 * backend-dependent features (chat fan-out, announcements, mutes, ...) are not
 * implemented for it yet.
 */
public enum ProxyMode {

    /** Connect to an external NovaLink backend (historical behaviour). */
    RELAY,

    /** Run without an external backend. */
    LITE;

    /**
     * Parses a configured mode value.
     *
     * <p>A missing value means {@link #RELAY}: older configuration files are
     * upgraded with an explicit {@code mode = "relay"}, and a document that
     * predates the key must keep connecting to its backend rather than silently
     * switching to lite.
     *
     * @param raw the configured value, may be null
     * @return the parsed mode, never null
     * @throws ConfigSource.ConfigException when the value is present but not a
     *         recognised mode
     */
    public static ProxyMode parse(String raw) throws ConfigSource.ConfigException {
        if (raw == null) {
            return RELAY;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new ConfigSource.ConfigException(
                    "Configuration value mode must not be blank (allowed: lite, relay)");
        }
        return switch (normalized) {
            case "relay" -> RELAY;
            case "lite" -> LITE;
            default -> throw new ConfigSource.ConfigException(
                    "Configuration value mode is unsupported: " + raw
                            + " (allowed: lite, relay)");
        };
    }
}
