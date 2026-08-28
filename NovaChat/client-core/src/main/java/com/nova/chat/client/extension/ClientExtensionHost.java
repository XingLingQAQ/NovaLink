package com.nova.chat.client.extension;

import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.common.extension.ExtensionEventBus;
import com.nova.chat.common.extension.ExtensionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Best-effort host that wires the shared {@link ExtensionManager} from
 * {@code NovaChat:common} into the client-core startup path.
 *
 * <p>The host is intentionally tolerant: a missing/empty extensions directory,
 * a loader failure, or an enable failure is logged and swallowed so the
 * network client keeps running with no extensions loaded. This preserves the
 * pre-extension baseline behavior when no extension JARs are deployed
 * (zero-regression guarantee).
 *
 * <p>The host does <strong>not</strong> create the extensions directory when it
 * is absent — that keeps the working tree side-effect-free for deployments that
 * do not ship extensions. When the directory exists but is empty, the loader
 * returns an empty list and no extensions are enabled.
 */
public final class ClientExtensionHost {

    /** Default extensions directory name, resolved against the working directory. */
    public static final String DEFAULT_EXTENSIONS_DIR = "extensions";

    private final ExtensionManager extensionManager;
    private final ClientLogger logger;
    private final boolean enabled;

    private ClientExtensionHost(ExtensionManager extensionManager,
                               ClientLogger logger,
                               boolean enabled) {
        this.extensionManager = extensionManager;
        this.logger = logger;
        this.enabled = enabled;
    }

    /**
     * Initializes the extension host: creates the manager, scans the extensions
     * directory, loads JARs, and enables all loaded extensions. Any failure
     * (including the directory not existing) is logged and swallowed, yielding
     * an empty but usable host.
     *
     * @param extensionsDir directory to scan for {@code *.jar}; null/blank resolves
     *                      to {@link #DEFAULT_EXTENSIONS_DIR} relative to the cwd
     * @param logger        platform logger; null → a no-op logger
     * @return a non-null host (never throws)
     */
    public static ClientExtensionHost initialize(String extensionsDir, ClientLogger logger) {
        ClientLogger safeLogger = logger != null ? logger : new NoOpLogger();
        Path dir = resolveExtensionsDir(extensionsDir);
        ExtensionManager manager = new ExtensionManager();

        // Zero-regression: if the extensions directory is absent, do NOT create
        // it (DefaultExtensionLoader would) and do NOT attempt to load. This keeps
        // the host side-effect-free when no extensions are deployed.
        if (!Files.isDirectory(dir)) {
            safeLogger.debug("Extension host: extensions directory absent ("
                    + dir + "); running without extensions");
            return new ClientExtensionHost(manager, safeLogger, false);
        }

        boolean enabled = false;
        try {
            int loaded = manager.loadExtensions(dir);
            if (loaded > 0) {
                int enabledCount = manager.enableAllExtensions();
                safeLogger.info("Extension host loaded " + loaded
                        + " extension(s); enabled " + enabledCount);
                enabled = enabledCount > 0;
            } else {
                safeLogger.debug("Extension host: no extensions found in " + dir);
            }
        } catch (Throwable t) {
            safeLogger.error("Extension host initialization failed; continuing without extensions", t);
        }
        return new ClientExtensionHost(manager, safeLogger, enabled);
    }

    private static Path resolveExtensionsDir(String extensionsDir) {
        String dir = extensionsDir == null || extensionsDir.isBlank()
                ? DEFAULT_EXTENSIONS_DIR
                : extensionsDir;
        return Paths.get(dir);
    }

    /** @return the underlying extension manager (never null) */
    public ExtensionManager getExtensionManager() {
        return extensionManager;
    }

    /** @return the extension event bus (never null) */
    public ExtensionEventBus getEventBus() {
        return extensionManager.getEventBus();
    }

    /** @return true when at least one extension was successfully enabled */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Disables all enabled extensions and releases resources. Safe to call
     * multiple times; safe to call when initialization failed (no-op).
     */
    public void shutdown() {
        try {
            extensionManager.disableAllExtensions();
        } catch (Throwable t) {
            logger.error("Extension host shutdown failed", t);
        }
    }

    private static final class NoOpLogger implements ClientLogger {
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void debug(String message) { }
        @Override public void error(String message) { }
    }
}
