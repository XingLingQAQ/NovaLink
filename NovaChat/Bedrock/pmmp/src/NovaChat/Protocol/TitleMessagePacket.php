<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Title message packet for displaying titles to players.
 * 
 * Fields:
 * - channelId (string): Target channel ID (empty for broadcast)
 * - title (string): Title text
 * - subtitle (string): Subtitle text
 * - fadeIn (int): Fade in time in ticks
 * - stay (int): Stay time in ticks
 * - fadeOut (int): Fade out time in ticks
 * - senderId (uuid): Sender UUID (admin). Zero UUID means system/console.
 */
class TitleMessagePacket extends Packet {
    
    public string $channelId = "";
    public string $title = "";
    public string $subtitle = "";
    public int $fadeIn = 10;
    public int $stay = 70;
    public int $fadeOut = 20;
    public string $senderId = "00000000-0000-0000-0000-000000000000";
    
    public function getId(): int {
        return self::TITLE;
    }
    
    public function encode(PacketBuffer $buffer): void {
        $buffer->writeString($this->channelId);
        $buffer->writeString($this->title);
        $buffer->writeString($this->subtitle);
        $buffer->writeInt($this->fadeIn);
        $buffer->writeInt($this->stay);
        $buffer->writeInt($this->fadeOut);
        $buffer->writeUUID($this->senderId);
    }
    
    public function decode(PacketBuffer $buffer): void {
        $this->channelId = $buffer->readString(ProtocolLimits::MAX_CHANNEL_ID);
        $this->title = $buffer->readString(ProtocolLimits::MAX_TITLE);
        $this->subtitle = $buffer->readString(ProtocolLimits::MAX_SUBTITLE);
        $this->fadeIn = $buffer->readInt();
        $this->stay = $buffer->readInt();
        $this->fadeOut = $buffer->readInt();
        $this->senderId = $buffer->readUUID();
    }
}
