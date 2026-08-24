package com.nova.link.channel;

/**
 * Provenance tag describing how a {@link Channel} entered the live channel registry.
 *
 * <p>The source governs reload semantics: config reload must not overwrite
 * dynamic channels, and channels declared in config cannot be edited through
 * the Panel. The tag is a runtime concept — it is not persisted directly, it is
 * recomputed at load time based on the loading path (config loader, persistence
 * loader, or runtime creation).</p>
 */
public enum ChannelSource {
    /** Declared in the configuration file and loaded by the config loader. */
    CONFIG,
    /** Restored from the persistence store at startup. */
    DATABASE,
    /** Created at runtime through the REST API, console, or a plugin loader. */
    RUNTIME
}
