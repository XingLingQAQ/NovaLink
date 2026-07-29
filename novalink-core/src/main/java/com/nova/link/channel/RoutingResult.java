package com.nova.link.channel;

import java.util.Collections;
import java.util.Set;

/**
 * Result of a message routing operation.
 * Contains information about which clients received the message.
 */
public class RoutingResult {

    private final boolean success;
    private final String channelId;
    private final ChannelScope scope;
    private final Set<String> recipientClientIds;
    private final String errorMessage;

    private RoutingResult(boolean success, String channelId, ChannelScope scope, 
                          Set<String> recipientClientIds, String errorMessage) {
        this.success = success;
        this.channelId = channelId;
        this.scope = scope;
        this.recipientClientIds = recipientClientIds != null ? 
                Collections.unmodifiableSet(recipientClientIds) : Collections.emptySet();
        this.errorMessage = errorMessage;
    }

    /**
     * Creates a successful routing result.
     *
     * @param channelId the channel ID
     * @param scope the channel scope
     * @param recipientClientIds the set of client IDs that received the message
     * @return a successful routing result
     */
    public static RoutingResult success(String channelId, ChannelScope scope, Set<String> recipientClientIds) {
        return new RoutingResult(true, channelId, scope, recipientClientIds, null);
    }

    /**
     * Creates a failed routing result.
     *
     * @param channelId the channel ID (may be null if channel not found)
     * @param errorMessage the error message
     * @return a failed routing result
     */
    public static RoutingResult failure(String channelId, String errorMessage) {
        return new RoutingResult(false, channelId, null, Collections.emptySet(), errorMessage);
    }

    /**
     * Checks if the routing was successful.
     *
     * @return true if successful
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Gets the channel ID.
     *
     * @return the channel ID
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * Gets the channel scope.
     *
     * @return the scope, or null if routing failed
     */
    public ChannelScope getScope() {
        return scope;
    }

    /**
     * Gets the set of client IDs that received the message.
     *
     * @return unmodifiable set of recipient client IDs
     */
    public Set<String> getRecipientClientIds() {
        return recipientClientIds;
    }

    /**
     * Gets the number of clients that received the message.
     *
     * @return recipient count
     */
    public int getRecipientCount() {
        return recipientClientIds.size();
    }

    /**
     * Gets the error message if routing failed.
     *
     * @return the error message, or null if successful
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        if (success) {
            return "RoutingResult{success=true, channelId='" + channelId + 
                    "', scope=" + scope + ", recipients=" + recipientClientIds.size() + "}";
        } else {
            return "RoutingResult{success=false, channelId='" + channelId + 
                    "', error='" + errorMessage + "'}";
        }
    }
}
