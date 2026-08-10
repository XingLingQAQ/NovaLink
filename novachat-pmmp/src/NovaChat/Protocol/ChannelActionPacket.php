<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Channel action packet for channel operations.
 *
 * Wire:
 * - action (byte): Action type
 * - channelId (string): Channel identifier
 * - password (string): Channel password (if required)
 * - extra (map): Extra key-value data
 *
 * Action wire IDs are 0-based and must match the Java ChannelAction enum:
 *   JOIN=0, LEAVE=1, CREATE=2, DELETE=3, INVITE=4, ACCEPT=5,
 *   KICK=6, MUTE=7, UNMUTE=8, BAN=9, UNBAN=10, WHO=11
 */
class ChannelActionPacket extends Packet {

    // Action types (0-based, matching Java ChannelAction enum)
    public const ACTION_JOIN = 0;
    public const ACTION_LEAVE = 1;
    public const ACTION_CREATE = 2;
    public const ACTION_DELETE = 3;
    public const ACTION_INVITE = 4;
    public const ACTION_ACCEPT = 5;
    public const ACTION_KICK = 6;
    public const ACTION_MUTE = 7;
    public const ACTION_UNMUTE = 8;
    public const ACTION_BAN = 9;
    public const ACTION_UNBAN = 10;
    public const ACTION_WHO = 11;

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
