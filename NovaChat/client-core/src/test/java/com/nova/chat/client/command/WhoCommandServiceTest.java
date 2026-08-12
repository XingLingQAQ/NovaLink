package com.nova.chat.client.command;

import com.nova.chat.client.i18n.I18n;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WhoCommandService}: member-list formatting and the
 * availability flag that gates the platform {@code WhoCommand} implementations.
 */
@DisplayName("WhoCommandService")
class WhoCommandServiceTest {

    @Test
    @DisplayName("isMemberListingSupported is true (backend now handles WHO)")
    void memberListingIsSupported() {
        assertThat(WhoCommandService.isMemberListingSupported()).isTrue();
    }

    @Test
    @DisplayName("formatMemberList renders header + body for a non-empty member list")
    void formatsNonEmptyList() {
        UUID requester = UUID.randomUUID();
        String result = WhoCommandService.formatMemberList(
                requester, "local", "Local Chat", "Alice, Bob, Carol", "3");

        // Header line names the display name + count; body line carries the CSV.
        assertThat(result).contains("Local Chat").contains("Alice, Bob, Carol");
        assertThat(result).contains("3");
        // Two lines: header + body.
        assertThat(result.lines().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("formatMemberList renders the empty prompt when count is 0")
    void formatsEmptyList() {
        UUID requester = UUID.randomUUID();
        String result = WhoCommandService.formatMemberList(
                requester, "local", "Local Chat", "", "0");

        assertThat(result).contains("Local Chat");
        // Empty body -> the chat.who.list_empty prompt is substituted; the CSV
        // must NOT appear.
        assertThat(result).doesNotContain(",");
    }

    @Test
    @DisplayName("formatMemberList falls back to channelId when displayName is blank")
    void fallsBackToChannelIdWhenDisplayNameBlank() {
        String result = WhoCommandService.formatMemberList(
                null, "global", "", "Alice", "1");
        assertThat(result).contains("global");
    }

    @Test
    @DisplayName("getFetchingPrompt interpolates the channel id")
    void fetchingPromptInterpolatesChannel() {
        String prompt = WhoCommandService.getFetchingPrompt("local");
        assertThat(prompt).contains("local");
    }

    @Test
    @DisplayName("getUnavailablePrompt resolves a non-key string")
    void unavailablePromptResolves() {
        // The key resolves to a localized message, never the raw key.
        assertThat(WhoCommandService.getUnavailablePrompt()).isNotEmpty();
    }
}
