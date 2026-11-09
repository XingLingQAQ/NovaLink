<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

class SpyMessagePacket extends Packet {
    public string $targetId = "";
    public ChatMessagePacket $message;

    public function __construct(?string $requestId = null) {
        parent::__construct($requestId);
        $this->message = new ChatMessagePacket();
    }

    public function getId(): int {
        return self::SPY_MESSAGE;
    }

    public function encode(PacketBuffer $buffer): void {
        $buffer->writeUUID($this->targetId);
        $this->message->encode($buffer);
    }

    public function decode(PacketBuffer $buffer): void {
        $this->targetId = $buffer->readUUID();
        $this->message = new ChatMessagePacket();
        $this->message->decode($buffer);
    }
}
