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
 */
public final class KnownChannelRegistry {

    private final Set<String> channelIds = ConcurrentHashMap.newKeySet();

    /**
     * Replaces the entire known-channel set. Safe to call from a Netty thread
     * (the platform facade parses the packet there); storage is concurrent.
     *
     * @param channels the new channel IDs (may be null / empty to clear)
     */
    public void replaceAll(Set<String> channels) {
        channelIds.clear();
        if (channels != null) {
            for (String id : channels) {
                if (id != null && !id.isBlank()) {
                    channelIds.add(id);
                }
            }
        }
    }

    /**
     * Adds channel IDs to the registry without clearing existing entries.
     *
     * @param channels the channel IDs to add (may be null / empty)
     */
    public void addAll(Set<String> channels) {
        if (channels == null) {
            return;
        }
        for (String id : channels) {
            if (id != null && !id.isBlank()) {
                channelIds.add(id);
            }
        }
    }

    /**
     * Returns channel IDs that start with the given prefix, sorted
     * case-insensitively. A null or empty prefix returns all known IDs.
     *
     * @param prefix the prefix to filter by (null / empty = all)
     * @return a new sorted list of matching channel IDs
     */
    public List<String> getKnownChannelIds(String prefix) {
        if (channelIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> filtered;
        if (prefix == null || prefix.isEmpty()) {
            filtered = new ArrayList<>(channelIds);
        } else {
            String lower = prefix.toLowerCase();
            filtered = new ArrayList<>();
            for (String id : channelIds) {
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
     * @return unmodifiable view of all known channel IDs
     */
    public Set<String> getAll() {
        return Collections.unmodifiableSet(channelIds);
    }

    /**
     * Whether the registry currently knows about the given channel.
     *
     * @param channelId the channel ID (null-safe)
     * @return true if the registry contains the channel
     */
    public boolean contains(String channelId) {
        return channelId != null && channelIds.contains(channelId);
    }

    /**
     * Whether the registry currently holds any known channels.
     *
     * @return true if no channels are known
     */
    public boolean isEmpty() {
        return channelIds.isEmpty();
    }

    /**
     * Clears all known channel IDs.
     */
    public void clear() {
        channelIds.clear();
    }
}
