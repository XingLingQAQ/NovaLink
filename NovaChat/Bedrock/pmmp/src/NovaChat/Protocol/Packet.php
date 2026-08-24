<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Base class for all NovaProtocol packets.
 * 
 * Requirements:
 * - 9.2: THE NovaChat-PMMP SHALL 实现所有核心数据包类型（Handshake、ChatMessage、ChannelAction、KeepAlive）
 */
abstract class Packet {
    
    // Packet IDs (must match Java PacketIds)
    public const HANDSHAKE = 0x01;
    public const HANDSHAKE_RESPONSE = 0x02;
    public const CHAT_MESSAGE = 0x03;
    public const CHANNEL_ACTION = 0x04;
    public const CHANNEL_ACTION_RESPONSE = 0x05;
    public const CONFIG_SYNC = 0x06;
    public const KEEP_ALIVE = 0x07;
    public const PLAYER_STATE = 0x08;
    public const TITLE = 0x09;
    /** @deprecated 0x0A AnnouncementPacket is obsolete; announcements now ride AdminAction STATUS + type=ANNOUNCE. Kept for protocol-history reference only — no factory arm, so an incoming 0x0A is treated as unknown. */
    public const ANNOUNCEMENT = 0x0A;
    public const ADMIN_ACTION = 0x0B;
    public const ADMIN_ACTION_RESPONSE = 0x0C;
    public const CHANNEL_UPDATE = 0x0D;
    public const ITEM_DISPLAY = 0x10;
    public const MENTION = 0x12;
    public const PRIVATE_MESSAGE = 0x14;

    // ==================== Challenge-response handshake (AUTH-002) ====================
    /** Handshake init (Client → Server). */
    public const HANDSHAKE_INIT = 0x15;
    /** Handshake challenge (Server → Client). */
    public const HANDSHAKE_CHALLENGE = 0x16;
    /** Handshake authenticate (Client → Server). */
    public const HANDSHAKE_AUTHENTICATE = 0x17;

    // Alias for backward compatibility
    public const TITLE_MESSAGE = self::TITLE;
    
    /** @var string UUID string with dashes */
    protected string $requestId;

    public function __construct(?string $requestId = null) {
        $this->requestId = $requestId ?? self::generateRequestId();
    }

    public function getRequestId(): string {
        return $this->requestId;
    }

    public function setRequestId(string $requestId): void {
        $this->requestId = $requestId;
    }
    
    /**
     * Gets the packet ID.
     * 
     * @return int The packet ID
     */
    abstract public function getId(): int;
    
    /**
     * Encodes the packet to a buffer.
     * 
     * @param PacketBuffer $buffer The buffer to write to
     */
    abstract public function encode(PacketBuffer $buffer): void;
    
    /**
     * Decodes the packet from a buffer.
     * 
     * @param PacketBuffer $buffer The buffer to read from
     */
    abstract public function decode(PacketBuffer $buffer): void;
    
    /**
     * Serializes the packet to bytes (with length prefix).
     * 
     * @return string The serialized packet bytes
     */
    public function serialize(): string {
        $buffer = new PacketBuffer();
        // NovaProtocol v1 framing:
        // | PacketId (Byte) | RequestId (UUID, 16 bytes) | Payload |
        $buffer->writeByte($this->getId());
        $buffer->writeUUID($this->requestId);
        $this->encode($buffer);
        
        $data = $buffer->getBuffer();
        $lengthPrefix = VarInt::write(strlen($data));
        
        return $lengthPrefix . $data;
    }
    
    /**
     * Creates a packet from raw bytes.
     * 
     * @param string $data The raw packet data (without length prefix)
     * @return Packet|null The decoded packet, or null if unknown
     */
    public static function fromBytes(string $data): ?Packet {
        $buffer = new PacketBuffer($data);
        $packetId = $buffer->readByte();
        $requestId = $buffer->readUUID();
        
        $packet = self::createPacket($packetId);
        if ($packet === null) {
            return null;
        }

        $packet->setRequestId($requestId);
        $packet->decode($buffer);
        return $packet;
    }
    
    /**
     * Creates a packet instance by ID.
     * 
     * @param int $packetId The packet ID
     * @return Packet|null The packet instance, or null if unknown
     */
    private static function createPacket(int $packetId): ?Packet {
        return match ($packetId) {
            self::HANDSHAKE => new HandshakePacket(),
            self::HANDSHAKE_RESPONSE => new HandshakeResponsePacket(),
            self::CHAT_MESSAGE => new ChatMessagePacket(),
            self::CHANNEL_ACTION => new ChannelActionPacket(),
            self::CHANNEL_ACTION_RESPONSE => new ChannelActionResponsePacket(),
            self::CONFIG_SYNC => new ConfigSyncPacket(),
            self::KEEP_ALIVE => new KeepAlivePacket(),
            self::CHANNEL_UPDATE => new ChannelUpdatePacket(),
            self::TITLE => new TitleMessagePacket(),
            // 0x0A AnnouncementPacket is deprecated and removed from the
            // factory: announcements are now sent as AdminAction STATUS with
            // type=ANNOUNCE, so the obsolete 0x0A wire id is treated as an
            // unknown packet by fromBytes().
            self::ADMIN_ACTION => new AdminActionPacket(),
            self::ADMIN_ACTION_RESPONSE => new AdminActionResponsePacket(),
            self::ITEM_DISPLAY => new ItemDisplayPacket(),
            self::MENTION => new MentionPacket(),
            self::PRIVATE_MESSAGE => new PrivateMessagePacket(),
            self::HANDSHAKE_INIT => new HandshakeInitPacket(),
            self::HANDSHAKE_CHALLENGE => new HandshakeChallengePacket(),
            self::HANDSHAKE_AUTHENTICATE => new HandshakeAuthenticatePacket(),
            default => null,
        };
    }

    private static function generateRequestId(): string {
        $bytes = random_bytes(16);
        // RFC4122 v4
        $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40);
        $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80);
        $hex = bin2hex($bytes);
        return sprintf(
            "%s-%s-%s-%s-%s",
            substr($hex, 0, 8),
            substr($hex, 8, 4),
            substr($hex, 12, 4),
            substr($hex, 16, 4),
            substr($hex, 20, 12)
        );
    }
}
