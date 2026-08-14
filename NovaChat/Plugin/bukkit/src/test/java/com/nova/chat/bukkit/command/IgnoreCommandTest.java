package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.error.ErrorMessageHandler;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Bukkit {@code /nc ignore} / {@code /nc unignore} command
 * shells: argument forwarding to the shared IgnoreCommandService and the
 * localized receipts sent back to the player.
 */
@DisplayName("Bukkit Ignore/Unignore commands")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IgnoreCommandTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatBukkit plugin;
    @Mock
    private ErrorMessageHandler errorHandler;
    @Mock
    private Player player;

    private IgnoreListService ignoreListService;
    private Locale previousDefaultLocale;

    @BeforeEach
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        ignoreListService = new IgnoreListService();
        when(plugin.getErrorHandler()).thenReturn(errorHandler);
        when(plugin.getIgnoreListService()).thenReturn(ignoreListService);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Viewer");
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    private String lastMessage() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(player, atLeastOnce()).sendMessage(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("/nc ignore <player> adds the target and sends the localized receipt")
    void ignoreAddsTarget() {
        new IgnoreCommand(plugin).execute(player, new String[]{"Steve"});

        assertThat(ignoreListService.isIgnored(PLAYER_ID, "steve")).isTrue();
        assertThat(lastMessage()).contains("已屏蔽玩家").contains("Steve");
    }

    @Test
    @DisplayName("/nc ignore without args sends the usage line")
    void ignoreWithoutArgsShowsUsage() {
        new IgnoreCommand(plugin).execute(player, new String[0]);
        assertThat(lastMessage()).contains("用法");
    }

    @Test
    @DisplayName("/nc ignore list renders the list")
    void ignoreListRenders() {
        ignoreListService.ignore(PLAYER_ID, "Viewer", "Steve");
        new IgnoreCommand(plugin).execute(player, new String[]{"list"});
        assertThat(lastMessage()).contains("steve");
    }

    @Test
    @DisplayName("/nc unignore <player> removes the target")
    void unignoreRemovesTarget() {
        ignoreListService.ignore(PLAYER_ID, "Viewer", "Steve");
        new UnignoreCommand(plugin).execute(player, new String[]{"Steve"});

        assertThat(ignoreListService.isIgnored(PLAYER_ID, "steve")).isFalse();
        assertThat(lastMessage()).contains("已解除屏蔽玩家");
    }
}
