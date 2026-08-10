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
        /** Message was not dropped; it proceeded to delivery. */
        NONE,
        /** The inbound packet object was null. */
        NULL_MESSAGE,
        /** The message content was null or blank after trimming. */
        EMPTY_CONTENT,
        /** The message content exceeded the configured length cap. */
        OVERSIZED_CONTENT,
        /** The packet carried no target channel id. */
        MISSING_CHANNEL_ID,
        /** The resolved channel id does not match a known channel. */
        CHANNEL_NOT_FOUND,
        /** The sender is muted on the target channel. */
        SENDER_MUTED,
        /** The sender is banned from the target channel (or globally). */
        SENDER_BANNED,
        /** Cross-client routing policy denied delivery to the target client. */
        CROSS_CLIENT_DENIED,
        /** No online recipients matched the channel membership. */
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

    /**
     * Creates a dropped result with no channel context. Use when the pipeline
     * rejects a message before a channel is resolved (e.g. null/empty/oversized
     * content, missing channel id).
     *
     * @param reason  why the message was dropped
     * @param message the offending message
     * @return a dropped result
     */
    public static MessagePipelineResult dropped(DropReason reason, ChatMessagePacket message) {
        return new MessagePipelineResult(false, reason, message, null, Collections.emptySet(), false, 0);
    }

    /**
     * Creates a dropped result that records the channel the message was
     * targeted at. Use when the drop decision was made after the channel was
     * located (e.g. sender muted/banned, cross-client denied, no recipients).
     *
     * @param reason  why the message was dropped
     * @param message the offending message
     * @param channel the channel the message was routed toward
     * @return a dropped result
     */
    public static MessagePipelineResult dropped(DropReason reason, ChatMessagePacket message, Channel channel) {
        return new MessagePipelineResult(false, reason, message, channel, Collections.emptySet(), false, 0);
    }

    /**
     * Creates a delivered result capturing the fan-out outcome: which channel
     * the message landed in, the recipient set, and whether content filtering
     * modified or flagged the message.
     *
     * @param message           the delivered message
     * @param channel           the channel it was delivered to
     * @param recipients        the player UUIDs that received it
     * @param contentFiltered   whether the content filter altered the message
     * @param filterMatchCount  number of filter rules that matched
     * @return a delivered result
     */
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
