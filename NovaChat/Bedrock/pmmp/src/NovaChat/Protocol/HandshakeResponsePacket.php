<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Handshake response packet sent by server after authentication.
 *
 * Wire order (must match Java HandshakeResponsePacket):
 * - success (bool): Whether authentication was successful
 * - errorCode (string): Error code if authentication failed
 * - message (string): Human-readable message
 *
 * NOTE: there is NO configJson field — the Java packet writes
 * success | errorCode | message. Earlier PMMP builds incorrectly carried a
 * configJson field here; it has been removed for protocol parity.
 */
class HandshakeResponsePacket extends Packet {

    public bool $success = false;
    public string $errorCode = "";
    public string $message = "";

    public function getId(): int {
        return self::HANDSHAKE_RESPONSE;
    }

    public function encode(PacketBuffer $buffer): void {
        $buffer->writeBoolean($this->success);
        $buffer->writeString($this->errorCode);
        $buffer->writeString($this->message);
    }

    public function decode(PacketBuffer $buffer): void {
        $this->success = $buffer->readBoolean();
        $this->errorCode = $buffer->readString();
        $this->message = $buffer->readString();
    }
}
