<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Handshake init packet — first packet of the AUTH-002 challenge-response
 * handshake (Client → Server). Packet ID: 0x15.
 *
 * Replaces the replayable static-hash HandshakePacket. The client sends a
 * fresh cryptographically-secure random nonce; the server replies with
 * HandshakeChallengePacket carrying its own nonce, and the client proves
 * possession of the password via an HMAC in HandshakeAuthenticatePacket.
 *
 * Wire (payload only; envelope written by Packet::serialize):
 *   VarInt  protocolVersion  (== HandshakePacket::PROTOCOL_VERSION, currently 3)
 *   String  clientId         (≤ 64)
 *   Byte    platform         (PlatformType id; 0x09 for PMMP)
 *   String  serverVersion    (≤ 64)
 *   String  clientNonce      (≤ 64, 16 random bytes lowercase-hex = 32 chars)
 *
 * Field order MUST stay in lockstep with the JVM HandshakeInitPacket
 * (NovaChat/common) and the Python/C++ forks.
 */
class HandshakeInitPacket extends Packet {

    public function getId(): int {
        return self::HANDSHAKE_INIT;
    }

    public int $protocolVersion = HandshakePacket::PROTOCOL_VERSION;
    public string $clientId = "";
    public int $platform = HandshakePacket::PLATFORM_PMMP;
    public string $serverVersion = "";
    public string $clientNonce = "";

    public function encode(PacketBuffer $buffer): void {
        $buffer->writeVarInt($this->protocolVersion);
        $buffer->writeString($this->clientId);
        $buffer->writeByte($this->platform);
        $buffer->writeString($this->serverVersion);
        $buffer->writeString($this->clientNonce);
    }

    public function decode(PacketBuffer $buffer): void {
        $this->protocolVersion = $buffer->readVarInt();
        $this->clientId = $buffer->readString(ProtocolLimits::MAX_CLIENT_ID);
        $this->platform = $buffer->readByte();
        // Trailing fields are optional on decode for partial-frame tolerance,
        // mirroring the JVM HandshakeInitPacket#read.
        if ($buffer->remaining() > 0) {
            $this->serverVersion = $buffer->readString(ProtocolLimits::MAX_SERVER_VERSION);
        } else {
            $this->serverVersion = "";
        }
        if ($buffer->remaining() > 0) {
            $this->clientNonce = $buffer->readString(ProtocolLimits::MAX_NONCE);
        } else {
            $this->clientNonce = "";
        }
    }
}
