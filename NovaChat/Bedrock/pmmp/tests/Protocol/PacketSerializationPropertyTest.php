<?php

declare(strict_types=1);

namespace NovaChat\Tests\Protocol;

use Eris\Generator;
use Eris\TestTrait;
use NovaChat\I18n\I18n;
use NovaChat\Protocol\ChannelActionPacket;
use NovaChat\Protocol\ChannelActionResponsePacket;
use NovaChat\Protocol\ChatMessagePacket;
use NovaChat\Protocol\ConfigSyncPacket;
use NovaChat\Protocol\HandshakePacket;
use NovaChat\Protocol\HandshakeResponsePacket;
use NovaChat\Protocol\ItemDisplayPacket;
use NovaChat\Protocol\KeepAlivePacket;
use NovaChat\Protocol\MentionPacket;
use NovaChat\Protocol\Packet;
use NovaChat\Protocol\PacketBuffer;
use NovaChat\Protocol\ProtocolLimits;
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
     * Builds an Eris string generator whose output is guaranteed to stay
     * within the given byte budget. ProtocolLimits fields are enforced on
     * the decode side by `PacketBuffer::readString($maxLength)`; an
     * unbounded `Generator\string()` can exceed that and trip a length
     * exception that is a property-of-the-generator artifact, not a
     * serialization bug. We cap the generated length to the field limit
     * so the property only exercises the legal value domain.
     *
     * The eris `StringGenerator` grows with the quantifier `size` (up to
     * `withMaxSize(100)` here), so without a cap it routinely emits 65+-
     * byte strings. We wrap `Generator\string()` in `Generator\suchThat`
     * with a `strlen($s) <= $maxBytes` predicate so every generated (and
     * shrunk) value is decodable.
     */
    private function boundedString(int $maxBytes) {
        return Generator\suchThat(
            static function (string $s) use ($maxBytes): bool {
                return strlen($s) <= $maxBytes;
            },
            Generator\string()
        );
    }

    /**
     * Property 2: HandshakePacket Serialization Round-Trip
     *
     * For any valid HandshakePacket, serializing and deserializing should
     * produce an equivalent packet.
     *
     * Fields are bounded with the same ProtocolLimits constants the decode
     * side enforces (PROTO-003): clientId <= MAX_CLIENT_ID, passwordHash <=
     * MAX_PASSWORD_HASH. serverVersion is optional on decode and is covered
     * by the dedicated v2 round-trip test below.
     */
    public function testHandshakePacketRoundTrip(): void {
        $this->forAll(
            Generator\int(),
            $this->boundedString(ProtocolLimits::MAX_CLIENT_ID),
            $this->boundedString(ProtocolLimits::MAX_PASSWORD_HASH),
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
     *
     * String fields are bounded with the same ProtocolLimits constants the
     * decode side enforces (PROTO-003): senderName <= MAX_SENDER_NAME,
     * clientId <= MAX_CLIENT_ID, channelId <= MAX_CHANNEL_ID, content <=
     * MAX_MESSAGE_CONTENT.
     */
    public function testChatMessagePacketRoundTrip(): void {
        $this->forAll(
            $this->boundedString(ProtocolLimits::MAX_SENDER_NAME),
            $this->boundedString(ProtocolLimits::MAX_CLIENT_ID),
            $this->boundedString(ProtocolLimits::MAX_CHANNEL_ID),
            $this->boundedString(ProtocolLimits::MAX_MESSAGE_CONTENT)
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
     *
     * String fields are bounded with the same ProtocolLimits constants the
     * decode side enforces (PROTO-003): channelId <= MAX_CHANNEL_ID,
     * password <= MAX_CHANNEL_PASSWORD, extra key/value <= MAX_METADATA_KEY
     * / MAX_METADATA_VALUE.
     */
    public function testChannelActionPacketRoundTrip(): void {
        $this->forAll(
            Generator\choose(0, 255),
            $this->boundedString(ProtocolLimits::MAX_CHANNEL_ID),
            $this->boundedString(ProtocolLimits::MAX_CHANNEL_PASSWORD),
            $this->boundedString(ProtocolLimits::MAX_METADATA_KEY),
            $this->boundedString(ProtocolLimits::MAX_METADATA_VALUE)
        )
        ->withMaxSize(100)
        ->then(function (int $action, string $channelId, string $password, string $extraKey, string $extraValue): void {
            // Create original packet
            $original = new ChannelActionPacket();
            $original->action = $action;
            $original->channelId = $channelId;
            $original->password = $password;
            $original->extra = [$extraKey => $extraValue];

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

    /**
     * HandshakePacket protocol v2 — server_version field is encoded and
     * survives a round-trip.
     */
    public function testHandshakeV2ServerVersionRoundTrip(): void {
        $original = new HandshakePacket();
        $original->protocolVersion = HandshakePacket::PROTOCOL_VERSION;
        $original->clientId = "srv";
        $original->passwordHash = "abc";
        $original->platform = HandshakePacket::PLATFORM_PMMP;
        $original->serverVersion = "5.0.0";

        $serialized = $original->serialize();
        $buffer = new PacketBuffer($serialized);
        $length = $buffer->readVarInt();
        $data = $buffer->readBytes($length);

        $decoded = Packet::fromBytes($data);
        $this->assertInstanceOf(HandshakePacket::class, $decoded);
        $this->assertSame(HandshakePacket::PROTOCOL_VERSION, $decoded->protocolVersion);
        $this->assertSame("srv", $decoded->clientId);
        $this->assertSame("abc", $decoded->passwordHash);
        $this->assertSame(HandshakePacket::PLATFORM_PMMP, $decoded->platform);
        $this->assertSame("5.0.0", $decoded->serverVersion);
    }

    /**
     * HandshakePacket decode is backward-compatible with a v1-style payload
     * that omits the trailing server_version field.
     */
    public function testHandshakeDecodeWithoutServerVersionIsBackwardCompatible(): void {
        $buffer = new PacketBuffer();
        $buffer->writeVarInt(HandshakePacket::PROTOCOL_VERSION);
        $buffer->writeString("srv");
        $buffer->writeString("abc");
        $buffer->writeByte(HandshakePacket::PLATFORM_PMMP);
        $buffer->reset();

        $decoded = new HandshakePacket();
        $decoded->decode($buffer);
        $this->assertSame("", $decoded->serverVersion);
    }

    /**
     * HandshakeResponsePacket field order: success | errorCode | message
     * (errorCode BEFORE message, no configJson).
     */
    public function testHandshakeResponseFieldOrder(): void {
        $buffer = new PacketBuffer();
        $buffer->writeBoolean(true);
        $buffer->writeString("NC-200");
        $buffer->writeString("OK");
        $buffer->reset();

        $decoded = new HandshakeResponsePacket();
        $decoded->decode($buffer);
        $this->assertTrue($decoded->success);
        $this->assertSame("NC-200", $decoded->errorCode);
        $this->assertSame("OK", $decoded->message);
    }

    /**
     * ChannelAction constants are 0-based, matching the Java enum.
     */
    public function testChannelActionIdsMatchJava(): void {
        $this->assertSame(0, ChannelActionPacket::ACTION_JOIN);
        $this->assertSame(1, ChannelActionPacket::ACTION_LEAVE);
        $this->assertSame(2, ChannelActionPacket::ACTION_CREATE);
        $this->assertSame(3, ChannelActionPacket::ACTION_DELETE);
        $this->assertSame(4, ChannelActionPacket::ACTION_INVITE);
        $this->assertSame(5, ChannelActionPacket::ACTION_ACCEPT);
        $this->assertSame(6, ChannelActionPacket::ACTION_KICK);
        $this->assertSame(7, ChannelActionPacket::ACTION_MUTE);
        $this->assertSame(8, ChannelActionPacket::ACTION_UNMUTE);
        $this->assertSame(9, ChannelActionPacket::ACTION_BAN);
        $this->assertSame(10, ChannelActionPacket::ACTION_UNBAN);
        $this->assertSame(11, ChannelActionPacket::ACTION_WHO);
    }

    /**
     * ChannelActionResponsePacket round-trip with extra map.
     */
    public function testChannelActionResponseRoundTrip(): void {
        $original = new ChannelActionResponsePacket();
        $original->success = false;
        $original->action = ChannelActionPacket::ACTION_KICK;
        $original->channelId = "global";
        $original->errorCode = "NC-403";
        $original->message = "Forbidden";
        $original->extra = ["operatorName" => "Admin", "targetUuid" => "abc-123"];

        $serialized = $original->serialize();
        $buffer = new PacketBuffer($serialized);
        $length = $buffer->readVarInt();
        $data = $buffer->readBytes($length);

        $decoded = Packet::fromBytes($data);
        $this->assertInstanceOf(ChannelActionResponsePacket::class, $decoded);
        $this->assertFalse($decoded->success);
        $this->assertSame(ChannelActionPacket::ACTION_KICK, $decoded->action);
        $this->assertSame("global", $decoded->channelId);
        $this->assertSame("NC-403", $decoded->errorCode);
        $this->assertSame("Forbidden", $decoded->message);
        $this->assertSame(["operatorName" => "Admin", "targetUuid" => "abc-123"], $decoded->extra);
    }

    /**
     * ConfigSyncPacket round-trip.
     */
    public function testConfigSyncRoundTrip(): void {
        $original = new ConfigSyncPacket();
        $original->configJson = '{"channels":["global","local"]}';
        $original->timestamp = 12345;

        $serialized = $original->serialize();
        $buffer = new PacketBuffer($serialized);
        $length = $buffer->readVarInt();
        $data = $buffer->readBytes($length);

        $decoded = Packet::fromBytes($data);
        $this->assertInstanceOf(ConfigSyncPacket::class, $decoded);
        $this->assertSame('{"channels":["global","local"]}', $decoded->configJson);
        $this->assertSame(12345, $decoded->timestamp);
    }

    /**
     * MentionPacket round-trip.
     */
    public function testMentionRoundTrip(): void {
        $original = new MentionPacket();
        $original->mentionerId = self::generateUUID();
        $original->mentionerName = "Steve";
        $original->mentionedId = self::generateUUID();
        $original->channelId = "global";
        $original->messagePreview = "hi @Alex";
        $original->timestamp = 999;

        $serialized = $original->serialize();
        $buffer = new PacketBuffer($serialized);
        $length = $buffer->readVarInt();
        $data = $buffer->readBytes($length);

        $decoded = Packet::fromBytes($data);
        $this->assertInstanceOf(MentionPacket::class, $decoded);
        $this->assertSame("Steve", $decoded->mentionerName);
        $this->assertSame("global", $decoded->channelId);
        $this->assertSame("hi @Alex", $decoded->messagePreview);
        $this->assertSame(999, $decoded->timestamp);
    }

    /**
     * ItemDisplayPacket round-trip.
     */
    public function testItemDisplayRoundTrip(): void {
        $original = new ItemDisplayPacket();
        $original->senderId = self::generateUUID();
        $original->senderName = "Alex";
        $original->channelId = "local";
        $original->itemJson = '{"id":"diamond"}';
        $original->timestamp = 42;

        $serialized = $original->serialize();
        $buffer = new PacketBuffer($serialized);
        $length = $buffer->readVarInt();
        $data = $buffer->readBytes($length);

        $decoded = Packet::fromBytes($data);
        $this->assertInstanceOf(ItemDisplayPacket::class, $decoded);
        $this->assertSame("Alex", $decoded->senderName);
        $this->assertSame("local", $decoded->channelId);
        $this->assertSame('{"id":"diamond"}', $decoded->itemJson);
        $this->assertSame(42, $decoded->timestamp);
    }

    /**
     * I18n zh_CN default lookup with placeholder substitution.
     */
    public function testI18nZhCNLookup(): void {
        $i18n = new I18n();
        $msg = $i18n->get("chat.join.joined", "zh_CN", ["global"]);
        $this->assertStringContainsString("已加入频道", $msg);
        $this->assertStringContainsString("global", $msg);
    }

    /**
     * I18n en_US lookup.
     */
    public function testI18nEnUSLookup(): void {
        $i18n = new I18n();
        $msg = $i18n->get("chat.join.joined", "en_US", ["global"]);
        $this->assertStringContainsString("Joined channel", $msg);
        $this->assertStringContainsString("global", $msg);
    }

    /**
     * I18n falls back to the key itself when absent from both bundles.
     */
    public function testI18nFallbackToKey(): void {
        $i18n = new I18n();
        $msg = $i18n->get("nonexistent.key", "en_US");
        $this->assertSame("nonexistent.key", $msg);
    }

    /**
     * I18n error message combines message + suggestion.
     */
    public function testI18nErrorMessage(): void {
        $i18n = new I18n();
        $msg = $i18n->errorMessage("NC-404", "zh_CN");
        $this->assertStringContainsString("资源不存在", $msg);
        $this->assertStringContainsString("请检查频道ID或玩家名称是否正确", $msg);
    }

    /**
     * I18n kick/mute notice keys exist in both locales.
     */
    public function testI18nKickMuteNoticeKeysExist(): void {
        $i18n = new I18n();
        $this->assertStringContainsString("踢出", $i18n->get("chat.notice.kick_title", "zh_CN"));
        $this->assertStringContainsString("禁言", $i18n->get("chat.notice.mute_title", "zh_CN"));
        $this->assertStringContainsString("kicked", strtolower($i18n->get("chat.notice.kick_title", "en_US")));
        $this->assertStringContainsString("muted", strtolower($i18n->get("chat.notice.mute_title", "en_US")));
    }

    /**
     * I18n loads a brand-new locale (fr_FR) from an external lang file dropped
     * into resources/lang/, with {0} placeholder substitution and fallback to
     * zh_CN for keys absent from the external file.
     *
     * The I18n class has no constructor param or setter for the lang dir, so
     * this test writes a fr_FR.json into the on-disk resources/lang/ directory
     * the constructor scans, then deletes it in a finally block. The file is
     * namespaced with a test marker so a crash never leaves a stray locale.
     */
    public function testI18nExternalLocaleFileLoading(): void {
        // __DIR__ = .../pmmp/tests/Protocol -> up 2 = .../pmmp (plugin root).
        $langDir = dirname(__DIR__, 2) . "/resources/lang";
        $frFile = $langDir . "/fr_FR.test.json";
        // Write a fr_FR locale file (the .json stem becomes the locale key).
        if (!is_dir($langDir)) {
            @mkdir($langDir, 0777, true);
        }
        $data = [
            "chat.test.french" => "Bonjour {0}",
        ];
        try {
            file_put_contents($frFile, json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE));
            // Re-instantiate so the new file is scanned at construction.
            $i18n = new I18n();
            // The external locale resolves from the dropped file.
            $msg = $i18n->get("chat.test.french", "fr_FR.test", ["Monde"]);
            $this->assertStringContainsString("Bonjour", $msg);
            $this->assertStringContainsString("Monde", $msg);
            // A key absent from fr_FR but present in zh_CN falls back.
            $fallback = $i18n->get("chat.toggle.on", "fr_FR.test");
            $this->assertStringContainsString("聊天已开启", $fallback);
        } finally {
            if (file_exists($frFile)) {
                @unlink($frFile);
            }
        }
    }
}
