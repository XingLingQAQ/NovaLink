<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Channel action response packet (Server -> Client).
 *
 * Wire:
 * - success (bool)
 * - action (byte): the ChannelAction this responds to
 * - channelId (string)
 * - errorCode (string)
 * - message (string)
 * - extra (map): extra key-value data (e.g. operatorName, targetUuid, duration)
 */
class ChannelActionResponsePacket extends Packet {

    public bool $success = false;
    public int $action = 0;
    public string $channelId = "";
    public string $errorCode = "";
    public string $message = "";
    /** @var array<string, string> */
    public array $extra = [];

    public function getId(): int {
        return self::CHANNEL_ACTION_RESPONSE;
    }

    public function encode(PacketBuffer $buffer): void {
        $buffer->writeBoolean($this->success);
        $buffer->writeByte($this->action);
        $buffer->writeString($this->channelId);
        $buffer->writeString($this->errorCode);
        $buffer->writeString($this->message);
        $buffer->writeVarInt(count($this->extra));
        foreach ($this->extra as $key => $value) {
            $buffer->writeString((string)$key);
            $buffer->writeString((string)$value);
        }
    }

    public function decode(PacketBuffer $buffer): void {
        $this->success = $buffer->readBoolean();
        $this->action = $buffer->readByte();
        $this->channelId = $buffer->readString(ProtocolLimits::MAX_CHANNEL_ID);
        $this->errorCode = $buffer->readString(ProtocolLimits::MAX_ERROR_CODE);
        $this->message = $buffer->readString(ProtocolLimits::MAX_ERROR_MESSAGE);
        if ($buffer->remaining() <= 0) {
            $this->extra = [];
            return;
        }
        $size = $buffer->readVarInt();
        $this->extra = [];
        for ($i = 0; $i < $size; $i++) {
            $key = $buffer->readString(ProtocolLimits::MAX_METADATA_KEY);
            $value = $buffer->readString(ProtocolLimits::MAX_METADATA_VALUE);
            $this->extra[$key] = $value;
        }
    }
}
