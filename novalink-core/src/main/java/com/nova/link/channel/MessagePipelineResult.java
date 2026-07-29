package com.nova.link.channel;

import com.nova.chat.common.protocol.packets.ChatMessagePacket;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Outcome of running a chat message through {@link MessagePipeline}.
 * <p>
 * Makes each stage decision observable for tests and logging without
 * coupling callers to Netty fan-out side effects.
 */
public final class MessagePipelineResult {

    public enum DropReason {
        NONE,
        NULL_MESSAGE,
        EMPTY_CONTENT,
        OVERSIZED_CONTENT,
        MISSING_CHANNEL_ID,
        CHANNEL_NOT_FOUND,
        SENDER_MUTED,
        CROSS_CLIENT_DENIED,
        NO_RECIPIENTS
    }

    private final boolean delivered;
    private final DropReason dropReason;
    private final ChatMessagePacket message;
    private final Channel channel;
    private final Set<String> recipients;
    private final boolean contentFiltered;
    private final int filterMatchCount;

    private MessagePipelineResult(boolean delivered,
                                  DropReason dropReason,
                                  ChatMessagePacket message,
                                  Channel channel,
                                  Set<String> recipients,
                                  boolean contentFiltered,
                                  int filterMatchCount) {
        this.delivered = delivered;
        this.dropReason = dropReason != null ? dropReason : DropReason.NONE;
        this.message = message;
        this.channel = channel;
        this.recipients = recipients != null
                ? Collections.unmodifiableSet(recipients)
                : Collections.emptySet();
        this.contentFiltered = contentFiltered;
        this.filterMatchCount = filterMatchCount;
    }

    public static MessagePipelineResult dropped(DropReason reason, ChatMessagePacket message) {
        return new MessagePipelineResult(false, reason, message, null, Collections.emptySet(), false, 0);
    }

    public static MessagePipelineResult dropped(DropReason reason, ChatMessagePacket message, Channel channel) {
        return new MessagePipelineResult(false, reason, message, channel, Collections.emptySet(), false, 0);
    }

    public static MessagePipelineResult delivered(ChatMessagePacket message,
                                                 Channel channel,
                                                 Set<String> recipients,
                                                 boolean contentFiltered,
                                                 int filterMatchCount) {
        Objects.requireNonNull(recipients, "recipients");
        return new MessagePipelineResult(true, DropReason.NONE, message, channel, recipients,
                contentFiltered, filterMatchCount);
    }

    public boolean isDelivered() {
        return delivered;
    }

    public DropReason getDropReason() {
        return dropReason;
    }

    public ChatMessagePacket getMessage() {
        return message;
    }

    public Channel getChannel() {
        return channel;
    }

    public Set<String> getRecipients() {
        return recipients;
    }

    public boolean isContentFiltered() {
        return contentFiltered;
    }

    public int getFilterMatchCount() {
        return filterMatchCount;
    }

    @Override
    public String toString() {
        return "MessagePipelineResult{" +
                "delivered=" + delivered +
                ", dropReason=" + dropReason +
                ", recipients=" + recipients.size() +
                ", contentFiltered=" + contentFiltered +
                ", filterMatchCount=" + filterMatchCount +
                '}';
    }
}
