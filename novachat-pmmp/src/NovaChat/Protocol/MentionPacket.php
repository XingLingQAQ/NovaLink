<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Mention notification packet (Server -> Client) - @mention highlight.
 *
 * Wire:
 * - mentionerId (uuid): UUID of the player who mentioned
 * - mentionerName (string): Display name of the mentioner
 * - mentionedId (uuid): UUID of the mentioned player
 * - channelId (string): Channel where the mention happened
 * - messagePreview (string): Truncated preview of the message
 * - timestamp (long): epoch millis
 */
class MentionPacket extends Packet {

    public string $mentionerId = "";
    public string $mentionerName = "";
    public string $mentionedId = "";
    public string $channelId = "";
    public string $messagePreview = "";
    public int $timestamp = 0;

    public function getId(): int {
        return self::MENTION;
    }

    public function encode(PacketBuffer $buffer): void {
        $buffer->writeUUID($this->mentionerId);
        $buffer->writeString($this->mentionerName);
        $buffer->writeUUID($this->mentionedId);
        $buffer->writeString($this->channelId);
        $buffer->writeString($this->messagePreview);
        $buffer->writeLong($this->timestamp);
    }

    public function decode(PacketBuffer $buffer): void {
        $this->mentionerId = $buffer->readUUID();
        $this->mentionerName = $buffer->readString();
        $this->mentionedId = $buffer->readUUID();
        $this->channelId = $buffer->readString();
        $this->messagePreview = $buffer->readString();
        $this->timestamp = $buffer->readLong();
    }
}
