<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Handshake packet sent by client to authenticate with the server.
 *
 * Wire (protocol v2):
 * - protocolVersion (varint): Protocol version number
 * - clientId (string): Unique client identifier
 * - passwordHash (string): SHA-256 hash of the password
 * - platform (byte): Platform identifier (0x09 for PMMP)
 * - serverVersion (string): Trailing v2 field (server software version)
 *
 * The trailing serverVersion is optional on decode for backward compatibility
 * with v1 peers that omit it.
 */
class HandshakePacket extends Packet {

    /** Platform identifier for PocketMine-MP */
    public const PLATFORM_PMMP = 0x09;

    /** Current protocol version (v2 adds trailing serverVersion). */
    public const PROTOCOL_VERSION = 2;

    public int $protocolVersion = self::PROTOCOL_VERSION;
    public string $clientId = "";
    public string $passwordHash = "";
    public int $platform = self::PLATFORM_PMMP;
    public string $serverVersion = "";

    public function getId(): int {
        return self::HANDSHAKE;
    }

    public function encode(PacketBuffer $buffer): void {
        $buffer->writeVarInt($this->protocolVersion);
        $buffer->writeString($this->clientId);
        $buffer->writeString($this->passwordHash);
        $buffer->writeByte($this->platform);
        $buffer->writeString($this->serverVersion);
    }

    public function decode(PacketBuffer $buffer): void {
        $this->protocolVersion = $buffer->readVarInt();
        $this->clientId = $buffer->readString();
        $this->passwordHash = $buffer->readString();
        $this->platform = $buffer->readByte();
        // Optional trailing serverVersion (v2+). Old v1 peers omit it.
        if ($buffer->remaining() > 0) {
            $this->serverVersion = $buffer->readString();
        } else {
            $this->serverVersion = "";
        }
    }
}
