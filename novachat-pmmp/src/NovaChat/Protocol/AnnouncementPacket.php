<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Announcement packet for broadcasting announcements.
 * 
 * Fields:
 * - announcementId (string): Unique announcement identifier
 * - content (string): Announcement content
 * - type (byte): Announcement type (chat, title, actionbar)
 */
class AnnouncementPacket extends Packet {
    
    // Announcement types
    public const TYPE_CHAT = 0x01;
    public const TYPE_TITLE = 0x02;
    public const TYPE_ACTIONBAR = 0x03;
    
    public string $announcementId = "";
    public string $content = "";
    public int $type = self::TYPE_CHAT;
    
    public function getId(): int {
        return self::ANNOUNCEMENT;
    }
    
    public function encode(PacketBuffer $buffer): void {
        $buffer->writeString($this->announcementId);
        $buffer->writeString($this->content);
        $buffer->writeByte($this->type);
    }
    
    public function decode(PacketBuffer $buffer): void {
        $this->announcementId = $buffer->readString();
        $this->content = $buffer->readString();
        $this->type = $buffer->readByte();
    }
}
