<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Handshake authenticate packet — third and final packet of the AUTH-002
 * challenge-response handshake (Client → Server). Packet ID: 0x17.
 *
 * The client echoes its own nonce (the one sent in HandshakeInitPacket) and
 * proves possession of the stored password hash by sending an HMAC-SHA-256
 * over (serverNonce . clientNonce), keyed by sha256hex(password).
 *
 * Wire (payload only):
 *   String  clientId      (≤ 64, must match the init packet's clientId)
 *   String  clientNonce   (≤ 64, must echo the init packet's clientNonce)
 *   String  hmac          (≤ 128, lowercase-hex HMAC-SHA-256)
 *
 * HMAC computation (must match JVM / Python / C++ forks byte-for-byte):
 *   key      = sha256hex(password)                 // stored credential hash (64 ASCII bytes)
 *   message  = serverNonceHex . clientNonceHex      // hex-string concatenation
 *   output   = hash_hmac('sha256', message, key)    // lowercase hex (64 chars)
 */
class HandshakeAuthenticatePacket extends Packet {

    public function getId(): int {
        return self::HANDSHAKE_AUTHENTICATE;
    }

    public string $clientId = "";
    public string $clientNonce = "";
    public string $hmac = "";

    public function encode(PacketBuffer $buffer): void {
        $buffer->writeString($this->clientId);
        $buffer->writeString($this->clientNonce);
        $buffer->writeString($this->hmac);
    }

    public function decode(PacketBuffer $buffer): void {
        $this->clientId = $buffer->readString(ProtocolLimits::MAX_CLIENT_ID);
        $this->clientNonce = $buffer->readString(ProtocolLimits::MAX_NONCE);
        $this->hmac = $buffer->readString(ProtocolLimits::MAX_HMAC);
    }
}
