<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Handshake challenge packet — second packet of the AUTH-002 challenge-response
 * handshake (Server → Client). Packet ID: 0x16.
 *
 * The server generates a fresh 16-byte cryptographically-secure random nonce
 * and returns it to the client in response to HandshakeInitPacket. The client
 * combines this nonce with its own init nonce to compute the HMAC in
 * HandshakeAuthenticatePacket.
 *
 * Wire (payload only):
 *   String  serverNonce  (≤ 64, 16 random bytes lowercase-hex = 32 chars)
 */
class HandshakeChallengePacket extends Packet {

    public function getId(): int {
        return self::HANDSHAKE_CHALLENGE;
    }

    public string $serverNonce = "";

    public function encode(PacketBuffer $buffer): void {
        $buffer->writeString($this->serverNonce);
    }

    public function decode(PacketBuffer $buffer): void {
        $this->serverNonce = $buffer->readString(ProtocolLimits::MAX_NONCE);
    }
}
