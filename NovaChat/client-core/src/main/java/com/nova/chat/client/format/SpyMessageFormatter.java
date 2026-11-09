package com.nova.chat.client.format;

import com.nova.chat.common.protocol.packets.SpyMessagePacket;

public final class SpyMessageFormatter {
    private SpyMessageFormatter() {
    }

    public static String format(SpyMessagePacket packet) {
        var message = packet.getMessage();
        return "[SPY] [" + message.getClientId() + "/" + message.getChannelId() + "] "
                + message.getSenderName() + ": " + message.getContent();
    }
}
