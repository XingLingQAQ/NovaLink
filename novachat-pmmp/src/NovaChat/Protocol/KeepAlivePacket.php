<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Keep-alive packet for maintaining connection.
 * 
 * Requirements:
 * - 9.5: THE NovaChat-PMMP SHALL 每 15 秒发送心跳包维持连接
 * 
 * Fields:
 * - timestamp (long): Current timestamp in milliseconds
 */
class KeepAlivePacket extends Packet {
    
    public int $timestamp = 0;
    
    public function getId(): int {
        return self::KEEP_ALIVE;
    }
    
    public function encode(PacketBuffer $buffer): void {
        $buffer->writeLong($this->timestamp);
    }
    
    public function decode(PacketBuffer $buffer): void {
        $this->timestamp = $buffer->readLong();
    }
    
    /**
     * Creates a new keep-alive packet with current timestamp.
     * 
     * @return KeepAlivePacket The new packet
     */
    public static function create(): KeepAlivePacket {
        $packet = new KeepAlivePacket();
        $packet->timestamp = (int)(microtime(true) * 1000);
        return $packet;
    }
}
