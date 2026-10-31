package com.nova.chat.pnx.listener;

import org.powernukkitx.Player;
import org.powernukkitx.event.player.PlayerJoinEvent;
import org.powernukkitx.lang.LangCode;
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

@DisplayName("PNX LocaleListener")
@ExtendWith(MockitoExtension.class)
class LocaleListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Mock
    private PlayerJoinEvent event;
    @Mock
    private Player player;
    private LocaleListener listener = new LocaleListener();

    @AfterEach
    void tearDown() {
        // Clean up any I18n registration leaked by the test so suites stay isolated.
        I18n.registerPlayerLocale(PLAYER_ID, null);
    }

    @Test
    @DisplayName("registers parsed locale from player language code")
    void registersLocaleFromPlayerLanguage() {
        when(event.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getLanguageCode()).thenReturn(LangCode.en_US);

        listener.onPlayerJoin(event);

        Locale resolved = I18n.resolvePlayerLocale(PLAYER_ID);
        assertThat(resolved).isEqualTo(LocaleResolver.normalize(Locale.US));
    }

    @Test
    @DisplayName("zh_CN language code resolves to the root fallback locale")
    void registersZhCnLocale() {
        when(event.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getLanguageCode()).thenReturn(LangCode.zh_CN);

        listener.onPlayerJoin(event);

        assertThat(I18n.resolvePlayerLocale(PLAYER_ID))
                .isEqualTo(LocaleResolver.normalize(Locale.SIMPLIFIED_CHINESE));
    }

    @Test
    @DisplayName("missing language code clears any prior registration")
    void missingLanguageCodeClearsRegistration() {
        I18n.registerPlayerLocale(PLAYER_ID, Locale.US);
        when(event.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getLanguageCode()).thenReturn(null);

        listener.onPlayerJoin(event);

        assertThat(I18n.resolvePlayerLocale(PLAYER_ID))
                .isEqualTo(I18n.getDefaultLocale());
    }

    @Test
    @DisplayName("null player returns early without touching I18n")
    void nullPlayerReturnsEarly() {
        when(event.getPlayer()).thenReturn(null);

        listener.onPlayerJoin(event);

        verify(player, never()).getUniqueId();
        verify(player, never()).getLanguageCode();
    }

    @Test
    @DisplayName("language lookup failure leaves the player unregistered")
    void languageLookupExceptionFallsBackSilently() {
        when(event.getPlayer()).thenReturn(player);
        lenient().when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getLanguageCode()).thenThrow(new RuntimeException("not ready"));

        listener.onPlayerJoin(event);

        assertThat(I18n.resolvePlayerLocale(PLAYER_ID))
                .isEqualTo(I18n.getDefaultLocale());
    }
}
