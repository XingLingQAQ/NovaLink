package com.nova.chat.mod.command;

import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.mod.chat.ChatInterceptor;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.ModServices;
import com.nova.chat.mod.platform.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Unit tests for the mod {@link IgnoreCommand} / {@link UnignoreCommand}
 * shells: argument pass-through to the shared {@code IgnoreCommandService},
 * service-state changes and localized receipts, mirroring
 * {@link CommandHandlersTest} infrastructure.
 */
@DisplayName("mod ignore/unignore command handlers")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IgnoreCommandHandlersTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String PLAYER_NAME = "Steve";

    @Mock
    private Platform platform;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private ChatInterceptor chatInterceptor;
    @Mock
    private ChannelCommandService channelCommandService;
    @Mock
    private com.nova.chat.client.channel.KnownChannelRegistry knownChannelRegistry;

    private IgnoreListService ignoreListService;
    private final List<String> sentMessages = new ArrayList<>();
    private Locale previousDefaultLocale;

    @BeforeEach
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        ignoreListService = new IgnoreListService();
        sentMessages.clear();
        org.mockito.Mockito.doAnswer(inv -> {
            sentMessages.add((String) inv.getArgument(1));
            return null;
        }).when(platform).sendMessage(eq(PLAYER), anyString());
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    private CommandContext context() {
        ModConfig config = new ModConfig();
        ModServices services = new ModServices(config, networkClient, chatInterceptor,
                channelCommandService, knownChannelRegistry, ignoreListService);
        return new CommandContext(PLAYER, PLAYER_NAME, platform, false)
                .withServices(services);
    }

    @Test
    @DisplayName("/nc ignore <player> adds the target and sends the added receipt")
    void ignoreAddsTarget() {
        new IgnoreCommand().execute(new String[]{"Alex"}, context());

        assertThat(ignoreListService.isIgnored(PLAYER, "Alex")).isTrue();
        assertThat(sentMessages).hasSize(1);
        assertThat(sentMessages.get(0)).contains("Alex");
    }

    @Test
    @DisplayName("/nc ignore without arguments sends the usage line")
    void ignoreWithoutArgsSendsUsage() {
        new IgnoreCommand().execute(new String[]{}, context());

        assertThat(sentMessages).hasSize(1);
        assertThat(sentMessages.get(0)).contains("/nc ignore");
    }

    @Test
    @DisplayName("/nc ignore <self> is rejected with the cannot-self receipt")
    void ignoreSelfRejected() {
        new IgnoreCommand().execute(new String[]{PLAYER_NAME}, context());

        assertThat(ignoreListService.listIgnored(PLAYER)).isEmpty();
        assertThat(sentMessages).hasSize(1);
    }

    @Test
    @DisplayName("/nc ignore list renders header and entries")
    void ignoreListRendersEntries() {
        ignoreListService.ignore(PLAYER, PLAYER_NAME, "Alex");

        new IgnoreCommand().execute(new String[]{"list"}, context());

        assertThat(sentMessages.size()).isGreaterThanOrEqualTo(2);
        assertThat(String.join("\n", sentMessages)).contains("alex");
    }

    @Test
    @DisplayName("/nc unignore <player> removes the target and sends the removed receipt")
    void unignoreRemovesTarget() {
        ignoreListService.ignore(PLAYER, PLAYER_NAME, "Alex");

        new UnignoreCommand().execute(new String[]{"Alex"}, context());

        assertThat(ignoreListService.isIgnored(PLAYER, "Alex")).isFalse();
        assertThat(sentMessages).hasSize(1);
        assertThat(sentMessages.get(0)).contains("Alex");
    }

    @Test
    @DisplayName("services-null guard reports uninitialized state")
    void servicesNullGuard() {
        CommandContext ctx = new CommandContext(PLAYER, PLAYER_NAME, platform, false);

        boolean result = new IgnoreCommand().execute(new String[]{"Alex"}, ctx);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("ignore tab completion offers the list argument")
    void ignoreTabCompletionOffersList() {
        assertThat(new IgnoreCommand().tabComplete(new String[]{"l"})).contains("list");
        assertThat(new IgnoreCommand().tabComplete(new String[]{"x"})).isEmpty();
    }
}
