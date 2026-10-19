package com.nova.chat.proxy.lite;

import com.nova.chat.proxy.config.LiteChannelConfig;
import com.nova.chat.proxy.config.LiteStorageConfig;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.ChannelSource;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.MySQLProvider;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.database.PostgreSQLProvider;
import com.nova.link.database.SQLiteProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Persistence for lite-mode channel membership.
 *
 * <p>Lite mode is authoritative in-process: {@link LocalAuthority} owns the
 * channel member table and the per-player joined/active channel state. Before
 * this class existed that state lived only in memory, so a proxy restart lost
 * every player's channel membership. This store mirrors the membership half of
 * that state into one of the four {@code lite.storage} backends through the
 * shared {@code :StarLink:storage} module ({@link DatabaseProvider} +
 * {@link PlayerStateManager}) — no SQL is written here.
 *
 * <p><b>What is persisted:</b> per player, the joined channel ids and the active
 * channel ({@link PlayerState#getJoinedChannels()} /
 * {@link PlayerState#getActiveChannel()}); plus, once at startup, the channel
 * definitions declared under {@code lite.channels} so the database row set
 * reflects the live roster. Mutes, kicks, chat history and private messages are
 * out of scope for this stage.
 *
 * <p><b>{@code memory} is a true no-op.</b> When {@code lite.storage.type} is
 * {@code memory} the store keeps no provider, creates no executor and performs
 * no I/O at all: every method returns immediately, so lite-with-memory behaves
 * exactly as it did before persistence existed (no threads, no file handles, no
 * connection attempts). The in-process maps remain the only copy.
 *
 * <p><b>Threading.</b> All database work runs on one private daemon thread. The
 * proxy event thread and the {@link LocalAuthorityClient} dispatch thread only
 * ever capture an immutable {@link PlayerSnapshot} and enqueue it, so a slow or
 * unreachable database can never stall chat, a JOIN, a LEAVE or a disconnect.
 * Because the queue is serial, submissions apply in call order: the backend is
 * opened by the first task, so writes enqueued while it is still connecting run
 * strictly after the open completes.
 *
 * <p><b>Failure policy.</b> Nothing here throws into the caller. A backend that
 * cannot be opened, or a write that fails, degrades to memory-only: the first
 * failure is reported once through the warn sink (with the underlying cause),
 * later ones at debug level, and chat keeps working. {@link #isPersistent()}
 * reports the degraded state so callers can skip pointless work.
 */
public final class LiteChannelStore implements AutoCloseable {

    /** Upper bound on draining queued writes; shutdown also uses it. */
    static final long DEFAULT_FLUSH_TIMEOUT_MILLIS = 15_000L;

    /** Thread name of the private I/O thread; also used by tests to prove it exists. */
    static final String IO_THREAD_NAME = "novachat-lite-storage";

    /**
     * Immutable snapshot of the membership state to persist.
     *
     * <p>A carrier rather than a live {@link PlayerState}: the snapshot is taken
     * under the authority's lock and consumed later on the I/O thread, so it must
     * not share mutable state with the running proxy.
     *
     * @param playerId the player UUID
     * @param playerName the account name, or null when unknown
     * @param joinedChannels the joined channel ids, never null
     * @param activeChannel the active channel id, or null when none
     */
    public record PlayerSnapshot(UUID playerId,
                                 String playerName,
                                 Set<String> joinedChannels,
                                 String activeChannel) {

        /**
         * @param playerId the player UUID
         * @param playerName the account name, or null
         * @param joinedChannels the joined channel ids (copied; null means none)
         * @param activeChannel the active channel id, or null
         */
        public PlayerSnapshot {
            joinedChannels = joinedChannels != null
                    ? Collections.unmodifiableSet(new LinkedHashSet<>(joinedChannels))
                    : Set.of();
        }

        /**
         * @return whether the snapshot carries no membership at all
         */
        public boolean isEmpty() {
            return joinedChannels.isEmpty() && activeChannel == null;
        }
    }

    private final LiteStorageConfig.Type type;
    private final boolean persistent;
    private final Path dataDirectory;
    private final Map<String, LiteChannelConfig> declaredChannels;
    private final String platformName;
    private final Consumer<String> warn;
    private final Consumer<String> debug;
    private final LiteStorageConfig storage;
    private final Set<String> persistedChannelIds = Collections.synchronizedSet(new LinkedHashSet<>());

    /** Null for {@code memory}: the store then owns no thread and no provider. */
    private final ExecutorService executor;

    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean writeFailureReported = new AtomicBoolean();
    private final AtomicBoolean openFailureReported = new AtomicBoolean();

    /**
     * Guards the one-time backend connection attempt.
     *
     * <p>Every I/O task calls {@link #ensureBackend()} before touching the
     * provider, so a write can never run against a store whose backend has not
     * been opened yet. Relying on task enqueue order alone was not enough: a
     * {@code savePlayer} submitted before {@code open()} ran first and silently
     * dropped the write.
     */
    private final AtomicBoolean backendAttempted = new AtomicBoolean();

    private volatile DatabaseProvider provider;
    private volatile PlayerStateManager manager;

    /**
     * Creates a store. Call {@link #open()} to start the backend in the
     * background, or use {@link #memory()} for the no-op form.
     *
     * @param storage the parsed {@code lite.storage} settings (null means memory)
     * @param dataDirectory the plugin data directory; relative SQLite paths
     *                      resolve against it
     * @param declaredChannels the {@code lite.channels} declarations to mirror
     * @param platformName the platform identity stamped on the player row
     * @param warn sink for degraded-operation warnings (null-tolerant)
     * @param debug sink for verbose diagnostics (null-tolerant)
     */
    private LiteChannelStore(LiteStorageConfig storage,
                             Path dataDirectory,
                             Map<String, LiteChannelConfig> declaredChannels,
                             String platformName,
                             Consumer<String> warn,
                             Consumer<String> debug) {
        this.type = storage != null ? storage.getType() : LiteStorageConfig.Type.MEMORY;
        this.persistent = type != LiteStorageConfig.Type.MEMORY;
        this.dataDirectory = dataDirectory;
        this.declaredChannels = declaredChannels != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(declaredChannels))
                : Map.of();
        this.platformName = platformName;
        this.warn = warn != null ? warn : message -> { };
        this.debug = debug != null ? debug : message -> { };
        this.executor = persistent
                ? Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, IO_THREAD_NAME);
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
        this.storage = storage;
    }

    /**
     * Opens a store for the configured backend and starts connecting in the
     * background. Never blocks and never throws: an unreachable backend is
     * reported through {@code warn} and leaves the store memory-only.
     *
     * @param storage the parsed {@code lite.storage} settings
     * @param dataDirectory the plugin data directory
     * @param declaredChannels the configured channel declarations
     * @param platformName the platform identity for the persisted player row
     * @param warn warning sink (null-tolerant)
     * @param debug debug sink (null-tolerant)
     * @return a store; call {@link #open()} on it, or just use {@link #memory()}
     */
    public static LiteChannelStore create(LiteStorageConfig storage,
                                          Path dataDirectory,
                                          Map<String, LiteChannelConfig> declaredChannels,
                                          String platformName,
                                          Consumer<String> warn,
                                          Consumer<String> debug) {
        return new LiteChannelStore(storage, dataDirectory, declaredChannels,
                platformName, warn, debug);
    }

    /**
     * A store that never touches a database or starts a thread.
     *
     * @return the no-op store used by {@code lite.storage.type=memory} and by
     *         tests that drive {@link LocalAuthority} without a backend
     */
    public static LiteChannelStore memory() {
        return new LiteChannelStore(null, null, Map.of(), null, null, null);
    }

    /**
     * Starts the backend connection and the startup channel mirror on the I/O
     * thread. Idempotent; a no-op for {@code memory}.
     */
    public void open() {
        if (!persistent) {
            return;
        }
        submit(this::ensureBackend);
    }

    /**
     * @return whether a database backend is configured for this store. This is
     *         the configured form: it stays {@code true} for a backend that was
     *         configured but failed to connect, so callers use
     *         {@link #isConnected()} for the live answer
     */
    public boolean isPersistent() {
        return persistent;
    }

    /**
     * @return whether a backend is configured <em>and</em> currently open
     */
    public boolean isConnected() {
        DatabaseProvider current = provider;
        return current != null && current.isConnected();
    }

    /**
     * @return the configured backend type (MEMORY when none)
     */
    public LiteStorageConfig.Type getType() {
        return type;
    }

    // ------------------------------------------------------------------ writes

    /**
     * Queues one player's membership snapshot.
     *
     * <p>Callers capture the snapshot synchronously and return; the write itself
     * happens on the I/O thread. A no-op for {@code memory}, and once the store
     * has been closed.
     *
     * @param snapshot the membership to persist (null is ignored)
     */
    public void savePlayer(PlayerSnapshot snapshot) {
        if (!persistent || snapshot == null || snapshot.playerId() == null) {
            return;
        }
        if (closing.get()) {
            return;
        }
        submit(() -> writePlayer(snapshot));
    }

    /**
     * Drops a player from the in-memory cache without touching the row, so the
     * persisted membership survives for the next login while the cache does not
     * grow with every player that ever joined.
     *
     * @param playerId the player that disconnected (null is ignored)
     */
    public void evictPlayer(UUID playerId) {
        if (!persistent || playerId == null || closing.get()) {
            return;
        }
        submit(() -> {
            ensureBackend();
            PlayerStateManager current = manager;
            if (current != null) {
                current.removeFromCache(playerId, false);
            }
        });
    }

    // ------------------------------------------------------------------- reads

    /**
     * Queues a read of one player's persisted membership.
     *
     * @param playerId the player to restore (null is ignored)
     * @param sink receives the persisted snapshot on the I/O thread; not invoked
     *             when nothing is stored, the backend is down, or this is the
     *             {@code memory} store
     */
    public void loadPlayer(UUID playerId, Consumer<PlayerSnapshot> sink) {
        if (!persistent || playerId == null || sink == null || closing.get()) {
            return;
        }
        submit(() -> readPlayer(playerId, sink));
    }

    // -------------------------------------------------------------- lifecycle

    /**
     * Waits until every task queued before this call has run.
     *
     * <p>Used by shutdown (flush before closing the pool) and by tests that need
     * deterministic ordering without sleeping.
     *
     * @param timeoutMillis how long to wait
     * @return {@code true} when the queue drained; {@code true} for
     *         {@code memory} (nothing was ever queued)
     */
    public boolean flush(long timeoutMillis) {
        ExecutorService current = executor;
        if (current == null) {
            return true;
        }
        CountDownLatch barrier = new CountDownLatch(1);
        try {
            current.execute(barrier::countDown);
        } catch (RejectedExecutionException e) {
            // Already shut down: everything queued before it was drained.
            return true;
        }
        try {
            return barrier.await(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Flushes queued writes, stops the I/O thread and closes the pool.
     *
     * <p>Idempotent: the {@link LocalAuthorityClient} shutdown path and the
     * platform shell may both reach it, and only the first call does work.
     */
    @Override
    public void close() {
        if (!persistent || !closing.compareAndSet(false, true)) {
            return;
        }
        flush(DEFAULT_FLUSH_TIMEOUT_MILLIS);

        ExecutorService current = executor;
        if (current != null) {
            current.shutdown();
            try {
                if (!current.awaitTermination(DEFAULT_FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    current.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                current.shutdownNow();
            }
        }

        DatabaseProvider current0 = provider;
        provider = null;
        manager = null;
        if (current0 != null) {
            try {
                current0.shutdown();
            } catch (RuntimeException e) {
                warn.accept("Failed to close the lite storage backend: " + describe(e));
            }
        }
    }

    // --------------------------------------------------------------- internals

    /**
     * Connects the backend on first use, exactly once.
     *
     * <p>Called at the head of every I/O task. A failed attempt is not retried:
     * the store stays in memory-only mode for the rest of the process, which is
     * the documented degradation, and retrying on every queued write would turn
     * a down database into a connection-attempt storm.
     */
    private void ensureBackend() {
        if (provider != null || closing.get()) {
            return;
        }
        if (!backendAttempted.compareAndSet(false, true)) {
            return;
        }
        openBackend();
    }

    /** Runs on the I/O thread: connect, migrate, then mirror the channel roster. */
    private void openBackend() {
        if (closing.get()) {
            return;
        }
        DatabaseProvider created = null;
        try {
            created = createProvider();
            created.initialize();
            PlayerStateManager createdManager = new PlayerStateManager(created);
            this.provider = created;
            this.manager = createdManager;
            mirrorDeclaredChannels(created);
            debug.accept("Lite storage ready (" + type + "); channel membership is persisted");
        } catch (Throwable t) {
            // Every failure mode ends here: unsupported TLS settings, connection
            // refused, missing driver, migration error. Degrade, never propagate.
            if (created != null) {
                try {
                    created.shutdown();
                } catch (RuntimeException ignored) {
                    // Best-effort cleanup of a half-initialised pool.
                }
            }
            this.provider = null;
            this.manager = null;
            reportOpenFailure(t);
        }
    }

    /**
     * Builds the provider for the configured type, mirroring
     * {@code NovaLinkCore#createDatabaseProvider} (that one is private to
     * {@code :StarLink:core}, which the proxy must not depend on).
     *
     * @return the uninitialised provider
     * @throws IOException when the SQLite directory cannot be created
     */
    private DatabaseProvider createProvider() throws IOException {
        LiteStorageConfig config = storage;
        if (config == null) {
            throw new IllegalStateException("lite.storage is not configured");
        }
        return switch (config.getType()) {
            case MYSQL -> new MySQLProvider(
                    config.getHost(),
                    config.getPort(),
                    config.getDatabase(),
                    config.getUsername(),
                    config.getPassword(),
                    config.getPoolSize(),
                    config.getSslMode(),
                    config.isAllowPlaintext());
            case POSTGRESQL -> new PostgreSQLProvider(
                    config.getHost(),
                    config.getPort(),
                    config.getDatabase(),
                    config.getUsername(),
                    config.getPassword(),
                    config.getPoolSize(),
                    config.getSslMode(),
                    config.isAllowPlaintext());
            case SQLITE -> {
                Path file = resolveDataPath(config.getFilePath());
                // sqlite-jdbc refuses to open a file whose parent directory is
                // missing, and an embedded plugin gets a fresh data folder.
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                // Minimum 2 is headroom, not a correctness requirement. The
                // providers used to borrow a second connection while holding
                // the first (loadPlayerState kept its ResultSet open and called
                // loadMutes(), which pooled its own connection), so a one-slot
                // pool deadlocked into a Hikari timeout and every restore
                // failed. That root cause is fixed in :StarLink:storage — every
                // aggregate now reuses the caller's connection — and
                // SQLiteProviderPoolSizeOneTest covers poolSize=1 directly. The
                // floor stays only so a busy proxy has a spare slot.
                yield new SQLiteProvider(file.toString(), Math.max(2, config.getPoolSize()));
            }
            case MEMORY -> throw new IllegalStateException(
                    "memory storage is the in-process mode and owns no provider");
        };
    }

    /**
     * Resolves a configured path against the plugin data directory.
     *
     * <p>A relative {@code data/novachat.db} must land in the plugin's own data
     * folder. Resolving it against the JVM working directory — as a bare
     * {@code Paths.get} would — puts it in the proxy server's root, which is
     * shared with every other plugin and lost on reinstall. Mirrors
     * {@code NovaLinkCore#resolveDataPath}.
     *
     * @param configured the configured path (may be null/blank)
     * @return the resolved path
     */
    private Path resolveDataPath(String configured) {
        Path base = dataDirectory != null ? dataDirectory : Paths.get(".");
        if (configured == null || configured.isBlank()) {
            return base;
        }
        Path path = Paths.get(configured);
        return path.isAbsolute() ? path : base.resolve(path);
    }

    /**
     * Stores the declared {@code lite.channels} definitions in the backend.
     *
     * <p>Best-effort and idempotent: a channel that was already mirrored is
     * skipped, and a failure only logs at debug level because the authoritative
     * declaration still lives in the configuration file.
     *
     * @param database the open provider
     */
    private void mirrorDeclaredChannels(DatabaseProvider database) {
        for (Map.Entry<String, LiteChannelConfig> entry : declaredChannels.entrySet()) {
            String channelId = entry.getKey();
            if (channelId == null || channelId.isBlank() || persistedChannelIds.contains(channelId)) {
                continue;
            }
            LiteChannelConfig declared = entry.getValue();
            try {
                // Lite channels are network-wide. Channel rejects a GLOBAL channel
                // that carries a clientId, hence the explicit null.
                Channel channel = new Channel(
                        channelId,
                        declared != null ? declared.getDisplayName() : channelId,
                        ChannelScope.GLOBAL,
                        null,
                        ChannelSource.CONFIG);
                if (declared != null) {
                    channel.setPermission(declared.getPermission());
                    if (declared.getMaxCapacity() > 0) {
                        // Channel#setMaxCapacity rejects 0, so an unlimited lite
                        // channel keeps the Channel default instead of being
                        // recorded as unbounded. Membership itself is unaffected:
                        // capacity is enforced by LocalAuthority from config.
                        channel.setMaxCapacity(declared.getMaxCapacity());
                    }
                    channel.setSlowModeSeconds(declared.getSlowMode());
                }
                database.saveChannel(channel);
                persistedChannelIds.add(channelId);
            } catch (DatabaseException | RuntimeException e) {
                debug.accept("Lite storage could not mirror channel " + channelId
                        + ": " + describe(e));
            }
        }
    }

    /** Runs on the I/O thread: upsert one player's membership row. */
    private void writePlayer(PlayerSnapshot snapshot) {
        ensureBackend();
        PlayerStateManager current = manager;
        if (current == null) {
            reportWriteFailure(new IllegalStateException("lite storage backend is not connected"));
            return;
        }
        UUID playerId = snapshot.playerId();
        try {
            // Reuse the cached row when present so a repeated join/leave does not
            // pay an extra SELECT; otherwise let the manager load-or-create it.
            PlayerState state = current.getCachedState(playerId)
                    .orElseGet(() -> current.getOrCreateState(playerId, snapshot.playerName()));
            if (snapshot.playerName() != null) {
                state.setPlayerName(snapshot.playerName());
            }
            if (platformName != null) {
                state.setPlatform(platformName);
            }
            state.setJoinedChannels(snapshot.joinedChannels());
            state.setActiveChannel(snapshot.activeChannel());
            if (!current.saveState(state)) {
                reportWriteFailure(new IllegalStateException("savePlayerState returned false"));
                return;
            }
            writeFailureReported.set(false);
            debug.accept("Lite storage persisted " + snapshot.joinedChannels().size()
                    + " channel(s) for " + playerId + " (active=" + snapshot.activeChannel() + ")");
        } catch (RuntimeException e) {
            reportWriteFailure(e);
        }
    }

    /** Runs on the I/O thread: read one player's membership row. */
    private void readPlayer(UUID playerId, Consumer<PlayerSnapshot> sink) {
        ensureBackend();
        PlayerStateManager current = manager;
        if (current == null) {
            return;
        }
        try {
            Optional<PlayerState> loaded = current.loadState(playerId);
            if (loaded.isEmpty()) {
                return;
            }
            PlayerState state = loaded.get();
            sink.accept(new PlayerSnapshot(
                    state.getPlayerId(),
                    state.getPlayerName(),
                    state.getJoinedChannels(),
                    state.getActiveChannel()));
        } catch (RuntimeException e) {
            // A failed restore keeps the fresh default state: the player just
            // starts in the configured default channel, as before persistence.
            debug.accept("Lite storage could not restore " + playerId + ": " + describe(e));
        }
    }

    private void submit(Runnable task) {
        ExecutorService current = executor;
        if (current == null) {
            return;
        }
        try {
            current.execute(task);
        } catch (RejectedExecutionException e) {
            // Shutting down; nothing left to persist.
        }
    }

    /**
     * Reports the first connection failure at warn level (operators must see
     * that persistence is off) and subsequent ones at debug level so a DOWN
     * backend cannot flood the proxy log on every queue drain.
     *
     * @param failure the cause
     */
    private void reportOpenFailure(Throwable failure) {
        String message = "Lite storage (" + type + ") is unavailable; channel membership will"
                + " live in memory only and will be lost on restart: " + describe(failure);
        if (openFailureReported.compareAndSet(false, true)) {
            warn.accept(message);
        } else {
            debug.accept(message);
        }
    }

    /**
     * Logs a write failure once per failure streak. Chat must keep working, so
     * this never rethrows.
     *
     * @param failure the cause
     */
    private void reportWriteFailure(Throwable failure) {
        String message = "Lite storage (" + type + ") could not persist channel membership;"
                + " continuing in memory: " + describe(failure);
        if (writeFailureReported.compareAndSet(false, true)) {
            warn.accept(message);
        } else {
            debug.accept(message);
        }
    }

    /**
     * @param failure the throwable
     * @return a one-line description including the root cause chain's messages
     */
    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder(String.valueOf(failure));
        Throwable cause = failure.getCause();
        int depth = 0;
        while (cause != null && depth++ < 4) {
            text.append(" <- ").append(cause);
            cause = cause.getCause();
        }
        return text.toString();
    }

    /**
     * @param channels the channel ids to count in the backend
     * @return the ids this store has successfully mirrored (diagnostics)
     */
    Collection<String> getMirroredChannelIds() {
        synchronized (persistedChannelIds) {
            return Set.copyOf(persistedChannelIds);
        }
    }
}
