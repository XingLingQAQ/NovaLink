<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Admin action response packet (Server -> Client).
 *
 * Wire:
 * - action (byte): the AdminAction this responds to
 * - success (bool)
 * - errorCode (string)
 * - message (string)
 */
class AdminActionResponsePacket extends Packet {

    public int $action = 0;
    public bool $success = false;
    public string $errorCode = "";
    public string $message = "";

    public function getId(): int {
        return self::ADMIN_ACTION_RESPONSE;
    }

    public function encode(PacketBuffer $buffer): void {
        $buffer->writeByte($this->action);
        $buffer->writeBoolean($this->success);
        $buffer->writeString($this->errorCode);
        $buffer->writeString($this->message);
    }

    public function decode(PacketBuffer $buffer): void {
        $this->action = $buffer->readByte();
        $this->success = $buffer->readBoolean();
        $this->errorCode = $buffer->readString();
        $this->message = $buffer->readString();
    }
}
