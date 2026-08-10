<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Item display packet (Bidirectional) - [item]/[i] tag display.
 *
 * Wire:
 * - senderId (uuid): UUID of the sender
 * - senderName (string): Display name of the sender
 * - channelId (string): Channel identifier
 * - itemJson (string): JSON describing the item
 * - timestamp (long): epoch millis
 */
class ItemDisplayPacket extends Packet {

    public string $senderId = "";
    public string $senderName = "";
    public string $channelId = "";
    public string $itemJson = "";
    public int $timestamp = 0;

    public function getId(): int {
        return self::ITEM_DISPLAY;
    }

    public function encode(PacketBuffer $buffer): void {
        $buffer->writeUUID($this->senderId);
        $buffer->writeString($this->senderName);
        $buffer->writeString($this->channelId);
        $buffer->writeString($this->itemJson);
        $buffer->writeLong($this->timestamp);
    }

    public function decode(PacketBuffer $buffer): void {
        $this->senderId = $buffer->readUUID();
        $this->senderName = $buffer->readString();
        $this->channelId = $buffer->readString();
        $this->itemJson = $buffer->readString();
        $this->timestamp = $buffer->readLong();
    }
}
