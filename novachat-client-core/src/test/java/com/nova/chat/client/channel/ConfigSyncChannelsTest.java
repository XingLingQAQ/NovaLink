package com.nova.chat.client.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConfigSyncChannels#extract}, covering null/blank/malformed
 * JSON handling, global-channel extraction, and per-client channel filtering by
 * username.
 */
@DisplayName("ConfigSyncChannels")
class ConfigSyncChannelsTest {

    @Nested
    @DisplayName("extract")
    class Extract {
        @Test
        @DisplayName("null / blank json returns empty set")
        void nullOrBlankReturnsEmpty() {
            assertThat(ConfigSyncChannels.extract(null, "server1")).isEmpty();
            assertThat(ConfigSyncChannels.extract("   ", "server1")).isEmpty();
        }

        @Test
        @DisplayName("malformed json returns empty set without throwing")
        void malformedReturnsEmpty() {
            assertThat(ConfigSyncChannels.extract("{not json", "server1")).isEmpty();
        }

        @Test
        @DisplayName("extracts global_channels keys")
        void extractsGlobalChannels() {
            String json = "{\"global_channels\":{\"global\":{},\"pvp\":{}}}";
            Set<String> result = ConfigSyncChannels.extract(json, null);
            assertThat(result).containsExactlyInAnyOrder("global", "pvp");
        }

        @Test
        @DisplayName("adds per-client channels for the matching username only")
        void addsMatchingClientChannels() {
            String json = "{\"global_channels\":{\"global\":{}},\"clients\":["
                    + "{\"username\":\"server1\",\"channels\":{\"local\":{},\"resource\":{}}},"
                    + "{\"username\":\"server2\",\"channels\":{\"pvp\":{}}}"
                    + "]}";
            Set<String> result = ConfigSyncChannels.extract(json, "server1");
            assertThat(result).containsExactlyInAnyOrder("global", "local", "resource");
        }

        @Test
        @DisplayName("blank username returns only globals")
        void blankUsernameReturnsOnlyGlobals() {
            String json = "{\"global_channels\":{\"global\":{}},\"clients\":["
                    + "{\"username\":\"server1\",\"channels\":{\"local\":{}}}"
                    + "]}";
            assertThat(ConfigSyncChannels.extract(json, null)).containsExactly("global");
            assertThat(ConfigSyncChannels.extract(json, "  ")).containsExactly("global");
        }

        @Test
        @DisplayName("missing clients array returns only globals")
        void missingClientsReturnsGlobals() {
            String json = "{\"global_channels\":{\"global\":{}}}";
            assertThat(ConfigSyncChannels.extract(json, "server1")).containsExactly("global");
        }

        @Test
        @DisplayName("username not present in clients returns only globals")
        void usernameNotFoundReturnsGlobals() {
            String json = "{\"global_channels\":{\"global\":{}},\"clients\":["
                    + "{\"username\":\"other\",\"channels\":{\"local\":{}}}"
                    + "]}";
            assertThat(ConfigSyncChannels.extract(json, "server1")).containsExactly("global");
        }
    }
}
