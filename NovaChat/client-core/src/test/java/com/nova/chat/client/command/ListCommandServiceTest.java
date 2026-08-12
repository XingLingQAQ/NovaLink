package com.nova.chat.client.command;

import com.nova.chat.client.channel.KnownChannelRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ListCommandService#formatChannelList}, covering empty
 * registries, sorted known-channel listing with join markers, joined-but-unknown
 * channels, and null-joined handling.
 */
@DisplayName("ListCommandService")
class ListCommandServiceTest {

    @Nested
    @DisplayName("formatChannelList")
    class Format {

        @Test
        @DisplayName("empty registry with no joined channels returns the empty prompt")
        void emptyRegistryReturnsPrompt() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            List<String> lines = ListCommandService.formatChannelList(registry, Set.of());
            assertThat(lines).hasSize(1);
            assertThat(lines.get(0)).contains("暂无已知频道");
        }

        @Test
        @DisplayName("null registry returns the empty prompt")
        void nullRegistryReturnsPrompt() {
            List<String> lines = ListCommandService.formatChannelList(null, Set.of());
            assertThat(lines.get(0)).contains("暂无已知频道");
        }

        @Test
        @DisplayName("known channels are listed sorted with join markers")
        void knownChannelsListedSorted() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(Set.of("pvp", "global", "local"));

            List<String> lines = ListCommandService.formatChannelList(registry, Set.of());

            assertThat(lines).hasSize(3);
            // Sorted case-insensitively: global, local, pvp — all not-joined (○) since none joined.
            assertThat(lines.get(0)).contains("○").contains("global");
            assertThat(lines.get(1)).contains("○").contains("local");
            assertThat(lines.get(2)).contains("○").contains("pvp");
            // None are joined, so no line carries the joined marker.
            assertThat(lines).noneMatch(l -> l.contains("✓"));
        }

        @Test
        @DisplayName("joined channels are marked with the joined marker")
        void joinedChannelsMarked() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(Set.of("pvp", "global"));

            Set<String> joined = new LinkedHashSet<>();
            joined.add("global");

            List<String> lines = ListCommandService.formatChannelList(registry, joined);

            assertThat(lines).hasSize(2);
            // global is joined -> joined marker (✓), pvp is not -> not-joined marker (○)
            String globalLine = lines.stream().filter(l -> l.contains("global")).findFirst().orElseThrow();
            String pvpLine = lines.stream().filter(l -> l.contains("pvp")).findFirst().orElseThrow();
            assertThat(globalLine).contains("✓");
            assertThat(pvpLine).contains("○");
            assertThat(pvpLine).doesNotContain("✓");
        }

        @Test
        @DisplayName("joined-but-unknown channels are appended after known ones")
        void joinedUnknownAppended() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(Set.of("global"));

            Set<String> joined = new LinkedHashSet<>();
            joined.add("global");
            joined.add("custom-channel");

            List<String> lines = ListCommandService.formatChannelList(registry, joined);

            assertThat(lines).hasSize(2);
            assertThat(lines.get(0)).contains("global");
            assertThat(lines.get(1)).contains("custom-channel").contains("✓");
        }

        @Test
        @DisplayName("null joinedChannels is treated as empty")
        void nullJoinedTreatedAsEmpty() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(Set.of("global"));

            List<String> lines = ListCommandService.formatChannelList(registry, null);

            assertThat(lines).hasSize(1);
            assertThat(lines.get(0)).contains("○").contains("global");
        }

        @Test
        @DisplayName("joined-only (empty registry) still lists joined channels")
        void joinedOnlyWhenRegistryEmpty() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            Set<String> joined = new LinkedHashSet<>();
            joined.add("global");

            List<String> lines = ListCommandService.formatChannelList(registry, joined);

            assertThat(lines).hasSize(1);
            assertThat(lines.get(0)).contains("✓").contains("global");
        }

        @Test
        @DisplayName("each line has exactly one channel id")
        void oneChannelPerLine() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(Set.of("global", "local", "pvp", "resource"));

            List<String> lines = ListCommandService.formatChannelList(registry, Set.of("local"));

            assertThat(lines).hasSize(4);
            // All lines start with a marker then the channel name
            for (String line : lines) {
                assertThat(line).matches(".*[✓○].*");
            }
        }
    }
}
