<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Chat message packet for sending and receiving chat messages.
 * 
 * Fields:
 * - senderId (uuid): UUID of the message sender
 * - senderName (string): Display name of the sender
 * - clientId (string): Client identifier
 * - channelId (string): Channel identifier
 * - content (string): Message content
 */
class ChatMessagePacket extends Packet {
    
    public string $senderId = "";
    public string $senderName = "";
    public string $clientId = "";
    public string $channelId = "";
    public string $content = "";
    
    public function getId(): int {
        return self::CHAT_MESSAGE;
    }
    
    public function encode(PacketBuffer $buffer): void {
        $buffer->writeUUID($this->senderId);
        $buffer->writeString($this->senderName);
        $buffer->writeString($this->clientId);
        $buffer->writeString($this->channelId);
        $buffer->writeString($this->content);
        // Placeholders map (optional). Keep empty for PMMP client.
        $buffer->writeVarInt(0);
    }
    
    public function decode(PacketBuffer $buffer): void {
        $this->senderId = $buffer->readUUID();
        $this->senderName = $buffer->readString();
        $this->clientId = $buffer->readString();
        $this->channelId = $buffer->readString();
        $this->content = $buffer->readString();
        // Consume optional placeholders map if present (ignore contents).
        if ($buffer->remaining() > 0) {
            try {
                $size = $buffer->readVarInt();
                for ($i = 0; $i < $size; $i++) {
                    $buffer->readString();
                    $buffer->readString();
                }
            } catch (\Throwable $e) {
                // best-effort
            }
        }
    }
}
