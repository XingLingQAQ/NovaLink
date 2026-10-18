package com.nova.link.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RecentKeepAlives")
class RecentKeepAlivesTest {

    @Test
    @DisplayName("remembers the ids it was given, by value")
    void remembersByValue() {
        RecentKeepAlives recent = new RecentKeepAlives(4, 60_000_000_000L);
        UUID id = UUID.randomUUID();
        recent.remember(id);

        assertThat(recent.contains(new UUID(id.getMostSignificantBits(),
                id.getLeastSignificantBits()))).isTrue();
        assertThat(recent.contains(UUID.randomUUID())).isFalse();
        assertThat(recent.contains(null)).isFalse();
    }

    @Test
    @DisplayName("expired ids stop matching")
    void expiresEntries() {
        long[] now = {0L};
        RecentKeepAlives recent = new RecentKeepAlives(4, 1_000_000_000L, () -> now[0]);
        UUID id = UUID.randomUUID();
        recent.remember(id);

        now[0] = 999_999_999L;
        assertThat(recent.contains(id)).isTrue();

        now[0] = 1_000_000_000L;
        assertThat(recent.contains(id)).isFalse();
    }

    @Test
    @DisplayName("the oldest id is evicted once capacity is reached")
    void evictsOldestAtCapacity() {
        RecentKeepAlives recent = new RecentKeepAlives(2, 60_000_000_000L);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        recent.remember(first);
        recent.remember(second);
        recent.remember(third);

        assertThat(recent.contains(first)).isFalse();
        assertThat(recent.contains(second)).isTrue();
        assertThat(recent.contains(third)).isTrue();
    }

    @Test
    @DisplayName("remembering null is a no-op")
    void nullIsIgnored() {
        RecentKeepAlives recent = new RecentKeepAlives(2, 60_000_000_000L);
        recent.remember(null);
        assertThat(recent.contains(null)).isFalse();
    }
}
