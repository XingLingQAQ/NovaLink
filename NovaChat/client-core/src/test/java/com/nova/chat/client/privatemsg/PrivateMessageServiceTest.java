package com.nova.chat.client.privatemsg;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PrivateMessageService}: send-side packet construction,
 * receive-side role resolution (echo vs received), ignore filtering, reply
 * tracking (both directions + logout cleanup) and backend-error rendering.
 */
@DisplayName("PrivateMessageService")
class PrivateMessageServiceTest {

    private static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ALEX = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NIL = new UUID(0L, 0L);

    private PrivateMessageService service;
    private Locale savedDefault;

    @BeforeEach
    void setUp() {
        service = new PrivateMessageService();
        savedDefault = I18n.getDefaultLocale();
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(savedDefault);
    }

    /** Completed (S->C) packet: Steve -> Alex. */
    private PrivateMessagePacket completed() {
        return new PrivateMessagePacket(STEVE, "Steve", "survival-01",
                "Alex", ALEX, "hello", 1755057712345L);
    }

    // ==================== send side ====================

    @Test
    @DisplayName("buildPacket produces the C->S form: nil targetId + sender fields")
    void buildPacketForm() {
        PrivateMessagePacket packet = PrivateMessageService.buildPacket(
                STEVE, "Steve", "survival-01", "Alex", "hello");

        assertThat(packet.getSenderId()).isEqualTo(STEVE);
        assertThat(packet.getSenderName()).isEqualTo("Steve");
        assertThat(packet.getSenderClientId()).isEqualTo("survival-01");
        assertThat(packet.getTargetName()).isEqualTo("Alex");
        assertThat(packet.getTargetId()).isEqualTo(NIL);
        assertThat(packet.getContent()).isEqualTo("hello");
        assertThat(packet.getRequestId()).isNotNull();
    }

    @Test
    @DisplayName("buildPacket tolerates a null senderClientId")
    void buildPacketNullClientId() {
        PrivateMessagePacket packet = PrivateMessageService.buildPacket(
                STEVE, "Steve", null, "Alex", "hello");
        assertThat(packet.getSenderClientId()).isEmpty();
    }

    // ==================== receive side: role resolution ====================

    @Test
    @DisplayName("local sender gets the echo line; non-local target renders nothing")
    void senderEchoOnly() {
        List<PrivateMessageService.Delivery> out =
                service.handleIncoming(completed(), Set.of(STEVE)::contains, null);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getPlayerId()).isEqualTo(STEVE);
        assertThat(out.get(0).getRole()).isEqualTo(PrivateMessageService.Role.ECHO);
        assertThat(out.get(0).getLine()).isEqualTo("&7你悄悄对 &eAlex &7说: &fhello");
    }

    @Test
    @DisplayName("local target gets the received line; non-local sender renders nothing")
    void targetReceivedOnly() {
        List<PrivateMessageService.Delivery> out =
                service.handleIncoming(completed(), Set.of(ALEX)::contains, null);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getPlayerId()).isEqualTo(ALEX);
        assertThat(out.get(0).getRole()).isEqualTo(PrivateMessageService.Role.RECEIVED);
        assertThat(out.get(0).getLine()).isEqualTo("&eSteve &7悄悄对你说: &fhello");
    }

    @Test
    @DisplayName("sender and target on the same server both render from one packet")
    void bothLocalRenderBothRoles() {
        List<PrivateMessageService.Delivery> out =
                service.handleIncoming(completed(), Set.of(STEVE, ALEX)::contains, null);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getRole()).isEqualTo(PrivateMessageService.Role.ECHO);
        assertThat(out.get(1).getRole()).isEqualTo(PrivateMessageService.Role.RECEIVED);
    }

    @Test
    @DisplayName("neither party local renders nothing")
    void neitherLocal() {
        assertThat(service.handleIncoming(completed(), id -> false, null)).isEmpty();
    }

    // ==================== receive side: ignore filter ====================

    @Test
    @DisplayName("receiver who ignores the sender gets nothing and keeps their old reply target")
    void ignoredSenderSuppressed() {
        IgnoreListService ignores = new IgnoreListService();
        ignores.ignore(ALEX, "Alex", "Steve");
        service.recordConversation(ALEX, "Bob"); // pre-existing conversation

        List<PrivateMessageService.Delivery> out =
                service.handleIncoming(completed(), Set.of(ALEX)::contains, ignores);

        assertThat(out).isEmpty();
        assertThat(service.getReplyTarget(ALEX)).contains("Bob");
    }

    @Test
    @DisplayName("ignore filter does not suppress the sender-side echo")
    void ignoreDoesNotAffectEcho() {
        IgnoreListService ignores = new IgnoreListService();
        ignores.ignore(ALEX, "Alex", "Steve");

        List<PrivateMessageService.Delivery> out =
                service.handleIncoming(completed(), Set.of(STEVE, ALEX)::contains, ignores);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getRole()).isEqualTo(PrivateMessageService.Role.ECHO);
    }

    // ==================== reply tracking ====================

    @Test
    @DisplayName("echo updates the sender's reply target; delivery updates the receiver's")
    void replyTargetsUpdateOnBothRoles() {
        service.handleIncoming(completed(), Set.of(STEVE, ALEX)::contains, null);

        assertThat(service.getReplyTarget(STEVE)).contains("Alex");
        assertThat(service.getReplyTarget(ALEX)).contains("Steve");
    }

    @Test
    @DisplayName("the most recent conversation wins")
    void mostRecentConversationWins() {
        service.recordConversation(STEVE, "Alex");
        service.recordConversation(STEVE, "Bob");
        assertThat(service.getReplyTarget(STEVE)).contains("Bob");
    }

    @Test
    @DisplayName("no conversation yet -> empty reply target")
    void emptyReplyTarget() {
        assertThat(service.getReplyTarget(STEVE)).isEmpty();
        assertThat(service.getReplyTarget(null)).isEmpty();
    }

    @Test
    @DisplayName("onPlayerQuit clears the player's reply target")
    void quitClearsReplyTarget() {
        service.recordConversation(STEVE, "Alex");
        service.onPlayerQuit(STEVE);
        assertThat(service.getReplyTarget(STEVE)).isEmpty();
    }

    // ==================== error side ====================

    private ChannelActionResponsePacket error(String detail) {
        ChannelActionResponsePacket packet = new ChannelActionResponsePacket(
                false, null, "", "NC-404", "backend text");
        packet.addExtra("reason", "private_message");
        packet.addExtra("detail", detail);
        packet.addExtra("senderId", STEVE.toString());
        packet.addExtra("targetName", "Alex");
        return packet;
    }

    @Test
    @DisplayName("isPrivateMessageError keys off reason=private_message on failures")
    void errorDetection() {
        assertThat(PrivateMessageService.isPrivateMessageError(error("not_online"))).isTrue();

        ChannelActionResponsePacket other = new ChannelActionResponsePacket(
                false, null, "", "NC-404", "x");
        assertThat(PrivateMessageService.isPrivateMessageError(other)).isFalse();

        ChannelActionResponsePacket success = new ChannelActionResponsePacket(
                true, null, "", "", "");
        success.addExtra("reason", "private_message");
        assertThat(PrivateMessageService.isPrivateMessageError(success)).isFalse();
    }

    @Test
    @DisplayName("error details map to the localized chat.msg.* lines")
    void errorDetailRendering() {
        assertThat(service.renderError(error("not_online"), id -> true))
                .map(PrivateMessageService.Delivery::getLine)
                .contains("&c玩家 &eAlex &c不在线");
        assertThat(service.renderError(error("self"), id -> true))
                .map(PrivateMessageService.Delivery::getLine)
                .contains("&c不能给自己发送私聊");
        assertThat(service.renderError(error("disabled"), id -> true))
                .map(PrivateMessageService.Delivery::getLine)
                .contains("&c服务器已关闭私聊功能");
        assertThat(service.renderError(error("muted"), id -> true))
                .map(PrivateMessageService.Delivery::getLine)
                .contains("&c你已被全局禁言，无法发送私聊");
        assertThat(service.renderError(error("rate_limited"), id -> true))
                .map(PrivateMessageService.Delivery::getLine)
                .contains("&c私聊发送过快，请稍后再试");
    }

    @Test
    @DisplayName("error delivery targets the sender with the ERROR role")
    void errorDeliveryShape() {
        PrivateMessageService.Delivery delivery =
                service.renderError(error("not_online"), id -> true).orElseThrow();
        assertThat(delivery.getPlayerId()).isEqualTo(STEVE);
        assertThat(delivery.getRole()).isEqualTo(PrivateMessageService.Role.ERROR);
    }

    @Test
    @DisplayName("unknown detail falls back to the generic error formatter")
    void unknownDetailFallsBack() {
        PrivateMessageService.Delivery delivery =
                service.renderError(error("mystery"), id -> true).orElseThrow();
        assertThat(delivery.getLine()).contains("NC-404");
    }

    @Test
    @DisplayName("non-local or unresolvable sender renders nothing")
    void errorForNonLocalSender() {
        assertThat(service.renderError(error("not_online"), id -> false)).isEmpty();

        ChannelActionResponsePacket noSender = error("not_online");
        noSender.addExtra("senderId", "not-a-uuid");
        assertThat(service.renderError(noSender, id -> true)).isEmpty();
    }
}
