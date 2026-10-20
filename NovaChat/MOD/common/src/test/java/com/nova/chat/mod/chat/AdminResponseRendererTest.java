package com.nova.chat.mod.chat;

import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.mod.platform.Platform;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AdminResponseRenderer}, the mod counterpart of the
 * Folia {@code AdminActionResponseHandler}.
 *
 * <p>Without this renderer {@code /nc auth}, {@code /nc announce} and
 * {@code /nc title} send their request and report nothing — the operator cannot
 * tell whether the super-admin session was established or why a broadcast was
 * rejected. These tests pin the four branches: success, super-admin-required,
 * generic error, and unsolicited.
 */
@DisplayName("AdminResponseRenderer")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminResponseRendererTest {

    private static final UUID OPERATOR = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID REQUEST = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID CONSOLE =
            UUID.fromString(NovaConstants.CONSOLE_SENTINEL_UUID);

    @Mock
    private Platform platform;

    private final List<String> sentMessages = new ArrayList<>();
    private final List<String> consoleLines = new ArrayList<>();
    private final List<UUID> consumedRequests = new ArrayList<>();
    private UUID pendingSender;

    private AdminResponseRenderer renderer;

    @BeforeEach
    void setUp() {
        sentMessages.clear();
        consoleLines.clear();
        consumedRequests.clear();
        pendingSender = OPERATOR;
        doAnswer(inv -> {
            sentMessages.add((String) inv.getArgument(1));
            return null;
        }).when(platform).sendMessage(any(UUID.class), anyString());
        renderer = new AdminResponseRenderer(platform, requestId -> {
            consumedRequests.add(requestId);
            return pendingSender;
        }, consoleLines::add);
    }

    private static AdminActionResponsePacket response(AdminAction action, boolean success,
                                                      String errorCode, String message) {
        AdminActionResponsePacket packet = new AdminActionResponsePacket(REQUEST);
        packet.setAction(action);
        packet.setSuccess(success);
        packet.setErrorCode(errorCode);
        packet.setMessage(message);
        return packet;
    }

    @Test
    @DisplayName("a successful response renders the backend message to the sender")
    void rendersSuccessMessage() {
        renderer.accept(response(AdminAction.AUTH, true, "", "Super admin authentication successful"));

        assertThat(consumedRequests).containsExactly(REQUEST);
        assertThat(sentMessages)
                .containsExactly("Super admin authentication successful");
    }

    @Test
    @DisplayName("a successful response without a message falls back to the shared success copy")
    void rendersSuccessFallback() {
        renderer.accept(response(AdminAction.STATUS, true, "", ""));

        assertThat(sentMessages).hasSize(1);
        assertThat(sentMessages.get(0)).isNotBlank();
    }

    @Test
    @DisplayName("NC-403 on STATUS renders the super-admin-session guidance")
    void rendersSuperAdminGuidance() {
        renderer.accept(response(AdminAction.STATUS, false, "NC-403",
                "Super admin authentication required for status"));

        assertThat(sentMessages).hasSize(1);
        // the guidance replaces the raw backend text: it tells the operator to
        // run /nc auth, which is the actionable part
        assertThat(sentMessages.get(0)).contains("/nc auth");
    }

    @Test
    @DisplayName("a generic error renders the shared error text with the backend message")
    void rendersGenericError() {
        renderer.accept(response(AdminAction.STATUS, false, "NC-404",
                "Channel not found: trade"));

        assertThat(sentMessages).hasSize(1);
        assertThat(sentMessages.get(0)).contains("NC-404").contains("Channel not found: trade");
    }

    @Test
    @DisplayName("an unknown error code keeps the raw code visible")
    void rendersUnknownErrorCode() {
        renderer.accept(response(AdminAction.STATUS, false, "NC-999", "odd failure"));

        assertThat(sentMessages).hasSize(1);
        assertThat(sentMessages.get(0)).contains("NC-999").contains("odd failure");
    }

    @Test
    @DisplayName("an NC-403 on a non-STATUS action renders as a generic error")
    void nonStatusForbiddenIsGeneric() {
        renderer.accept(response(AdminAction.AUTH, false, "NC-403", "bad password"));

        assertThat(sentMessages).hasSize(1);
        assertThat(sentMessages.get(0)).doesNotContain("/nc auth");
    }

    @Test
    @DisplayName("an untracked response (no pending request) renders nothing")
    void unsolicitedResponseDropped() {
        pendingSender = null;

        renderer.accept(response(AdminAction.STATUS, true, "", "ok"));

        verify(platform, never()).sendMessage(any(), anyString());
        assertThat(consoleLines).isEmpty();
    }

    @Test
    @DisplayName("a console-sentinel response goes to the console sink, not a player")
    void consoleSenderRoutedToSink() {
        pendingSender = CONSOLE;

        renderer.accept(response(AdminAction.STATUS, false, "NC-403", "no session"));

        verify(platform, never()).sendMessage(any(), anyString());
        assertThat(consoleLines).hasSize(1);
        assertThat(consoleLines.get(0)).contains("/nc auth");
    }

    @Test
    @DisplayName("a null response is ignored")
    void nullResponseIgnored() {
        renderer.accept(null);

        verify(platform, never()).sendMessage(any(), anyString());
        assertThat(consumedRequests).isEmpty();
    }
}
