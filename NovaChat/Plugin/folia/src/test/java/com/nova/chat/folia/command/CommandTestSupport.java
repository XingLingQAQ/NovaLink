package com.nova.chat.folia.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.network.AsyncNetworkClient;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shared fixture for the Folia command-shell tests.
 *
 * <p>Every moderation shell does the same three things — gate on the connection,
 * build one packet, report progress or failure — so the harness that captures
 * the packet and the messages is shared rather than repeated per test class.
 */
abstract class CommandTestSupport {

    /** Static {@code Bukkit.getPlayer/getOnlinePlayers} registry mock. */
    protected MockedStatic<org.bukkit.Bukkit> bukkit;

    private Locale previousDefaultLocale;

    @BeforeEach
    void initHarness() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        bukkit = Mockito.mockStatic(org.bukkit.Bukkit.class);
    }

    @AfterEach
    void closeHarness() {
        if (bukkit != null) {
            bukkit.close();
        }
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    /**
     * Wires a plugin mock to a connected, authenticated network client and a real
     * {@link MessageHelper}, so the shell's user-facing output lands on the
     * mocked sender and can be asserted.
     */
    protected static void wireConnected(NovaChatFolia plugin, AsyncNetworkClient client) {
        when(plugin.getNetworkClient()).thenReturn(client);
        when(plugin.getMessageHelper()).thenReturn(new MessageHelper(plugin));
        when(client.isConnected()).thenReturn(true);
        when(client.isAuthenticated()).thenReturn(true);
    }

    /** @return the last {@link Packet} handed to the client */
    protected static Packet sentPacket(AsyncNetworkClient client) {
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

    protected static void verifyNoPacket(AsyncNetworkClient client) {
        verify(client, Mockito.never()).sendPacket(Mockito.any(Packet.class));
    }

    protected static void verifyAnyPacket(AsyncNetworkClient client) {
        verify(client, atLeastOnce()).sendPacket(Mockito.any(Packet.class));
    }
}
