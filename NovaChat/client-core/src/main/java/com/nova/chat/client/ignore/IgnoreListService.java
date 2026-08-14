package com.nova.chat.client.ignore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-player ignore lists: a player can ignore other players by name so their
 * chat messages, {@code @mention} notifications and ItemDisplay lines are no
 * longer rendered for the ignoring player.
 *
 * <p>Names are stored normalized to lowercase, so lookups are
 * case-insensitive. A player cannot ignore themselves, and each player can
 * hold at most {@link #MAX_IGNORES_PER_PLAYER} entries.
 *
 * <p><b>Persistence:</b> the service persists to
 * {@code <dataDirectory>/ignore-lists.json}. The data directory is injected by
 * each platform plugin at startup via {@link #setDataDirectory(Path)}
 * (mirroring the {@code I18n.setExternalLangDir} precedent). Writes are
 * debounced on a single daemon thread and performed atomically
 * (temp file + move); a missing or corrupt file loads as empty.
 *
 * <p><b>Thread safety:</b> all mutations go through
 * {@link ConcurrentHashMap#compute}, which is atomic per player key, so the
 * per-player size limit holds under concurrent callers (e.g. Folia region
 * threads, Netty handlers).
 */
public final class IgnoreListService {

    /** Maximum ignore entries per player. */
    public static final int MAX_IGNORES_PER_PLAYER = 100;

    /** Persistence file name inside the injected data directory. */
    public static final String FILE_NAME = "ignore-lists.json";

    private static final Logger LOG = Logger.getLogger(IgnoreListService.class.getName());

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type FILE_TYPE = new TypeToken<Map<String, List<String>>>() { }.getType();

    /** Outcome of an {@link #ignore(UUID, String, String)} call. */
    public enum AddResult {
        /** The name was added to the ignore list. */
        ADDED,
        /** The name was already on the ignore list. */
        ALREADY_IGNORED,
        /** The list already holds {@link #MAX_IGNORES_PER_PLAYER} entries. */
        LIMIT_REACHED,
        /** The player tried to ignore themselves. */
        SELF
    }

    private final ConcurrentMap<UUID, Set<String>> ignores = new ConcurrentHashMap<>();

    /** Injected data directory; null until a platform registers one (persistence disabled). */
    private volatile Path dataDirectory;

    private final long debounceMillis;
    private final AtomicBoolean savePending = new AtomicBoolean(false);
    private final Object saveLock = new Object();
    private final ScheduledExecutorService saveExecutor;

    /** Creates a service with the default 500 ms write debounce. */
    public IgnoreListService() {
        this(500);
    }

    /**
     * Creates a service with a custom write debounce (test hook).
     *
     * @param debounceMillis delay between the first mutation and the disk write
     */
    IgnoreListService(long debounceMillis) {
        this.debounceMillis = debounceMillis;
        this.saveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "novachat-ignore-save");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Registers the plugin data directory and loads any existing
     * {@code ignore-lists.json} from it, replacing the in-memory state.
     * Call once at plugin startup before players interact with the service.
     *
     * <p>A missing file loads as empty; a corrupt file is logged and treated
     * as empty rather than failing startup.
     *
     * @param dataDirectory the plugin data directory (null disables persistence)
     */
    public void setDataDirectory(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        if (dataDirectory == null) {
            return;
        }
        loadFromDisk(dataDirectory.resolve(FILE_NAME));
    }

    /**
     * Adds a name to the player's ignore list.
     *
     * @param playerId   the ignoring player's UUID
     * @param playerName the ignoring player's own name (for the self check; may be null)
     * @param targetName the name to ignore
     * @return the outcome; {@link AddResult#SELF} when targetName equals playerName
     *         (case-insensitive)
     */
    public AddResult ignore(UUID playerId, String playerName, String targetName) {
        if (playerId == null || targetName == null || targetName.trim().isEmpty()) {
            return AddResult.ALREADY_IGNORED;
        }
        String normalized = normalize(targetName);
        if (playerName != null && normalize(playerName).equals(normalized)) {
            return AddResult.SELF;
        }

        AddResult[] result = new AddResult[1];
        ignores.compute(playerId, (id, existing) -> {
            Set<String> set = existing != null ? existing : ConcurrentHashMap.newKeySet();
            if (set.contains(normalized)) {
                result[0] = AddResult.ALREADY_IGNORED;
            } else if (set.size() >= MAX_IGNORES_PER_PLAYER) {
                result[0] = AddResult.LIMIT_REACHED;
            } else {
                set.add(normalized);
                result[0] = AddResult.ADDED;
            }
            return set;
        });

        if (result[0] == AddResult.ADDED) {
            scheduleSave();
        }
        return result[0];
    }

    /**
     * Removes a name from the player's ignore list.
     *
     * @param playerId   the player's UUID
     * @param targetName the name to stop ignoring
     * @return true if the name was on the list and has been removed
     */
    public boolean unignore(UUID playerId, String targetName) {
        if (playerId == null || targetName == null || targetName.trim().isEmpty()) {
            return false;
        }
        String normalized = normalize(targetName);
        boolean[] removed = new boolean[1];
        ignores.computeIfPresent(playerId, (id, set) -> {
            removed[0] = set.remove(normalized);
            return set.isEmpty() ? null : set;
        });
        if (removed[0]) {
            scheduleSave();
        }
        return removed[0];
    }

    /**
     * Checks whether the viewer has ignored the given sender name.
     *
     * @param viewerId   the viewing player's UUID
     * @param senderName the sender's name (case-insensitive)
     * @return true if the sender is on the viewer's ignore list
     */
    public boolean isIgnored(UUID viewerId, String senderName) {
        if (viewerId == null || senderName == null || senderName.isEmpty()) {
            return false;
        }
        Set<String> set = ignores.get(viewerId);
        return set != null && set.contains(normalize(senderName));
    }

    /**
     * Returns the player's ignored names, sorted alphabetically.
     *
     * @param playerId the player's UUID
     * @return a new sorted list (empty when nothing is ignored)
     */
    public List<String> listIgnored(UUID playerId) {
        if (playerId == null) {
            return Collections.emptyList();
        }
        Set<String> set = ignores.get(playerId);
        if (set == null || set.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>(set);
        Collections.sort(names);
        return names;
    }

    /**
     * Writes the current state to disk immediately (used at plugin shutdown).
     * No-op when no data directory has been registered.
     */
    public void flush() {
        savePending.set(false);
        saveNow();
    }

    /**
     * Flushes pending writes and stops the background save thread.
     */
    public void close() {
        saveExecutor.shutdown();
        flush();
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void scheduleSave() {
        if (dataDirectory == null) {
            return;
        }
        if (savePending.compareAndSet(false, true)) {
            try {
                saveExecutor.schedule(() -> {
                    savePending.set(false);
                    saveNow();
                }, debounceMillis, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Executor already shut down (plugin disabling); flush() handles the rest.
                savePending.set(false);
            }
        }
    }

    private void saveNow() {
        Path dir = dataDirectory;
        if (dir == null) {
            return;
        }
        // Serialize writers so a debounced write and a shutdown flush cannot interleave.
        synchronized (saveLock) {
            try {
                Files.createDirectories(dir);
                Path target = dir.resolve(FILE_NAME);
                Path temp = dir.resolve(FILE_NAME + ".tmp");

                Map<String, List<String>> snapshot = new TreeMap<>();
                for (Map.Entry<UUID, Set<String>> entry : ignores.entrySet()) {
                    if (!entry.getValue().isEmpty()) {
                        List<String> names = new ArrayList<>(entry.getValue());
                        Collections.sort(names);
                        snapshot.put(entry.getKey().toString(), names);
                    }
                }

                Files.write(temp, GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "[NovaChat] Failed to save " + FILE_NAME + ": " + e.getMessage());
            }
        }
    }

    private void loadFromDisk(Path file) {
        ignores.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Map<String, List<String>> data = GSON.fromJson(json, FILE_TYPE);
            if (data == null) {
                return;
            }
            for (Map.Entry<String, List<String>> entry : new HashMap<>(data).entrySet()) {
                UUID playerId;
                try {
                    playerId = UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException e) {
                    continue; // Skip malformed keys instead of failing the whole file.
                }
                if (entry.getValue() == null) {
                    continue;
                }
                Set<String> set = ConcurrentHashMap.newKeySet();
                for (String name : entry.getValue()) {
                    if (name != null && !name.trim().isEmpty() && set.size() < MAX_IGNORES_PER_PLAYER) {
                        set.add(normalize(name));
                    }
                }
                if (!set.isEmpty()) {
                    ignores.put(playerId, set);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Corrupt or unreadable file: log and start empty rather than fail startup.
            LOG.log(Level.WARNING, "[NovaChat] Could not read " + FILE_NAME
                    + " (starting with empty ignore lists): " + e.getMessage());
            ignores.clear();
        }
    }
}
