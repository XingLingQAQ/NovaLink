package com.nova.chat.mod.platform;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.mod.chat.ChatInterceptor;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NetworkClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ModServices}: constructor wiring and getter identity.
 */
@DisplayName("ModServices")
class ModServicesTest {

    @Test
    @DisplayName("getters return the exact constructor-supplied instances")
    void gettersReturnConstructorInstances() {
        ModConfig config = new ModConfig();
        NetworkClient networkClient = mock(NetworkClient.class);
        ChatInterceptor chatInterceptor = mock(ChatInterceptor.class);
        ChannelCommandService channelCommandService = mock(ChannelCommandService.class);
        KnownChannelRegistry knownChannelRegistry = mock(KnownChannelRegistry.class);

        ModServices services = new ModServices(
                config, networkClient, chatInterceptor, channelCommandService, knownChannelRegistry);

        assertThat(services.getConfig()).isSameAs(config);
        assertThat(services.getNetworkClient()).isSameAs(networkClient);
        assertThat(services.getChatInterceptor()).isSameAs(chatInterceptor);
        assertThat(services.getChannelCommandService()).isSameAs(channelCommandService);
        assertThat(services.getKnownChannelRegistry()).isSameAs(knownChannelRegistry);
    }

    @Test
    @DisplayName("getNetworkClient exposes the AbstractPlatformNetworkClient surface")
    void networkClientIsAbstractSurface() {
        NetworkClient networkClient = mock(NetworkClient.class);
        ModServices services = new ModServices(
                new ModConfig(), networkClient, mock(ChatInterceptor.class),
                mock(ChannelCommandService.class), mock(KnownChannelRegistry.class));

        // The getter is declared to return AbstractPlatformNetworkClient (the shared
        // surface); the concrete facade is a NetworkClient subclass.
        assertThat(services.getNetworkClient()).isSameAs(networkClient);
    }
}
