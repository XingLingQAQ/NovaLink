<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Handshake packet sent by client to authenticate with the server.
 * 
 * Fields:
 * - protocolVersion (int): Protocol version number
 * - clientId (string): Unique client identifier
 * - passwordHash (string): SHA-256 hash of the password
 * - platform (byte): Platform identifier (0x05 for PMMP)
 */
class HandshakePacket extends Packet {
    
    /** Platform identifier for PocketMine-MP */
    public const PLATFORM_PMMP = 0x09;
    
    /** Current protocol version */
    public const PROTOCOL_VERSION = 2;
    
    public int $protocolVersion = self::PROTOCOL_VERSION;
    public string $clientId = "";
    public string $passwordHash = "";
    public int $platform = self::PLATFORM_PMMP;
    
    public function getId(): int {
        return self::HANDSHAKE;
    }
    
    public function encode(PacketBuffer $buffer): void {
        $buffer->writeVarInt($this->protocolVersion);
        $buffer->writeString($this->clientId);
        $buffer->writeString($this->passwordHash);
        $buffer->writeByte($this->platform);
    }
    
    public function decode(PacketBuffer $buffer): void {
        $this->protocolVersion = $buffer->readVarInt();
        $this->clientId = $buffer->readString();
        $this->passwordHash = $buffer->readString();
        $this->platform = $buffer->readByte();
    }
}
