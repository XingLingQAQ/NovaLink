<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Private message packet (Bidirectional) - cross-server /msg + /reply.
 *
 * Client -> Server: sender fields + targetName are filled; targetId may be the
 * nil UUID (00000000-0000-0000-0000-000000000000) — the backend resolves the
 * target by name. Server -> Client: the backend fills the real targetId and
 * the authoritative timestamp; the plugin renders the sent/received line
 * depending on which local player matches senderId/targetId.
 *
 * Wire:
 * - senderId (uuid): UUID of the sending player
 * - senderName (string): Display name of the sending player
 * - senderClientId (string): Client (game server) ID the sender uses
 * - targetName (string): Target player name as typed by the sender
 * - targetId (uuid): Target player UUID (nil C->S; real value S->C)
 * - content (string): Message content
 * - timestamp (long): epoch millis (server-authoritative S->C)
 */
class PrivateMessagePacket extends Packet {

    public string $senderId = "";
    public string $senderName = "";
    public string $senderClientId = "";
    public string $targetName = "";
    public string $targetId = "";
    public string $content = "";
    public int $timestamp = 0;

    public function getId(): int {
        return self::PRIVATE_MESSAGE;
    }

    public function encode(PacketBuffer $buffer): void {
        $buffer->writeUUID($this->senderId);
        $buffer->writeString($this->senderName);
        $buffer->writeString($this->senderClientId);
        $buffer->writeString($this->targetName);
        $buffer->writeUUID($this->targetId);
        $buffer->writeString($this->content);
        $buffer->writeLong($this->timestamp);
    }

    public function decode(PacketBuffer $buffer): void {
        $this->senderId = $buffer->readUUID();
        $this->senderName = $buffer->readString();
        $this->senderClientId = $buffer->readString();
        $this->targetName = $buffer->readString();
        $this->targetId = $buffer->readUUID();
        $this->content = $buffer->readString();
        $this->timestamp = $buffer->readLong();
    }
}
