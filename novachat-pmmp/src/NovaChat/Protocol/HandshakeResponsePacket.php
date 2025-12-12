<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Handshake response packet sent by server after authentication.
 * 
 * Fields:
 * - success (bool): Whether authentication was successful
 * - errorCode (string): Error code if authentication failed
 * - configJson (string): JSON configuration data if successful
 */
class HandshakeResponsePacket extends Packet {
    
    public bool $success = false;
    public string $errorCode = "";
    public string $configJson = "";
    
    public function getId(): int {
        return self::HANDSHAKE_RESPONSE;
    }
    
    public function encode(PacketBuffer $buffer): void {
        $buffer->writeBoolean($this->success);
        $buffer->writeString($this->errorCode);
        $buffer->writeString($this->configJson);
    }
    
    public function decode(PacketBuffer $buffer): void {
        $this->success = $buffer->readBoolean();
        $this->errorCode = $buffer->readString();
        $this->configJson = $buffer->readString();
    }
}
