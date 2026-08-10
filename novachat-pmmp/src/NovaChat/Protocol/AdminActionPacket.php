<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Admin action packet (Client -> Server).
 *
 * Wire:
 * - action (byte): AdminAction wire ID
 * - playerId (uuid): the admin player UUID
 * - passwordHash (string): SHA-256 of admin password (for AUTH)
 * - target (string): target player/channel (action-dependent)
 * - extra (map): extra key-value data
 *
 * AdminAction wire IDs (must match Java AdminAction enum):
 *   AUTH=0, LOGOUT=1, SPY_START=2, SPY_STOP=3, RELOAD=4, STATUS=5
 */
class AdminActionPacket extends Packet {

    public const ACTION_AUTH = 0;
    public const ACTION_LOGOUT = 1;
    public const ACTION_SPY_START = 2;
    public const ACTION_SPY_STOP = 3;
    public const ACTION_RELOAD = 4;
    public const ACTION_STATUS = 5;

    public int $action = 0;
    public string $playerId = "";
    public string $passwordHash = "";
    public string $target = "";
    /** @var array<string, string> */
    public array $extra = [];

    public function getId(): int {
        return self::ADMIN_ACTION;
    }

    public function encode(PacketBuffer $buffer): void {
        $buffer->writeByte($this->action);
        $buffer->writeUUID($this->playerId);
        $buffer->writeString($this->passwordHash);
        $buffer->writeString($this->target);
        $buffer->writeVarInt(count($this->extra));
        foreach ($this->extra as $key => $value) {
            $buffer->writeString((string)$key);
            $buffer->writeString((string)$value);
        }
    }

    public function decode(PacketBuffer $buffer): void {
        $this->action = $buffer->readByte();
        $this->playerId = $buffer->readUUID();
        $this->passwordHash = $buffer->readString();
        $this->target = $buffer->readString();
        $size = $buffer->readVarInt();
        $this->extra = [];
        for ($i = 0; $i < $size; $i++) {
            $key = $buffer->readString();
            $value = $buffer->readString();
            $this->extra[$key] = $value;
        }
    }
}
