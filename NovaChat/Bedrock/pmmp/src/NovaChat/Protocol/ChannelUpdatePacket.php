<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Channel update packet sent by server when channel state changes.
 * 
 * Fields:
 * - channelId (string): Channel identifier
 * - updateType (byte): Type of update
 * - dataJson (string): JSON data for the update
 */
class ChannelUpdatePacket extends Packet {
    
    // Update types
    public const UPDATE_CREATED = 0x01;
    public const UPDATE_DELETED = 0x02;
    public const UPDATE_MEMBER_JOIN = 0x03;
    public const UPDATE_MEMBER_LEAVE = 0x04;
    public const UPDATE_CONFIG_CHANGED = 0x05;
    
    public int $updateType = 0;
    public string $channelId = "";
    public string $dataJson = "";
    
    public function getId(): int {
        return self::CHANNEL_UPDATE;
    }
    
    public function encode(PacketBuffer $buffer): void {
        $buffer->writeString($this->channelId);
        $buffer->writeByte($this->updateType);
        $buffer->writeString($this->dataJson);
    }

    public function decode(PacketBuffer $buffer): void {
        $this->channelId = $buffer->readString();
        $this->updateType = $buffer->readByte();
        $this->dataJson = $buffer->readString();
    }
}
