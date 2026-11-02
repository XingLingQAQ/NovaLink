package com.nova.chat.pnx.chat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class RecentChannelSelections {
    static final int MAX_ENTRIES = 1024;
    static final long TTL_NANOS = TimeUnit.MINUTES.toNanos(2);

    private final Map<UUID, Selection> selections = new LinkedHashMap<>();
    private final LongSupplier clock;

    RecentChannelSelections() {
        this(System::nanoTime);
    }

    RecentChannelSelections(LongSupplier clock) {
        this.clock = clock;
    }

    synchronized void remember(UUID playerId, String channel) {
        long now = clock.getAsLong();
        selections.entrySet().removeIf(entry -> now - entry.getValue().savedAt() >= TTL_NANOS);
        selections.remove(playerId);
        if (channel == null || channel.isBlank()) {
            return;
        }
        if (selections.size() >= MAX_ENTRIES) {
            selections.remove(selections.keySet().iterator().next());
        }
        selections.put(playerId, new Selection(channel, now));
    }

    synchronized String take(UUID playerId) {
        Selection selection = selections.remove(playerId);
        return selection != null && clock.getAsLong() - selection.savedAt() < TTL_NANOS
                ? selection.channel() : null;
    }

    private record Selection(String channel, long savedAt) { }
}
