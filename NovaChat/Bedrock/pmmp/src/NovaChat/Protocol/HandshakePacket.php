<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Legacy static-hash handshake packet (protocol v1/v2). NOT sent on the wire
 * since AUTH-002 (protocol v3): the challenge-response flow now uses
 * HandshakeInitPacket / HandshakeChallengePacket / HandshakeAuthenticatePacket.
 *
 * This class is retained for golden-byte decode compatibility and to host the
 * shared PROTOCOL_VERSION / PLATFORM_PMMP constants referenced across the
 * new challenge-response packets.
 *
 * Wire (protocol v2, legacy):
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

    /**
     * Current protocol version. v3 switches to the AUTH-002 challenge-response
     * handshake (HMAC over server+client nonces); the legacy static-hash flow
     * is no longer sent. Kept as the shared version constant.
     */
    public const PROTOCOL_VERSION = 3;

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
        $this->clientId = $buffer->readString(ProtocolLimits::MAX_CLIENT_ID);
        $this->passwordHash = $buffer->readString(ProtocolLimits::MAX_PASSWORD_HASH);
        $this->platform = $buffer->readByte();
        // Optional trailing serverVersion (v2+). Old v1 peers omit it.
        if ($buffer->remaining() > 0) {
            $this->serverVersion = $buffer->readString(ProtocolLimits::MAX_SERVER_VERSION);
        } else {
            $this->serverVersion = "";
        }
    }
}
