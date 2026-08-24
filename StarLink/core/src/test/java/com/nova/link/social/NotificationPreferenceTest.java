package com.nova.link.social;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NotificationPreference} — the per-player notification
 * knobs introduced by §11.6 item-18 / PANEL proposal 08.
 *
 * <p>Verifies the defaults contract (mentions enabled), the playerId-keyed
 * equality contract, and the absence of any {@code dmsEnabled} concept here
 * (DM opt-out lives on {@code PlayerState}).
 */
@DisplayName("NotificationPreference")
class NotificationPreferenceTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    @DisplayName("defaults enable mentions and stamp updatedAt to now")
    void defaultsEnableMentions() {
        long before = System.currentTimeMillis();
        NotificationPreference defaults = NotificationPreference.defaults(ALICE);
        long after = System.currentTimeMillis();

        assertThat(defaults.getPlayerId()).isEqualTo(ALICE);
        assertThat(defaults.isMentionsEnabled()).isTrue();
        assertThat(defaults.getUpdatedAt()).isBetween(before, after);
    }

    @Test
    @DisplayName("full constructor exposes all fields via getters")
    void fullConstructor() {
        long timestamp = 1_700_000_000_000L;
        NotificationPreference preference = new NotificationPreference(ALICE, false, timestamp);

        assertThat(preference.getPlayerId()).isEqualTo(ALICE);
        assertThat(preference.isMentionsEnabled()).isFalse();
        assertThat(preference.getUpdatedAt()).isEqualTo(timestamp);
    }

    @Test
    @DisplayName("equality is keyed on playerId only")
    void equalsByPlayerId() {
        NotificationPreference a = new NotificationPreference(ALICE, true, 1L);
        NotificationPreference b = new NotificationPreference(ALICE, false, 9_999L);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("different playerId breaks equality")
    void unequalWhenPlayerDiffers() {
        NotificationPreference a = new NotificationPreference(ALICE, true, 1L);
        NotificationPreference b = new NotificationPreference(BOB, true, 1L);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("a preference equals itself and not null/foreign types")
    void reflexivityAndNullForeign() {
        NotificationPreference preference = new NotificationPreference(ALICE, true, 1L);

        assertThat(preference).isEqualTo(preference);
        assertThat(preference).isNotEqualTo(null);
        assertThat(preference).isNotEqualTo("not a preference");
    }

    @Test
    @DisplayName("toString mentions playerId and mentionsEnabled")
    void toStringMentionsFields() {
        NotificationPreference preference = new NotificationPreference(ALICE, false, 42L);

        String string = preference.toString();
        assertThat(string).contains(ALICE.toString());
        assertThat(string).contains("mentionsEnabled=false");
        assertThat(string).contains("updatedAt=42");
    }
}
