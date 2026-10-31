package com.nova.chat.pnx.command;

import org.powernukkitx.Player;
import org.powernukkitx.Server;
import org.powernukkitx.command.CommandSender;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.config.NovaChatConfig;
import com.nova.chat.pnx.network.NetworkClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shared fixture for the PNX moderation command tests.
 *
 * <p>Every moderation shell does the same three things — gate on the connection,
 * build one packet, report progress or failure — so the harness that captures
 * the packet and the messages is shared rather than repeated per test class.
 *
 * <p>PNX shells talk to the sender directly (there is no {@code MessageHelper}
 * on this platform), so the config snapshot they read for prefix/error/success
 * formats is what gets stubbed here.
 */
abstract class ModerationCommandTestSupport {

    private Locale previousDefaultLocale;

    @BeforeEach
    void initHarness() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
    }

    @AfterEach
    void closeHarness() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    /**
     * Wires a plugin mock to a connected, authenticated client, a server, and a
     * config snapshot whose formats reduce to the bare message, so the shell's
     * output lands on the mocked sender and can be asserted.
     */
    protected static void wireConnected(NovaChatPNX plugin, NetworkClient client, Server server) {
        when(plugin.getNetworkClient()).thenReturn(client);
        when(client.isConnected()).thenReturn(true);
        when(client.isAuthenticated()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);

        NovaChatConfig config = mock(NovaChatConfig.class);
        when(config.getFormatPrefix()).thenReturn("");
        when(config.getFormatError()).thenReturn("{message}");
        when(config.getFormatSuccess()).thenReturn("{message}");
        when(config.getDefaultChannel()).thenReturn("global");
        when(plugin.getNovaChatConfig()).thenReturn(config);
    }

    /** @return the last {@link Packet} handed to the client */
    protected static Packet sentPacket(NetworkClient client) {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        List<Packet> sent = captor.getAllValues();
        return sent.get(sent.size() - 1);
    }

    /** @return every message sent to the sender, in order */
    protected static List<String> messagesTo(CommandSender sender) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues();
    }

    /** Asserts some message sent to the sender contains {@code fragment}. */
    protected static void assertMessageContains(CommandSender sender, String fragment) {
        assertThat(messagesTo(sender))
                .as("expected a message containing '%s'", fragment)
                .anySatisfy(message -> assertThat(message).contains(fragment));
    }

    /** Asserts the shell reported the backend request failure (NC-503). */
    protected static void assertReportsRequestFailed(CommandSender sender) {
        assertMessageContains(sender, "NC-503");
    }

    protected static void verifyNoPacket(NetworkClient client) {
        verify(client, never()).sendPacket(any(Packet.class));
    }

    /** Registers one online player so target-name resolution can find it. */
    protected static void registerOnline(Server server, Player player, String name) {
        when(player.getName()).thenReturn(name);
        when(server.getPlayerExact(name)).thenReturn(player);
        when(server.getOnlinePlayers()).thenReturn(Map.of(player.getUniqueId(), player));
    }
}
