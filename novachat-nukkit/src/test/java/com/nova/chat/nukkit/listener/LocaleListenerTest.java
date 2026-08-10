package com.nova.chat.nukkit.listener;

import cn.nukkit.Player;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.utils.LoginChainData;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Nukkit {@link LocaleListener}.
 *
 * <p>Verifies the bedrock-specific locale capture: the client language code is
 * read from {@code LoginChainData.getLanguageCode()} on join and registered with
 * the shared {@link I18n} service. Covers the happy path, blank locale, null
 * player, and the exception fallback that leaves the player unregistered.
 */
@DisplayName("Nukkit LocaleListener")
@ExtendWith(MockitoExtension.class)
class LocaleListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Mock
    private PlayerJoinEvent event;
    @Mock
    private Player player;
    @Mock
    private LoginChainData loginChainData;

    private LocaleListener listener = new LocaleListener();

    @AfterEach
    void tearDown() {
        // Clean up any I18n registration leaked by the test so suites stay isolated.
        I18n.registerPlayerLocale(PLAYER_ID, null);
    }

    @Test
    @DisplayName("registers parsed locale from login chain language code")
    void registersLocaleFromLoginChain() {
        when(event.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getLoginChainData()).thenReturn(loginChainData);
        when(loginChainData.getLanguageCode()).thenReturn("en_US");

        listener.onPlayerJoin(event);

        Locale resolved = I18n.resolvePlayerLocale(PLAYER_ID);
        // LocaleResolver.normalize collapses to en_US; resolvePlayerLocale never returns null.
        assertThat(resolved).isEqualTo(LocaleResolver.normalize(Locale.US));
    }

    @Test
    @DisplayName("zh_CN language code resolves to the root fallback locale")
    void registersZhCnLocale() {
        when(event.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getLoginChainData()).thenReturn(loginChainData);
        when(loginChainData.getLanguageCode()).thenReturn("zh_CN");

        listener.onPlayerJoin(event);

        assertThat(I18n.resolvePlayerLocale(PLAYER_ID))
                .isEqualTo(LocaleResolver.normalize(Locale.SIMPLIFIED_CHINESE));
    }

    @Test
    @DisplayName("blank language code clears any prior registration (parse returns null)")
    void blankLanguageCodeClearsRegistration() {
        // Pre-register a locale to prove the blank path clears it.
        I18n.registerPlayerLocale(PLAYER_ID, Locale.US);
        when(event.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getLoginChainData()).thenReturn(loginChainData);
        when(loginChainData.getLanguageCode()).thenReturn("   ");

        listener.onPlayerJoin(event);

        // registerPlayerLocale(uuid, null) removes the entry; resolve falls back to default.
        assertThat(I18n.resolvePlayerLocale(PLAYER_ID))
                .isEqualTo(I18n.getDefaultLocale());
    }

    @Test
    @DisplayName("null player returns early without touching I18n")
    void nullPlayerReturnsEarly() {
        when(event.getPlayer()).thenReturn(null);

        listener.onPlayerJoin(event);

        // No interaction with the player or login chain beyond the null check.
        verify(player, never()).getUniqueId();
        verify(loginChainData, never()).getLanguageCode();
    }

    @Test
    @DisplayName("getLoginChainData throwing falls back silently (player unregistered)")
    void loginChainExceptionFallsBackSilently() {
        when(event.getPlayer()).thenReturn(player);
        // getUniqueId is never reached because getLoginChainData throws first; lenient.
        lenient().when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getLoginChainData()).thenThrow(new RuntimeException("not ready"));

        listener.onPlayerJoin(event);

        // Exception is swallowed; player resolves to the default locale (never registered).
        assertThat(I18n.resolvePlayerLocale(PLAYER_ID))
                .isEqualTo(I18n.getDefaultLocale());
    }
}
