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
 * - placeholders (map<string,string>): PlaceholderAPI variables
 */
class ChatMessagePacket extends Packet {
    
    public string $senderId = "";
    public string $senderName = "";
    public string $clientId = "";
    public string $channelId = "";
    public string $content = "";
    /** @var array<string, string> PlaceholderAPI variables (insertion order is preserved on re-encode) */
    public array $placeholders = [];
    
    public function getId(): int {
        return self::CHAT_MESSAGE;
    }
    
    public function encode(PacketBuffer $buffer): void {
        $buffer->writeUUID($this->senderId);
        $buffer->writeString($this->senderName);
        $buffer->writeString($this->clientId);
        $buffer->writeString($this->channelId);
        $buffer->writeString($this->content);
        // Placeholders map, matching the Java encoder byte-for-byte.
        $buffer->writeVarInt(count($this->placeholders));
        foreach ($this->placeholders as $key => $value) {
            $buffer->writeString((string) $key);
            $buffer->writeString((string) $value);
        }
    }
    
    public function decode(PacketBuffer $buffer): void {
        $this->senderId = $buffer->readUUID();
        $this->senderName = $buffer->readString(ProtocolLimits::MAX_SENDER_NAME);
        $this->clientId = $buffer->readString(ProtocolLimits::MAX_CLIENT_ID);
        $this->channelId = $buffer->readString(ProtocolLimits::MAX_CHANNEL_ID);
        $this->content = $buffer->readString(ProtocolLimits::MAX_MESSAGE_CONTENT);

        // Placeholders map (optional for legacy peers), kept like Java does.
        $this->placeholders = [];
        if ($buffer->remaining() <= 0) {
            return;
        }
        try {
            $size = $buffer->readVarInt();
        } catch (\Throwable $e) {
            // Legacy payload ended after content; treat as no placeholders.
            return;
        }
        if ($size < 0 || $size > 1000) {
            // Defensive: avoid OOM on corrupted frames (mirrors Java).
            return;
        }
        for ($i = 0; $i < $size; $i++) {
            $key = $buffer->readString(ProtocolLimits::MAX_METADATA_KEY);
            $this->placeholders[$key] = $buffer->readString(ProtocolLimits::MAX_METADATA_VALUE);
        }
    }
}
