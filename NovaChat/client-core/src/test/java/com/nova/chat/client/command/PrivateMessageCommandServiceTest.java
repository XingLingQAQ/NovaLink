package com.nova.chat.client.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PrivateMessageCommandService}: argument validation,
 * usage/self/not-connected receipts, packet construction and the reply-target
 * chain through {@link PrivateMessageService}.
 */
@DisplayName("PrivateMessageCommandService")
class PrivateMessageCommandServiceTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private PrivateMessageService service;
    private List<PrivateMessagePacket> sent;
    private PrivateMessageCommandService.PrivateMessagePacketSender okSender;
    private Locale savedDefault;

    @BeforeEach
    void setUp() {
        service = new PrivateMessageService();
        sent = new ArrayList<>();
        okSender = packet -> {
            sent.add(packet);
            return true;
        };
        savedDefault = I18n.getDefaultLocale();
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(savedDefault);
    }

    @Test
    @DisplayName("msg: missing target or message renders the usage line")
    void msgUsage() {
        assertThat(PrivateMessageCommandService.msg(okSender, PLAYER, "Steve", "s1", null))
                .containsExactly("&7用法: /nc msg <玩家名> <消息>");
        assertThat(PrivateMessageCommandService.msg(okSender, PLAYER, "Steve", "s1", new String[0]))
                .hasSize(1).first().asString().contains("用法");
        assertThat(PrivateMessageCommandService.msg(okSender, PLAYER, "Steve", "s1",
                new String[]{"Alex"}))
                .hasSize(1).first().asString().contains("用法");
        assertThat(PrivateMessageCommandService.msg(okSender, PLAYER, "Steve", "s1",
                new String[]{"  ", "hello"}))
                .hasSize(1).first().asString().contains("用法");
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("msg: whispering yourself is rejected locally (case-insensitive)")
    void msgSelfRejected() {
        assertThat(PrivateMessageCommandService.msg(okSender, PLAYER, "Steve", "s1",
                new String[]{"sTeVe", "hello"}))
                .containsExactly("&c不能给自己发送私聊");
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("msg: accepted send returns no line and transmits the C->S packet")
    void msgTransmits() {
        List<String> lines = PrivateMessageCommandService.msg(okSender, PLAYER, "Steve", "s1",
                new String[]{"Alex", "hello", "over", "there"});

        assertThat(lines).isEmpty(); // echo confirms later
        assertThat(sent).hasSize(1);
        PrivateMessagePacket packet = sent.get(0);
        assertThat(packet.getSenderId()).isEqualTo(PLAYER);
        assertThat(packet.getSenderName()).isEqualTo("Steve");
        assertThat(packet.getSenderClientId()).isEqualTo("s1");
        assertThat(packet.getTargetName()).isEqualTo("Alex");
        assertThat(packet.getTargetId()).isEqualTo(new UUID(0L, 0L));
        assertThat(packet.getContent()).isEqualTo("hello over there");
    }

    @Test
    @DisplayName("msg: refused send renders the not-connected line")
    void msgNotConnected() {
        assertThat(PrivateMessageCommandService.msg(packet -> false, PLAYER, "Steve", "s1",
                new String[]{"Alex", "hello"}))
                .containsExactly("&c未连接到聊天服务器，请稍后再试");
    }

    @Test
    @DisplayName("reply: missing message renders the reply usage line")
    void replyUsage() {
        assertThat(PrivateMessageCommandService.reply(service, okSender, PLAYER, "Steve", "s1",
                new String[0]))
                .containsExactly("&7用法: /nc r <消息>");
        assertThat(PrivateMessageCommandService.reply(service, okSender, PLAYER, "Steve", "s1",
                null))
                .hasSize(1).first().asString().contains("用法");
    }

    @Test
    @DisplayName("reply: no prior conversation renders the no-target line")
    void replyNoTarget() {
        assertThat(PrivateMessageCommandService.reply(service, okSender, PLAYER, "Steve", "s1",
                new String[]{"hello"}))
                .containsExactly("&c最近没有私聊往来，无法回复（先使用 /nc msg <玩家名> <消息>）");
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("reply: transmits to the tracked partner")
    void replyTransmits() {
        service.recordConversation(PLAYER, "Alex");

        List<String> lines = PrivateMessageCommandService.reply(service, okSender, PLAYER,
                "Steve", "s1", new String[]{"hello", "again"});

        assertThat(lines).isEmpty();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getTargetName()).isEqualTo("Alex");
        assertThat(sent.get(0).getContent()).isEqualTo("hello again");
    }

    @Test
    @DisplayName("reply: refused send renders the not-connected line")
    void replyNotConnected() {
        service.recordConversation(PLAYER, "Alex");
        assertThat(PrivateMessageCommandService.reply(service, packet -> false, PLAYER, "Steve",
                "s1", new String[]{"hello"}))
                .hasSize(1).first().asString().contains("未连接");
    }
}
