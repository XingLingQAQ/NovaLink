<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Announcement packet for broadcasting announcements.
 *
 * Wire format (matches Python/Endstone implementation):
 * - type (byte): Announcement type (chat, title, actionbar)
 * - message (string): Announcement content
 */
class AnnouncementPacket extends Packet {

    // Announcement types
    public const TYPE_CHAT = 0x01;
    public const TYPE_TITLE = 0x02;
    public const TYPE_ACTIONBAR = 0x03;

    public int $type = self::TYPE_CHAT;
    public string $message = "";

    public function getId(): int {
        return self::ANNOUNCEMENT;
    }

    public function encode(PacketBuffer $buffer): void {
        $buffer->writeByte($this->type);
        $buffer->writeString($this->message);
    }

    public function decode(PacketBuffer $buffer): void {
        $this->type = $buffer->readByte();
        $this->message = $buffer->readString();
    }
}
