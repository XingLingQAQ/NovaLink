package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.ProtocolLimits;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Title packet for sending title and subtitle messages to players.
 * Supports color codes including &amp; codes and Hex colors (&#RRGGBB).
 * 
 * Packet ID: 0x09
 * Direction: Server → Client
 * 
 * Requirements:
 * - 15.1: Admin can send Title messages to channel players
 * - 15.5: Support color codes in Title messages
 */
public class TitlePacket extends Packet {

    /** Target channel ID */
    private String channelId;
    
    /** Title text (main title) */
    private String title;
    
    /** Subtitle text (optional) */
    private String subtitle;
    
    /** Fade in time in ticks (default: 10) */
    private int fadeIn;
    
    /** Stay time in ticks (default: 70) */
    private int stay;
    
    /** Fade out time in ticks (default: 20) */
    private int fadeOut;
    
    /** UUID of the admin who sent the title */
    private UUID senderId;

    public TitlePacket() {
        super();
        this.fadeIn = 10;
        this.stay = 70;
        this.fadeOut = 20;
    }

    public TitlePacket(UUID requestId) {
        super(requestId);
        this.fadeIn = 10;
        this.stay = 70;
        this.fadeOut = 20;
    }


    /**
     * Creates a new TitlePacket with the specified parameters.
     *
     * @param channelId the target channel ID
     * @param title the title text
     * @param subtitle the subtitle text (can be null or empty)
     * @param senderId the UUID of the admin sending the title
     */
    public TitlePacket(String channelId, String title, String subtitle, UUID senderId) {
        super();
        this.channelId = channelId;
        this.title = title;
        this.subtitle = subtitle != null ? subtitle : "";
        this.senderId = senderId;
        this.fadeIn = 10;
        this.stay = 70;
        this.fadeOut = 20;
    }

    /**
     * Creates a new TitlePacket with custom timing.
     *
     * @param channelId the target channel ID
     * @param title the title text
     * @param subtitle the subtitle text (can be null or empty)
     * @param senderId the UUID of the admin sending the title
     * @param fadeIn fade in time in ticks
     * @param stay stay time in ticks
     * @param fadeOut fade out time in ticks
     */
    public TitlePacket(String channelId, String title, String subtitle, UUID senderId,
                       int fadeIn, int stay, int fadeOut) {
        super();
        this.channelId = channelId;
        this.title = title;
        this.subtitle = subtitle != null ? subtitle : "";
        this.senderId = senderId;
        this.fadeIn = fadeIn;
        this.stay = stay;
        this.fadeOut = fadeOut;
    }

    @Override
    public int getPacketId() {
        return PacketIds.TITLE;
    }

    @Override
    public void write(ByteBuf buf) {
        PacketBuffer.writeString(buf, channelId != null ? channelId : "");
        PacketBuffer.writeString(buf, title != null ? title : "");
        PacketBuffer.writeString(buf, subtitle != null ? subtitle : "");
        PacketBuffer.writeInt(buf, fadeIn);
        PacketBuffer.writeInt(buf, stay);
        PacketBuffer.writeInt(buf, fadeOut);
        PacketBuffer.writeUUID(buf, senderId != null ? senderId : new UUID(0, 0));
    }

    @Override
    public void read(ByteBuf buf) {
        // PROTO-003: bound each field so a single oversized string cannot
        // approach the 4 MiB frame ceiling.
        channelId = PacketBuffer.readString(buf, ProtocolLimits.MAX_CHANNEL_ID);
        title = PacketBuffer.readString(buf, ProtocolLimits.MAX_TITLE);
        subtitle = PacketBuffer.readString(buf, ProtocolLimits.MAX_SUBTITLE);
        fadeIn = PacketBuffer.readInt(buf);
        stay = PacketBuffer.readInt(buf);
        fadeOut = PacketBuffer.readInt(buf);
        senderId = PacketBuffer.readUUID(buf);
    }

    // Getters and setters

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle != null ? subtitle : "";
    }

    public int getFadeIn() {
        return fadeIn;
    }

    public void setFadeIn(int fadeIn) {
        this.fadeIn = fadeIn;
    }

    public int getStay() {
        return stay;
    }

    public void setStay(int stay) {
        this.stay = stay;
    }

    public int getFadeOut() {
        return fadeOut;
    }

    public void setFadeOut(int fadeOut) {
        this.fadeOut = fadeOut;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public void setSenderId(UUID senderId) {
        this.senderId = senderId;
    }

    @Override
    public String toString() {
        return "TitlePacket{" +
                "channelId='" + channelId + '\'' +
                ", title='" + title + '\'' +
                ", subtitle='" + subtitle + '\'' +
                ", fadeIn=" + fadeIn +
                ", stay=" + stay +
                ", fadeOut=" + fadeOut +
                ", senderId=" + senderId +
                '}';
    }
}
