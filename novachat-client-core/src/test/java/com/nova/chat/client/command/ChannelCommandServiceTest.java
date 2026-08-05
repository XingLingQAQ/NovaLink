package com.nova.chat.client.command;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChannelCommandService")
@ExtendWith(MockitoExtension.class)
class ChannelCommandServiceTest {

    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Mock
    private PacketSender packetSender;

    private ChannelCommandService service;
    private PlayerChannelState state;

    @BeforeEach
    void setUp() {
        service = new ChannelCommandService(packetSender);
        state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("rejects null PacketSender")
        void rejectsNullSender() {
            assertThatThrownBy(() -> new ChannelCommandService(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("packetSender");
        }
    }

    @Nested
    @DisplayName("join")
    class Join {

        @Test
        @DisplayName("sends JOIN packet, updates active channel, and returns success")
        void happyPath() {
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(true);

            CommandResult result = service.join(state, "trade", "secret", "Steve");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getIntent()).isEqualTo(CommandIntent.JOIN);
            assertThat(result.getMessage()).contains("trade");
            assertThat(state.getActiveChannel()).isEqualTo("trade");
            assertThat(state.isJoined("trade")).isTrue();
            assertThat(state.isJoined("global")).isTrue();

            ArgumentCaptor<ChannelActionPacket> captor =
                    ArgumentCaptor.forClass(ChannelActionPacket.class);
            verify(packetSender).send(captor.capture());
            ChannelActionPacket packet = captor.getValue();
            assertThat(packet.getAction()).isEqualTo(ChannelAction.JOIN);
            assertThat(packet.getChannelId()).isEqualTo("trade");
            assertThat(packet.getPassword()).isEqualTo("secret");
            assertThat(packet.getExtra("playerId")).isEqualTo(PLAYER.toString());
            assertThat(packet.getExtra("playerName")).isEqualTo("Steve");
        }

        @Test
        @DisplayName("null password becomes empty string on packet")
        void nullPassword() {
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(true);

            CommandResult result = service.join(state, "trade", null, null);

            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<ChannelActionPacket> captor =
                    ArgumentCaptor.forClass(ChannelActionPacket.class);
            verify(packetSender).send(captor.capture());
            assertThat(captor.getValue().getPassword()).isEmpty();
            assertThat(captor.getValue().getExtra("playerId")).isEqualTo(PLAYER.toString());
            assertThat(captor.getValue().getExtra()).doesNotContainKey("playerName");
        }

        @Test
        @DisplayName("blank playerName is omitted from extras")
        void blankPlayerNameOmitted() {
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(true);

            service.join(state, "trade", "", "  ");

            ArgumentCaptor<ChannelActionPacket> captor =
                    ArgumentCaptor.forClass(ChannelActionPacket.class);
            verify(packetSender).send(captor.capture());
            assertThat(captor.getValue().getExtra()).doesNotContainKey("playerName");
        }

        @Test
        @DisplayName("carries world extra when provided")
        void carriesWorld() {
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(true);

            CommandResult result = service.join(state, "trade", null, "Steve", "world_nether");

            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<ChannelActionPacket> captor =
                    ArgumentCaptor.forClass(ChannelActionPacket.class);
            verify(packetSender).send(captor.capture());
            assertThat(captor.getValue().getExtra("world")).isEqualTo("world_nether");
        }

        @Test
        @DisplayName("omits world extra when null/blank (backward compatible)")
        void omitsWorldWhenNull() {
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(true);

            service.join(state, "trade", null, "Steve");

            ArgumentCaptor<ChannelActionPacket> captor =
                    ArgumentCaptor.forClass(ChannelActionPacket.class);
            verify(packetSender).send(captor.capture());
            assertThat(captor.getValue().getExtra()).doesNotContainKey("world");
        }

        @Test
        @DisplayName("convenience overload joins without password/name")
        void convenienceOverload() {
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(true);

            CommandResult result = service.join(state, "staff");

            assertThat(result.isSuccess()).isTrue();
            assertThat(state.getActiveChannel()).isEqualTo("staff");
            ArgumentCaptor<ChannelActionPacket> captor =
                    ArgumentCaptor.forClass(ChannelActionPacket.class);
            verify(packetSender).send(captor.capture());
            assertThat(captor.getValue().getPassword()).isEmpty();
            assertThat(captor.getValue().getExtra()).doesNotContainKey("playerName");
        }

        @Test
        @DisplayName("does not mutate state when send fails")
        void sendFailureLeavesState() {
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(false);

            CommandResult result = service.join(state, "trade", null, "Steve");

            assertThat(result.isFailure()).isTrue();
            assertThat(result.getIntent()).isEqualTo(CommandIntent.JOIN);
            assertThat(result.getMessage()).contains("Failed to send JOIN");
            assertThat(state.getActiveChannel()).isEqualTo("global");
            assertThat(state.isJoined("trade")).isFalse();
        }

        @Test
        @DisplayName("rejects blank channelId without sending")
        void rejectsBlankChannel() {
            CommandResult result = service.join(state, "  ", null, null);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.getIntent()).isEqualTo(CommandIntent.JOIN);
            assertThat(result.getMessage()).contains("channelId");
            verify(packetSender, never()).send(any());
            assertThat(state.getActiveChannel()).isEqualTo("global");
        }

        @Test
        @DisplayName("rejects null channelId without sending")
        void rejectsNullChannel() {
            CommandResult result = service.join(state, null);

            assertThat(result.isFailure()).isTrue();
            verify(packetSender, never()).send(any());
        }

        @Test
        @DisplayName("rejects null state")
        void rejectsNullState() {
            assertThatThrownBy(() -> service.join(null, "trade"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("state");
        }
    }

    @Nested
    @DisplayName("leave")
    class Leave {

        @Test
        @DisplayName("sends LEAVE packet, removes membership, reassigns active")
        void happyPath() {
            state.joinChannel("trade");
            state.setActiveChannel("trade");
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(true);

            CommandResult result = service.leave(state, "trade", "Alex");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getIntent()).isEqualTo(CommandIntent.LEAVE);
            assertThat(result.getMessage()).contains("trade");
            assertThat(state.isJoined("trade")).isFalse();
            assertThat(state.getActiveChannel()).isEqualTo("global");

            ArgumentCaptor<ChannelActionPacket> captor =
                    ArgumentCaptor.forClass(ChannelActionPacket.class);
            verify(packetSender).send(captor.capture());
            ChannelActionPacket packet = captor.getValue();
            assertThat(packet.getAction()).isEqualTo(ChannelAction.LEAVE);
            assertThat(packet.getChannelId()).isEqualTo("trade");
            assertThat(packet.getExtra("playerId")).isEqualTo(PLAYER.toString());
            assertThat(packet.getExtra("playerName")).isEqualTo("Alex");
        }

        @Test
        @DisplayName("null/blank channelId leaves the active channel")
        void leaveActiveWhenNull() {
            state.setActiveChannel("trade");
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(true);

            CommandResult result = service.leave(state, null, null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(state.isJoined("trade")).isFalse();
            ArgumentCaptor<ChannelActionPacket> captor =
                    ArgumentCaptor.forClass(ChannelActionPacket.class);
            verify(packetSender).send(captor.capture());
            assertThat(captor.getValue().getChannelId()).isEqualTo("trade");
            assertThat(captor.getValue().getExtra()).doesNotContainKey("playerName");
        }

        @Test
        @DisplayName("blank channelId also leaves the active channel")
        void leaveActiveWhenBlank() {
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(true);

            CommandResult result = service.leave(state, "  ");

            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<ChannelActionPacket> captor =
                    ArgumentCaptor.forClass(ChannelActionPacket.class);
            verify(packetSender).send(captor.capture());
            assertThat(captor.getValue().getChannelId()).isEqualTo("global");
            assertThat(state.getJoinedChannels()).isEmpty();
            assertThat(state.getActiveChannel()).isNull();
        }

        @Test
        @DisplayName("fails when no active channel and channelId blank")
        void failsWhenNotInChannel() {
            state.leaveChannel("global");
            assertThat(state.getActiveChannel()).isNull();

            CommandResult result = service.leave(state, null);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.getIntent()).isEqualTo(CommandIntent.LEAVE);
            assertThat(result.getMessage()).contains("Not in a channel");
            verify(packetSender, never()).send(any());
        }

        @Test
        @DisplayName("does not mutate state when send fails")
        void sendFailureLeavesState() {
            state.setActiveChannel("trade");
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(false);

            CommandResult result = service.leave(state, "trade", "Alex");

            assertThat(result.isFailure()).isTrue();
            assertThat(result.getMessage()).contains("Failed to send LEAVE");
            assertThat(state.isJoined("trade")).isTrue();
            assertThat(state.getActiveChannel()).isEqualTo("trade");
        }

        @Test
        @DisplayName("leaving the default removes membership and list renders it unjoined")
        void leaveDefaultKeepsListConsistentWithBackend() {
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(true);

            CommandResult result = service.leave(state, "global", "Alex");

            assertThat(result.isSuccess()).isTrue();
            assertThat(state.getJoinedChannels()).isEmpty();
            assertThat(state.getActiveChannel()).isNull();

            com.nova.chat.client.channel.KnownChannelRegistry registry =
                    new com.nova.chat.client.channel.KnownChannelRegistry();
            registry.addAll(java.util.Set.of("global"));
            List<String> lines = ListCommandService.formatChannelList(
                    registry, state.getJoinedChannels());
            assertThat(lines).singleElement()
                    .satisfies(line -> assertThat(line).contains("○").contains("global").doesNotContain("✓"));
        }

        @Test
        @DisplayName("second leave is rejected locally without sending another packet")
        void secondLeaveShortCircuitsLocally() {
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(true);

            CommandResult first = service.leave(state, "global", "Alex");
            CommandResult second = service.leave(state, "global", "Alex");

            assertThat(first.isSuccess()).isTrue();
            assertThat(second.isFailure()).isTrue();
            assertThat(second.getErrorCode()).isEqualTo("NC-433");
            assertThat(state.getJoinedChannels()).isEmpty();
            assertThat(state.getActiveChannel()).isNull();
            verify(packetSender, times(1)).send(any(ChannelActionPacket.class));
        }

        @Test
        @DisplayName("specified unjoined channel is rejected without changing optimistic state")
        void unjoinedTargetShortCircuitsLocally() {
            CommandResult result = service.leave(state, "trade", "Alex");

            assertThat(result.isFailure()).isTrue();
            assertThat(result.getErrorCode()).isEqualTo("NC-433");
            assertThat(state.getJoinedChannels()).containsExactly("global");
            assertThat(state.getActiveChannel()).isEqualTo("global");
            verify(packetSender, never()).send(any());
        }

        @Test
        @DisplayName("leaving active non-default prefers default only when it is still joined")
        void activeNonDefaultCanPreferJoinedDefaultWithoutCreatingMembership() {
            state.setActiveChannel("trade");
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(true);

            CommandResult result = service.leave(state, "trade", "Alex");
            boolean selectedDefault = state.setActiveChannelIfJoined("global");

            assertThat(result.isSuccess()).isTrue();
            assertThat(selectedDefault).isTrue();
            assertThat(state.getActiveChannel()).isEqualTo("global");
            assertThat(state.getJoinedChannels()).containsExactly("global");
        }

        @Test
        @DisplayName("leaving a non-active channel keeps active unchanged")
        void leaveNonActive() {
            state.joinChannel("trade");
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(true);

            CommandResult result = service.leave(state, "trade");

            assertThat(result.isSuccess()).isTrue();
            assertThat(state.getActiveChannel()).isEqualTo("global");
            assertThat(state.isJoined("trade")).isFalse();
        }

        @Test
        @DisplayName("rejects null state")
        void rejectsNullState() {
            assertThatThrownBy(() -> service.leave(null, "global"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("state");
        }
    }

    @Nested
    @DisplayName("toggle")
    class Toggle {

        @Test
        @DisplayName("flips HYBRID to REPLACE without sending a packet")
        void hybridToReplace() {
            CommandResult result = service.toggle(state);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getIntent()).isEqualTo(CommandIntent.TOGGLE);
            assertThat(result.getMessage()).contains("REPLACE");
            assertThat(state.getChatMode()).isEqualTo(ChatMode.REPLACE);
            assertThat(state.isModeOverridden()).isTrue();
            verify(packetSender, never()).send(any());
        }

        @Test
        @DisplayName("flips REPLACE to HYBRID")
        void replaceToHybrid() {
            state.setChatMode(ChatMode.REPLACE);

            CommandResult result = service.toggle(state);

            assertThat(result.isSuccess()).isTrue();
            assertThat(state.getChatMode()).isEqualTo(ChatMode.HYBRID);
            verify(packetSender, never()).send(any());
        }

        @Test
        @DisplayName("double toggle restores original mode and keeps override flag")
        void doubleToggle() {
            ChatMode original = state.getChatMode();
            service.toggle(state);
            service.toggle(state);

            assertThat(state.getChatMode()).isEqualTo(original);
            assertThat(state.isModeOverridden()).isTrue();
            verify(packetSender, never()).send(any());
        }

        @Test
        @DisplayName("rejects null state")
        void rejectsNullState() {
            assertThatThrownBy(() -> service.toggle(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("state");
        }
    }

    @Nested
    @DisplayName("reload")
    class Reload {

        @Test
        @DisplayName("returns RELOAD intent success without sending or mutating state")
        void noOpForPlatform() {
            String activeBefore = state.getActiveChannel();
            ChatMode modeBefore = state.getChatMode();
            int joinedBefore = state.getJoinedChannelCount();

            CommandResult result = service.reload();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getIntent()).isEqualTo(CommandIntent.RELOAD);
            assertThat(result.getMessage()).containsIgnoringCase("platform");
            assertThat(state.getActiveChannel()).isEqualTo(activeBefore);
            assertThat(state.getChatMode()).isEqualTo(modeBefore);
            assertThat(state.getJoinedChannelCount()).isEqualTo(joinedBefore);
            verify(packetSender, never()).send(any());
        }

        @Test
        @DisplayName("is idempotent and never touches PacketSender")
        void idempotent() {
            CommandResult first = service.reload();
            CommandResult second = service.reload();

            assertThat(first).isEqualTo(second);
            verify(packetSender, never()).send(any());
        }
    }

    @Nested
    @DisplayName("PacketSender integration")
    class PacketSenderIntegration {

        @Test
        @DisplayName("recording sender captures ordered join then leave packets")
        void recordingSenderOrder() {
            List<ChannelActionPacket> sent = new ArrayList<>();
            PacketSender recorder = packet -> {
                sent.add(packet);
                return true;
            };
            ChannelCommandService local = new ChannelCommandService(recorder);

            local.join(state, "trade", null, "Steve");
            local.leave(state, "trade", "Steve");

            assertThat(sent).hasSize(2);
            assertThat(sent.get(0).getAction()).isEqualTo(ChannelAction.JOIN);
            assertThat(sent.get(0).getChannelId()).isEqualTo("trade");
            assertThat(sent.get(1).getAction()).isEqualTo(ChannelAction.LEAVE);
            assertThat(sent.get(1).getChannelId()).isEqualTo("trade");
        }

        @Test
        @DisplayName("failing sender prevents optimistic state changes across intents")
        void failingSender() {
            AtomicBoolean allow = new AtomicBoolean(false);
            PacketSender gated = packet -> allow.get();
            ChannelCommandService local = new ChannelCommandService(gated);

            assertThat(local.join(state, "trade").isFailure()).isTrue();
            assertThat(state.isJoined("trade")).isFalse();

            assertThat(local.leave(state, "global").isFailure()).isTrue();
            assertThat(state.isJoined("global")).isTrue();

            // toggle/reload never consult the sender
            assertThat(local.toggle(state).isSuccess()).isTrue();
            assertThat(local.reload().isSuccess()).isTrue();
            assertThat(state.getChatMode()).isEqualTo(ChatMode.REPLACE);
        }

        @Test
        @DisplayName("mock verifies send is invoked exactly once per successful join")
        void mockInvocationCount() {
            when(packetSender.send(any(ChannelActionPacket.class))).thenReturn(true);

            service.join(state, "a");
            service.join(state, "b");

            verify(packetSender, times(2)).send(any(ChannelActionPacket.class));
        }
    }

    @Nested
    @DisplayName("CommandResult / CommandIntent")
    class ResultAndIntent {

        @Test
        @DisplayName("CommandIntent exposes the four minimal values")
        void intentValues() {
            assertThat(CommandIntent.values()).containsExactly(
                    CommandIntent.JOIN,
                    CommandIntent.LEAVE,
                    CommandIntent.TOGGLE,
                    CommandIntent.RELOAD
            );
        }

        @Test
        @DisplayName("CommandResult success/failure factories set flags and fields")
        void resultFactories() {
            CommandResult ok = CommandResult.success(CommandIntent.JOIN, "joined");
            assertThat(ok.isSuccess()).isTrue();
            assertThat(ok.isFailure()).isFalse();
            assertThat(ok.getIntent()).isEqualTo(CommandIntent.JOIN);
            assertThat(ok.getMessage()).isEqualTo("joined");

            CommandResult fail = CommandResult.failure(CommandIntent.LEAVE, "nope");
            assertThat(fail.isSuccess()).isFalse();
            assertThat(fail.isFailure()).isTrue();
            assertThat(fail.getIntent()).isEqualTo(CommandIntent.LEAVE);
            assertThat(fail.getMessage()).isEqualTo("nope");
        }

        @Test
        @DisplayName("CommandResult null message becomes empty string")
        void nullMessageBecomesEmpty() {
            CommandResult result = CommandResult.success(CommandIntent.RELOAD, null);
            assertThat(result.getMessage()).isEmpty();
        }

        @Test
        @DisplayName("CommandResult rejects null intent")
        void rejectsNullIntent() {
            assertThatThrownBy(() -> CommandResult.success(null, "x"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("intent");
            assertThatThrownBy(() -> CommandResult.failure(null, "x"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("CommandResult equality is value-based")
        void equality() {
            CommandResult a = CommandResult.success(CommandIntent.TOGGLE, "m");
            CommandResult b = CommandResult.success(CommandIntent.TOGGLE, "m");
            CommandResult c = CommandResult.failure(CommandIntent.TOGGLE, "m");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
            assertThat(a.toString()).contains("TOGGLE").contains("success=true");
        }
    }
}
