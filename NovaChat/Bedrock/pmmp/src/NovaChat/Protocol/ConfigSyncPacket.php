<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Configuration sync packet (Server -> Client).
 *
 * Wire:
 * - configJson (string): JSON configuration data
 * - timestamp (long): sync timestamp (epoch millis)
 */
class ConfigSyncPacket extends Packet {

    public string $configJson = "{}";
    public int $timestamp = 0;

    public function getId(): int {
        return self::CONFIG_SYNC;
    }

    public function encode(PacketBuffer $buffer): void {
        $buffer->writeString($this->configJson === "" ? "{}" : $this->configJson);
        $buffer->writeLong($this->timestamp);
    }

    public function decode(PacketBuffer $buffer): void {
        $this->configJson = $buffer->readString();
        $this->timestamp = $buffer->readLong();
    }
}
