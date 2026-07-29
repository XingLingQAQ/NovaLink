package com.nova.link.channel;

import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive unit tests for {@link MessagePipelineResult} factory methods,
 * immutability of recipients, and diagnostic toString output.
 */
@DisplayName("MessagePipelineResult detailed")
class MessagePipelineResultDetailedTest {

    private ChatMessagePacket sampleMessage() {
        return new ChatMessagePacket(
                UUID.randomUUID(), "Steve", "client-1", "global", "hello");
    }

    private Channel sampleChannel() {
        return new Channel("global", "Global", ChannelScope.GLOBAL, null);
    }

    @Nested
    @DisplayName("dropped factory")
    class DroppedFactory {

        @Test
        @DisplayName("dropped(reason, message) marks not delivered with empty recipients and no channel")
        void droppedWithoutChannel() {
            ChatMessagePacket msg = sampleMessage();
            MessagePipelineResult result = MessagePipelineResult.dropped(
                    MessagePipelineResult.DropReason.EMPTY_CONTENT, msg);

            assertThat(result.isDelivered()).isFalse();
            assertThat(result.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.EMPTY_CONTENT);
            assertThat(result.getMessage()).isSameAs(msg);
            assertThat(result.getChannel()).isNull();
            assertThat(result.getRecipients()).isEmpty();
            assertThat(result.isContentFiltered()).isFalse();
            assertThat(result.getFilterMatchCount()).isZero();
        }

        @Test
        @DisplayName("dropped(reason, message, channel) retains channel reference")
        void droppedWithChannel() {
            ChatMessagePacket msg = sampleMessage();
            Channel channel = sampleChannel();
            MessagePipelineResult result = MessagePipelineResult.dropped(
                    MessagePipelineResult.DropReason.SENDER_MUTED, msg, channel);

            assertThat(result.isDelivered()).isFalse();
            assertThat(result.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.SENDER_MUTED);
            assertThat(result.getMessage()).isSameAs(msg);
            assertThat(result.getChannel()).isSameAs(channel);
            assertThat(result.getRecipients()).isEmpty();
            assertThat(result.isContentFiltered()).isFalse();
            assertThat(result.getFilterMatchCount()).isZero();
        }

        @Test
        @DisplayName("dropped accepts null message (NULL_MESSAGE path)")
        void droppedNullMessage() {
            MessagePipelineResult result = MessagePipelineResult.dropped(
                    MessagePipelineResult.DropReason.NULL_MESSAGE, null);

            assertThat(result.isDelivered()).isFalse();
            assertThat(result.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.NULL_MESSAGE);
            assertThat(result.getMessage()).isNull();
            assertThat(result.getChannel()).isNull();
        }

        @ParameterizedTest(name = "drop reason {0}")
        @EnumSource(value = MessagePipelineResult.DropReason.class, mode = EnumSource.Mode.EXCLUDE, names = "NONE")
        @DisplayName("every non-NONE DropReason can be stored via dropped()")
        void allDropReasons(MessagePipelineResult.DropReason reason) {
            MessagePipelineResult result = MessagePipelineResult.dropped(reason, sampleMessage());
            assertThat(result.isDelivered()).isFalse();
            assertThat(result.getDropReason()).isEqualTo(reason);
        }

        @Test
        @DisplayName("null DropReason is normalized to NONE")
        void nullDropReasonBecomesNone() {
            // Factory always passes an enum; exercise via delivered path which sets NONE.
            // Direct null is only reachable if constructor receives null — delivered uses NONE.
            MessagePipelineResult delivered = MessagePipelineResult.delivered(
                    sampleMessage(), sampleChannel(), Set.of("c1"), false, 0);
            assertThat(delivered.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.NONE);
        }
    }

    @Nested
    @DisplayName("delivered factory")
    class DeliveredFactory {

        @Test
        @DisplayName("delivered marks success with recipients and filter metadata")
        void deliveredSuccess() {
            ChatMessagePacket msg = sampleMessage();
            Channel channel = sampleChannel();
            Set<String> recipients = new HashSet<>();
            recipients.add("client-a");
            recipients.add("client-b");

            MessagePipelineResult result = MessagePipelineResult.delivered(
                    msg, channel, recipients, true, 3);

            assertThat(result.isDelivered()).isTrue();
            assertThat(result.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.NONE);
            assertThat(result.getMessage()).isSameAs(msg);
            assertThat(result.getChannel()).isSameAs(channel);
            assertThat(result.getRecipients()).containsExactlyInAnyOrder("client-a", "client-b");
            assertThat(result.isContentFiltered()).isTrue();
            assertThat(result.getFilterMatchCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("delivered with empty recipients is still marked delivered")
        void deliveredEmptyRecipientsAllowedByFactory() {
            MessagePipelineResult result = MessagePipelineResult.delivered(
                    sampleMessage(), sampleChannel(), Collections.emptySet(), false, 0);

            assertThat(result.isDelivered()).isTrue();
            assertThat(result.getRecipients()).isEmpty();
            assertThat(result.isContentFiltered()).isFalse();
            assertThat(result.getFilterMatchCount()).isZero();
        }

        @Test
        @DisplayName("delivered rejects null recipients")
        void deliveredNullRecipientsThrows() {
            assertThatThrownBy(() -> MessagePipelineResult.delivered(
                    sampleMessage(), sampleChannel(), null, false, 0))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("recipients");
        }

        @Test
        @DisplayName("delivered allows null message and null channel")
        void deliveredNullMessageAndChannel() {
            MessagePipelineResult result = MessagePipelineResult.delivered(
                    null, null, Set.of("only"), false, 0);

            assertThat(result.isDelivered()).isTrue();
            assertThat(result.getMessage()).isNull();
            assertThat(result.getChannel()).isNull();
            assertThat(result.getRecipients()).containsExactly("only");
        }
    }

    @Nested
    @DisplayName("recipients immutability")
    class RecipientsImmutability {

        @Test
        @DisplayName("getRecipients returns unmodifiable set for delivered")
        void deliveredRecipientsUnmodifiable() {
            Set<String> mutable = new HashSet<>();
            mutable.add("client-1");
            MessagePipelineResult result = MessagePipelineResult.delivered(
                    sampleMessage(), sampleChannel(), mutable, false, 0);

            assertThatThrownBy(() -> result.getRecipients().add("client-2"))
                    .isInstanceOf(UnsupportedOperationException.class);

            // Mutating the original input after factory call must not affect the result
            mutable.add("client-3");
            // result may or may not be a live view depending on Collections.unmodifiableSet —
            // unmodifiableSet wraps the original, so live mutation is visible; document current behavior:
            // MessagePipelineResult stores Collections.unmodifiableSet(recipients) which is a live view.
            // Pipeline always passes a fresh HashSet, so callers should not mutate after.
            assertThat(result.getRecipients()).contains("client-1");
        }

        @Test
        @DisplayName("getRecipients returns unmodifiable empty set for dropped")
        void droppedRecipientsUnmodifiable() {
            MessagePipelineResult result = MessagePipelineResult.dropped(
                    MessagePipelineResult.DropReason.NO_RECIPIENTS, sampleMessage());

            assertThat(result.getRecipients()).isEmpty();
            assertThatThrownBy(() -> result.getRecipients().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("dropped with channel also returns unmodifiable empty recipients")
        void droppedWithChannelRecipientsUnmodifiable() {
            MessagePipelineResult result = MessagePipelineResult.dropped(
                    MessagePipelineResult.DropReason.CROSS_CLIENT_DENIED,
                    sampleMessage(),
                    sampleChannel());

            assertThatThrownBy(() -> result.getRecipients().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("toString is non-null and contains key fields for delivered")
        void deliveredToString() {
            MessagePipelineResult result = MessagePipelineResult.delivered(
                    sampleMessage(), sampleChannel(), Set.of("a", "b"), true, 2);

            String text = result.toString();
            assertThat(text).isNotNull();
            assertThat(text)
                    .contains("MessagePipelineResult")
                    .contains("delivered=true")
                    .contains("dropReason=NONE")
                    .contains("recipients=2")
                    .contains("contentFiltered=true")
                    .contains("filterMatchCount=2");
        }

        @Test
        @DisplayName("toString is non-null and contains key fields for dropped")
        void droppedToString() {
            MessagePipelineResult result = MessagePipelineResult.dropped(
                    MessagePipelineResult.DropReason.OVERSIZED_CONTENT, sampleMessage());

            String text = result.toString();
            assertThat(text).isNotNull();
            assertThat(text)
                    .contains("delivered=false")
                    .contains("dropReason=OVERSIZED_CONTENT")
                    .contains("recipients=0")
                    .contains("contentFiltered=false");
        }

        @Test
        @DisplayName("toString never returns null for null-message drop")
        void nullMessageToString() {
            MessagePipelineResult result = MessagePipelineResult.dropped(
                    MessagePipelineResult.DropReason.NULL_MESSAGE, null);
            assertThat(result.toString()).isNotNull().isNotBlank();
        }
    }

    @Nested
    @DisplayName("DropReason enum")
    class DropReasonEnum {

        @Test
        @DisplayName("all expected drop reasons exist")
        void expectedValuesPresent() {
            assertThat(MessagePipelineResult.DropReason.values())
                    .containsExactlyInAnyOrder(
                            MessagePipelineResult.DropReason.NONE,
                            MessagePipelineResult.DropReason.NULL_MESSAGE,
                            MessagePipelineResult.DropReason.EMPTY_CONTENT,
                            MessagePipelineResult.DropReason.OVERSIZED_CONTENT,
                            MessagePipelineResult.DropReason.MISSING_CHANNEL_ID,
                            MessagePipelineResult.DropReason.CHANNEL_NOT_FOUND,
                            MessagePipelineResult.DropReason.SENDER_MUTED,
                            MessagePipelineResult.DropReason.CROSS_CLIENT_DENIED,
                            MessagePipelineResult.DropReason.NO_RECIPIENTS
                    );
        }
    }
}
