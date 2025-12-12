<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Channel action packet for channel operations.
 * 
 * Fields:
 * - action (byte): Action type
 * - channelId (string): Channel identifier
 * - password (string): Channel password (if required)
 * - extra (map): Extra key-value data
 */
class ChannelActionPacket extends Packet {
    
    // Action types
    public const ACTION_JOIN = 0x01;
    public const ACTION_LEAVE = 0x02;
    public const ACTION_CREATE = 0x03;
    public const ACTION_DELETE = 0x04;
    public const ACTION_INVITE = 0x05;
    public const ACTION_ACCEPT = 0x06;
    public const ACTION_KICK = 0x07;
    public const ACTION_LIST = 0x08;
    public const ACTION_ACCEPT_INVITE = 0x09;
    public const ACTION_ADMIN = 0x0A;
    
    public int $action = 0;
    public string $channelId = "";
    public string $password = "";
    /** @var array<string, string> */
    public array $extra = [];
    
    public function getId(): int {
        return self::CHANNEL_ACTION;
    }
    
    public function encode(PacketBuffer $buffer): void {
        $buffer->writeByte($this->action);
        $buffer->writeString($this->channelId);
        $buffer->writeString($this->password);
        $buffer->writeVarInt(count($this->extra));
        foreach ($this->extra as $key => $value) {
            $buffer->writeString((string)$key);
            $buffer->writeString((string)$value);
        }
    }
    
    public function decode(PacketBuffer $buffer): void {
        $this->action = $buffer->readByte();
        $this->channelId = $buffer->readString();
        $this->password = $buffer->readString();
        $size = $buffer->readVarInt();
        $this->extra = [];
        for ($i = 0; $i < $size; $i++) {
            $key = $buffer->readString();
            $value = $buffer->readString();
            $this->extra[$key] = $value;
        }
    }
}
