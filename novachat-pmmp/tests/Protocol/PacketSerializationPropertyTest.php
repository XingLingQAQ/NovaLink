<?php

declare(strict_types=1);

namespace NovaChat\Tests\Protocol;

use Eris\Generator;
use Eris\TestTrait;
use NovaChat\Protocol\ChannelActionPacket;
use NovaChat\Protocol\ChatMessagePacket;
use NovaChat\Protocol\HandshakePacket;
use NovaChat\Protocol\KeepAlivePacket;
use NovaChat\Protocol\Packet;
use NovaChat\Protocol\PacketBuffer;
use PHPUnit\Framework\TestCase;

/**
 * Property-based tests for packet serialization/deserialization.
 * 
 * **Feature: novachat-platform-expansion, Property 2: Packet Serialization Round-Trip (Cross-Language)**
 * **Validates: Requirements 9.2**
 * 
 * For any valid packet, serializing and then deserializing should produce
 * an equivalent packet object.
 */
class PacketSerializationPropertyTest extends TestCase {
    use TestTrait;

    /**
     * Generates a valid UUID string.
     */
    private static function generateUUID(): string {
        return sprintf(
            '%08x-%04x-%04x-%04x-%012x',
            mt_rand(0, 0xffffffff),
            mt_rand(0, 0xffff),
            mt_rand(0, 0x0fff) | 0x4000,
            mt_rand(0, 0x3fff) | 0x8000,
            mt_rand(0, 0xffffffffffff)
        );
    }

    /**
     * Property 2: HandshakePacket Serialization Round-Trip
     * 
     * For any valid HandshakePacket, serializing and deserializing should
     * produce an equivalent packet.
     */
    public function testHandshakePacketRoundTrip(): void {
        $this->forAll(
            Generator\int(),
            Generator\string(),
            Generator\string(),
            Generator\choose(0, 255)
        )
        ->withMaxSize(100)
        ->then(function (int $protocolVersion, string $clientId, string $passwordHash, int $platform): void {
            // Create original packet
            $original = new HandshakePacket();
            $original->protocolVersion = $protocolVersion;
            $original->clientId = $clientId;
            $original->passwordHash = $passwordHash;
            $original->platform = $platform;
            
            // Serialize
            $serialized = $original->serialize();
            
            // Deserialize (skip length prefix)
            $buffer = new PacketBuffer($serialized);
            $length = $buffer->readVarInt();
            $data = $buffer->readBytes($length);
            
            $decoded = Packet::fromBytes($data);
            
            // Assert
            $this->assertInstanceOf(HandshakePacket::class, $decoded);
            $this->assertSame($original->protocolVersion, $decoded->protocolVersion);
            $this->assertSame($original->clientId, $decoded->clientId);
            $this->assertSame($original->passwordHash, $decoded->passwordHash);
            $this->assertSame($original->platform, $decoded->platform);
        });
    }

    /**
     * Property 2: ChatMessagePacket Serialization Round-Trip
     * 
     * For any valid ChatMessagePacket, serializing and deserializing should
     * produce an equivalent packet.
     */
    public function testChatMessagePacketRoundTrip(): void {
        $this->forAll(
            Generator\string(),
            Generator\string(),
            Generator\string(),
            Generator\string()
        )
        ->withMaxSize(100)
        ->then(function (string $senderName, string $clientId, string $channelId, string $content): void {
            // Create original packet with a valid UUID
            $original = new ChatMessagePacket();
            $original->senderId = self::generateUUID();
            $original->senderName = $senderName;
            $original->clientId = $clientId;
            $original->channelId = $channelId;
            $original->content = $content;
            
            // Serialize
            $serialized = $original->serialize();
            
            // Deserialize (skip length prefix)
            $buffer = new PacketBuffer($serialized);
            $length = $buffer->readVarInt();
            $data = $buffer->readBytes($length);
            
            $decoded = Packet::fromBytes($data);
            
            // Assert
            $this->assertInstanceOf(ChatMessagePacket::class, $decoded);
            $this->assertSame($original->senderId, $decoded->senderId);
            $this->assertSame($original->senderName, $decoded->senderName);
            $this->assertSame($original->clientId, $decoded->clientId);
            $this->assertSame($original->channelId, $decoded->channelId);
            $this->assertSame($original->content, $decoded->content);
        });
    }

    /**
     * Property 2: ChannelActionPacket Serialization Round-Trip
     * 
     * For any valid ChannelActionPacket, serializing and deserializing should
     * produce an equivalent packet.
     */
    public function testChannelActionPacketRoundTrip(): void {
        $this->forAll(
            Generator\choose(0, 255),
            Generator\string(),
            Generator\string(),
            Generator\string()
        )
        ->withMaxSize(100)
        ->then(function (int $action, string $channelId, string $password, string $extra): void {
            // Create original packet
            $original = new ChannelActionPacket();
            $original->action = $action;
            $original->channelId = $channelId;
            $original->password = $password;
            $original->extra = $extra;
            
            // Serialize
            $serialized = $original->serialize();
            
            // Deserialize (skip length prefix)
            $buffer = new PacketBuffer($serialized);
            $length = $buffer->readVarInt();
            $data = $buffer->readBytes($length);
            
            $decoded = Packet::fromBytes($data);
            
            // Assert
            $this->assertInstanceOf(ChannelActionPacket::class, $decoded);
            $this->assertSame($original->action, $decoded->action);
            $this->assertSame($original->channelId, $decoded->channelId);
            $this->assertSame($original->password, $decoded->password);
            $this->assertSame($original->extra, $decoded->extra);
        });
    }

    /**
     * Property 2: KeepAlivePacket Serialization Round-Trip
     * 
     * For any valid KeepAlivePacket, serializing and deserializing should
     * produce an equivalent packet.
     */
    public function testKeepAlivePacketRoundTrip(): void {
        $this->forAll(
            Generator\int()
        )
        ->withMaxSize(100)
        ->then(function (int $timestamp): void {
            // Create original packet
            $original = new KeepAlivePacket();
            $original->timestamp = $timestamp;
            
            // Serialize
            $serialized = $original->serialize();
            
            // Deserialize (skip length prefix)
            $buffer = new PacketBuffer($serialized);
            $length = $buffer->readVarInt();
            $data = $buffer->readBytes($length);
            
            $decoded = Packet::fromBytes($data);
            
            // Assert
            $this->assertInstanceOf(KeepAlivePacket::class, $decoded);
            $this->assertSame($original->timestamp, $decoded->timestamp);
        });
    }

    /**
     * Property: Packet ID is preserved through serialization.
     * 
     * For any packet, the packet ID should be preserved after serialization
     * and deserialization.
     */
    public function testPacketIdPreserved(): void {
        $packets = [
            new HandshakePacket(),
            new ChatMessagePacket(),
            new ChannelActionPacket(),
            new KeepAlivePacket(),
        ];
        
        foreach ($packets as $original) {
            // Set valid UUID for ChatMessagePacket
            if ($original instanceof ChatMessagePacket) {
                $original->senderId = self::generateUUID();
            }
            
            // Serialize
            $serialized = $original->serialize();
            
            // Deserialize (skip length prefix)
            $buffer = new PacketBuffer($serialized);
            $length = $buffer->readVarInt();
            $data = $buffer->readBytes($length);
            
            $decoded = Packet::fromBytes($data);
            
            // Assert packet ID is preserved
            $this->assertSame(
                $original->getId(),
                $decoded->getId(),
                "Packet ID not preserved for " . get_class($original)
            );
        }
    }
}
