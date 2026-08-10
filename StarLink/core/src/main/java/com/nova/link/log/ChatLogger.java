package com.nova.link.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple chat logger that records delivered chat messages via SLF4J.
 *
 * <p>No database table is involved — this intentionally avoids adding another
 * migration. Messages are logged at INFO to a dedicated logger name so they
 * can be captured by the logging backend (file appender, etc.) without
 * polluting the main NovaLink log stream. The logger name is stable so
 * operators can filter it independently.
 *
 * Requirements: FeatureConfig.messageLogEnabled — optional chat logging
 */
public class ChatLogger {

    private static final Logger logger = LoggerFactory.getLogger("novalink.chatlog");

    /**
     * Records a delivered chat message.
     *
     * @param channelId the channel the message was delivered to
     * @param senderId the sender UUID (may be null for API-originated messages)
     * @param senderName the sender display name
     * @param content the (possibly filtered) message content
     */
    public void log(String channelId, String senderId, String senderName, String content) {
        logger.info("[{}] {} ({}): {}", channelId, senderName != null ? senderName : "?",
                senderId != null ? senderId : "-", content);
    }
}
