package com.nova.link.channel;

import com.nova.link.database.Invitation;

/**
 * Represents the result of an invitation operation.
 * 
 * Requirements: 8.2, 8.3, 8.4
 */
public class InvitationResult {

    private final boolean success;
    private final String errorCode;
    private final String errorMessage;
    private final Invitation invitation;
    private final String channelId;

    private InvitationResult(boolean success, String errorCode, String errorMessage, 
                             Invitation invitation, String channelId) {
        this.success = success;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.invitation = invitation;
        this.channelId = channelId;
    }

    /**
     * Creates a successful validation result.
     */
    public static InvitationResult valid(Invitation invitation) {
        return new InvitationResult(true, null, null, invitation, null);
    }

    /**
     * Creates a successful acceptance result.
     */
    public static InvitationResult accepted(Invitation invitation, String channelId) {
        return new InvitationResult(true, null, null, invitation, channelId);
    }

    /**
     * Creates an invalid/error result.
     */
    public static InvitationResult invalid(String errorCode, String errorMessage) {
        return new InvitationResult(false, errorCode, errorMessage, null, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Invitation getInvitation() {
        return invitation;
    }

    public String getChannelId() {
        return channelId;
    }

    @Override
    public String toString() {
        if (success) {
            return "InvitationResult{success=true, channelId='" + channelId + "'}";
        } else {
            return "InvitationResult{success=false, errorCode='" + errorCode + 
                   "', errorMessage='" + errorMessage + "'}";
        }
    }
}
