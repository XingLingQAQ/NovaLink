package com.nova.chat.client.channel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of channel IDs the backend advertised via
 * {@code ConfigSyncPacket}.
 *
 * <p>Populated by each platform's facade when it receives a ConfigSync push;
 * consumed by {@code /nc list} (via {@link ListCommandService}) and by
 * {@code /nc join <Tab>} completion. When the backend does not push a roster
 * for a platform, the registry stays empty and consumers degrade gracefully
 * (list shows the "no known channels" prompt, tab completion falls back).
 *
 * <p>UX-DESIGN §2.1.
 *
 * <p>Atomicity: the backing set is published via a {@code volatile} reference.
 * Mutations ({@link #replaceAll}, {@link #addAll}) build a
 * fresh set and then publish it with a single reference assignment, so readers
 * always observe a consistent snapshot — never the empty / half-rebuilt
 * intermediate state a {@code clear + addAll} sequence would expose during a
 * ConfigSync re-push (BUG-H3). Read methods read the reference once and iterate
 * that snapshot.
 */
public final class KnownChannelRegistry {

    // Volatile so reference swaps are visible across threads; readers snapshot
    // the reference once and iterate it. The set itself is a concurrent key set
    // so addAll's copy-then-publish is safe even if a stale reader still holds
    // the previous reference.
    private volatile Set<String> channelIds = Set.of();

    private static Set<String> newSet() {
        return ConcurrentHashMap.newKeySet();
    }

    private static Set<String> copyInto(Set<String> source) {
        Set<String> dest = newSet();
        if (source != null) {
            for (String id : source) {
                if (id != null && !id.isBlank()) {
                    dest.add(id);
                }
            }
        }
        return dest;
    }

    /**
     * Replaces the entire known-channel set. Safe to call from a Netty thread
     * (the platform facade parses the packet there). Builds the new set fully,
     * then publishes it with a single atomic reference assignment so concurrent
     * readers never observe an empty or half-rebuilt set.
     *
     * @param channels the new channel IDs (may be null / empty to clear)
     */
    public void replaceAll(Set<String> channels) {
        channelIds = copyInto(channels);
    }

    /**
     * Adds channel IDs to the registry without clearing existing entries.
     *
     * <p>Copy-on-write: reads a consistent snapshot of the current set, adds
     * the new IDs into the copy, then publishes it atomically.
     *
     * @param channels the channel IDs to add (may be null / empty)
     */
    public void addAll(Set<String> channels) {
        if (channels == null || channels.isEmpty()) {
            return;
        }
        Set<String> base = copyInto(channelIds);
        for (String id : channels) {
            if (id != null && !id.isBlank()) {
                base.add(id);
            }
        }
        channelIds = base;
    }

    /**
     * Returns channel IDs that start with the given prefix, sorted
     * case-insensitively. A null or empty prefix returns all known IDs.
     *
     * @param prefix the prefix to filter by (null / empty = all)
     * @return a new sorted list of matching channel IDs
     */
    public List<String> getKnownChannelIds(String prefix) {
        Set<String> snapshot = channelIds;
        if (snapshot.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> filtered;
        if (prefix == null || prefix.isEmpty()) {
            filtered = new ArrayList<>(snapshot);
        } else {
            String lower = prefix.toLowerCase();
            filtered = new ArrayList<>();
            for (String id : snapshot) {
                if (id != null && id.toLowerCase().startsWith(lower)) {
                    filtered.add(id);
                }
            }
        }
        filtered.sort(String.CASE_INSENSITIVE_ORDER);
        return filtered;
    }

    /**
     * Returns an unmodifiable view of all known channel IDs.
     *
     * <p>The view is backed by the snapshot at call time; later mutations to
     * the registry are not reflected in a previously returned view.
     *
     * @return unmodifiable view of all known channel IDs
     */
    public Set<String> getAll() {
        return Collections.unmodifiableSet(channelIds);
    }
}
