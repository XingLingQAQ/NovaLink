#pragma once

#include "PacketBuffer.h"
#include "PacketIds.h"
#include "ProtocolLimits.h"
#include <memory>
#include <unordered_map>
#include <string>
#include <utility>
#include <vector>

namespace novachat::protocol {

/**
 * Base class for all NovaProtocol packets.
 *
 * NovaProtocol frame: | Length (VarInt) | PacketID (Byte) | RequestID (UUID) | Payload |
 */
class Packet {
public:
    Packet() : mRequestId(UUID::random()) {}
    explicit Packet(UUID requestId) : mRequestId(requestId) {}
    virtual ~Packet() = default;

    [[nodiscard]] virtual uint8_t getPacketId() const = 0;
    virtual void write(PacketBuffer& buf) const = 0;
    virtual void read(PacketBuffer& buf) = 0;

    [[nodiscard]] const UUID& getRequestId() const { return mRequestId; }
    void setRequestId(const UUID& requestId) { mRequestId = requestId; }

protected:
    UUID mRequestId;
};

// ==================== Packet Implementations ====================
// Field order and types must match the Java packets in
// NovaChat/common/.../protocol/packets/ exactly.

/**
 * Handshake packet sent by client to authenticate (protocol v2).
 * Packet ID: 0x01, Direction: Client -> Server
 *
 * Wire: varint protocolVersion | string clientId | string passwordHash |
 *       byte platform | string serverVersion (v2 trailing field)
 */
class HandshakePacket : public Packet {
public:
    HandshakePacket() = default;
    HandshakePacket(int32_t protocolVersion, const std::string& clientId,
                    const std::string& passwordHash, PlatformType platform,
                    const std::string& serverVersion = "")
        : mProtocolVersion(protocolVersion)
        , mClientId(clientId)
        , mPasswordHash(passwordHash)
        , mPlatform(platform)
        , mServerVersion(serverVersion) {}

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::HANDSHAKE; }

    void write(PacketBuffer& buf) const override {
        buf.writeVarInt(mProtocolVersion);
        buf.writeString(mClientId);
        buf.writeString(mPasswordHash);
        buf.writeByte(static_cast<uint8_t>(mPlatform));
        buf.writeString(mServerVersion);
    }

    void read(PacketBuffer& buf) override {
        mProtocolVersion = buf.readVarInt();
        mClientId = buf.readString(ProtocolLimits::MAX_CLIENT_ID);
        mPasswordHash = buf.readString(ProtocolLimits::MAX_PASSWORD_HASH);
        mPlatform = static_cast<PlatformType>(buf.readByte());
        // Optional trailing field (protocol v2+); old v1 peers omit it.
        if (buf.readableBytes() > 0) {
            mServerVersion = buf.readString(ProtocolLimits::MAX_SERVER_VERSION);
        } else {
            mServerVersion.clear();
        }
    }

    [[nodiscard]] int32_t getProtocolVersion() const { return mProtocolVersion; }
    [[nodiscard]] const std::string& getClientId() const { return mClientId; }
    [[nodiscard]] const std::string& getPasswordHash() const { return mPasswordHash; }
    [[nodiscard]] PlatformType getPlatform() const { return mPlatform; }
    [[nodiscard]] const std::string& getServerVersion() const { return mServerVersion; }

private:
    int32_t mProtocolVersion = PROTOCOL_VERSION;
    std::string mClientId;
    std::string mPasswordHash;
    PlatformType mPlatform = PlatformType::LEVILAMINA;
    std::string mServerVersion;
};

/**
 * Handshake init (Client -> Server), protocol v3 AUTH-002.
 * Packet ID: 0x15
 *
 * Wire: varint protocolVersion | string clientId | byte platform |
 *       string serverVersion | string clientNonce
 *
 * Replaces the legacy 0x01 HandshakePacket. The clientNonce is 16
 * cryptographically-secure random bytes hex (32 chars); the HMAC exchanged
 * in HandshakeAuthenticate binds it to the server's challenge nonce so the
 * password is never replayed as a static hash.
 *
 * Field order and types must match the Java HandshakeInitPacket exactly.
 * platform is the PlatformType wire ID (getId(), not ordinal).
 */
class HandshakeInitPacket : public Packet {
public:
    HandshakeInitPacket() = default;
    HandshakeInitPacket(int32_t protocolVersion, const std::string& clientId,
                        PlatformType platform, const std::string& serverVersion,
                        const std::string& clientNonce)
        : mProtocolVersion(protocolVersion)
        , mClientId(clientId)
        , mPlatform(platform)
        , mServerVersion(serverVersion)
        , mClientNonce(clientNonce) {}

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::HANDSHAKE_INIT; }

    void write(PacketBuffer& buf) const override {
        buf.writeVarInt(mProtocolVersion);
        buf.writeString(mClientId);
        buf.writeByte(static_cast<uint8_t>(mPlatform));
        buf.writeString(mServerVersion);
        buf.writeString(mClientNonce);
    }

    void read(PacketBuffer& buf) override {
        mProtocolVersion = buf.readVarInt();
        mClientId = buf.readString(ProtocolLimits::MAX_CLIENT_ID);
        mPlatform = static_cast<PlatformType>(buf.readByte());
        // serverVersion + clientNonce are optional trailing fields (tolerant
        // decode mirrors the Java reader so a partial frame still decodes).
        if (buf.readableBytes() > 0) {
            mServerVersion = buf.readString(ProtocolLimits::MAX_SERVER_VERSION);
            if (buf.readableBytes() > 0) {
                mClientNonce = buf.readString(ProtocolLimits::MAX_NONCE);
            } else {
                mClientNonce.clear();
            }
        } else {
            mServerVersion.clear();
            mClientNonce.clear();
        }
    }

    [[nodiscard]] int32_t getProtocolVersion() const { return mProtocolVersion; }
    [[nodiscard]] const std::string& getClientId() const { return mClientId; }
    [[nodiscard]] PlatformType getPlatform() const { return mPlatform; }
    [[nodiscard]] const std::string& getServerVersion() const { return mServerVersion; }
    [[nodiscard]] const std::string& getClientNonce() const { return mClientNonce; }

private:
    int32_t mProtocolVersion = PROTOCOL_VERSION;
    std::string mClientId;
    PlatformType mPlatform = PlatformType::LEVILAMINA;
    std::string mServerVersion;
    std::string mClientNonce;
};

/**
 * Handshake challenge (Server -> Client), protocol v3 AUTH-002.
 * Packet ID: 0x16
 *
 * Wire: string serverNonce (16 random bytes hex, 32 chars)
 */
class HandshakeChallengePacket : public Packet {
public:
    HandshakeChallengePacket() = default;
    explicit HandshakeChallengePacket(const std::string& serverNonce)
        : mServerNonce(serverNonce) {}

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::HANDSHAKE_CHALLENGE; }

    void write(PacketBuffer& buf) const override {
        buf.writeString(mServerNonce);
    }

    void read(PacketBuffer& buf) override {
        mServerNonce = buf.readString(ProtocolLimits::MAX_NONCE);
    }

    [[nodiscard]] const std::string& getServerNonce() const { return mServerNonce; }

private:
    std::string mServerNonce;
};

/**
 * Handshake authenticate (Client -> Server), protocol v3 AUTH-002.
 * Packet ID: 0x17
 *
 * Wire: string clientId | string clientNonce | string hmac
 *
 * hmac = HMAC-SHA256(key = sha256hex(password), message = serverNonce || clientNonce),
 * output lowercase hex (see util/HmacSha256). The clientNonce here echoes the
 * nonce the client sent in HandshakeInit; the server pairs it with the
 * serverNonce it issued in HandshakeChallenge.
 */
class HandshakeAuthenticatePacket : public Packet {
public:
    HandshakeAuthenticatePacket() = default;
    HandshakeAuthenticatePacket(const std::string& clientId, const std::string& clientNonce,
                                const std::string& hmac)
        : mClientId(clientId), mClientNonce(clientNonce), mHmac(hmac) {}

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::HANDSHAKE_AUTHENTICATE; }

    void write(PacketBuffer& buf) const override {
        buf.writeString(mClientId);
        buf.writeString(mClientNonce);
        buf.writeString(mHmac);
    }

    void read(PacketBuffer& buf) override {
        mClientId = buf.readString(ProtocolLimits::MAX_CLIENT_ID);
        mClientNonce = buf.readString(ProtocolLimits::MAX_NONCE);
        mHmac = buf.readString(ProtocolLimits::MAX_HMAC);
    }

    [[nodiscard]] const std::string& getClientId() const { return mClientId; }
    [[nodiscard]] const std::string& getClientNonce() const { return mClientNonce; }
    [[nodiscard]] const std::string& getHmac() const { return mHmac; }

private:
    std::string mClientId;
    std::string mClientNonce;
    std::string mHmac;
};

/**
 * Handshake response from server.
 * Packet ID: 0x02, Direction: Server -> Client
 *
 * Wire: bool success | string errorCode | string message
 * (NOTE: errorCode BEFORE message, matching the Java packet.)
 */
class HandshakeResponsePacket : public Packet {
public:
    HandshakeResponsePacket() = default;

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::HANDSHAKE_RESPONSE; }

    void write(PacketBuffer& buf) const override {
        buf.writeBoolean(mSuccess);
        buf.writeString(mErrorCode);
        buf.writeString(mMessage);
    }

    void read(PacketBuffer& buf) override {
        mSuccess = buf.readBoolean();
        mErrorCode = buf.readString(ProtocolLimits::MAX_ERROR_CODE);
        mMessage = buf.readString(ProtocolLimits::MAX_ERROR_MESSAGE);
    }

    [[nodiscard]] bool isSuccess() const { return mSuccess; }
    [[nodiscard]] const std::string& getErrorCode() const { return mErrorCode; }
    [[nodiscard]] const std::string& getMessage() const { return mMessage; }

private:
    bool mSuccess = false;
    std::string mErrorCode;
    std::string mMessage;
};

/**
 * Chat message packet.
 * Packet ID: 0x03, Direction: Bidirectional
 *
 * Wire: uuid senderId | string senderName | string clientId |
 *       string channelId | string content | varint placeholdersCount |
 *       (string key | string value) * placeholdersCount
 */
class ChatMessagePacket : public Packet {
public:
    ChatMessagePacket() = default;
    ChatMessagePacket(const UUID& senderId, const std::string& senderName,
                      const std::string& clientId, const std::string& channelId,
                      const std::string& content)
        : mSenderId(senderId)
        , mSenderName(senderName)
        , mClientId(clientId)
        , mChannelId(channelId)
        , mContent(content) {}

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::CHAT_MESSAGE; }

    void write(PacketBuffer& buf) const override {
        buf.writeUUID(mSenderId);
        buf.writeString(mSenderName);
        buf.writeString(mClientId);
        buf.writeString(mChannelId);
        buf.writeString(mContent);
        // Placeholders map, matching the Java encoder byte-for-byte.
        buf.writeVarInt(static_cast<int32_t>(mPlaceholders.size()));
        for (const auto& kv : mPlaceholders) {
            buf.writeString(kv.first);
            buf.writeString(kv.second);
        }
    }

    void read(PacketBuffer& buf) override {
        mSenderId = buf.readUUID();
        mSenderName = buf.readString(ProtocolLimits::MAX_SENDER_NAME);
        mClientId = buf.readString(ProtocolLimits::MAX_CLIENT_ID);
        mChannelId = buf.readString(ProtocolLimits::MAX_CHANNEL_ID);
        mContent = buf.readString(ProtocolLimits::MAX_MESSAGE_CONTENT);
        // Placeholders map (optional for legacy peers), kept like Java does.
        // vector<pair> preserves wire order so re-encode is byte-stable.
        mPlaceholders.clear();
        if (buf.readableBytes() > 0) {
            int32_t size = buf.readVarInt();
            if (size >= 0 && size <= 1000) { // defensive bound, mirrors Java
                mPlaceholders.reserve(static_cast<size_t>(size));
                for (int32_t i = 0; i < size; ++i) {
                    std::string key = buf.readString(ProtocolLimits::MAX_METADATA_KEY);
                    std::string value = buf.readString(ProtocolLimits::MAX_METADATA_VALUE);
                    mPlaceholders.emplace_back(std::move(key), std::move(value));
                }
            }
        }
    }

    [[nodiscard]] const UUID& getSenderId() const { return mSenderId; }
    [[nodiscard]] const std::string& getSenderName() const { return mSenderName; }
    [[nodiscard]] const std::string& getClientId() const { return mClientId; }
    [[nodiscard]] const std::string& getChannelId() const { return mChannelId; }
    [[nodiscard]] const std::string& getContent() const { return mContent; }
    [[nodiscard]] const std::vector<std::pair<std::string, std::string>>& getPlaceholders() const {
        return mPlaceholders;
    }

    void setPlaceholders(std::vector<std::pair<std::string, std::string>> placeholders) {
        mPlaceholders = std::move(placeholders);
    }

    void addPlaceholder(const std::string& key, const std::string& value) {
        mPlaceholders.emplace_back(key, value);
    }

private:
    UUID mSenderId;
    std::string mSenderName;
    std::string mClientId;
    std::string mChannelId;
    std::string mContent;
    std::vector<std::pair<std::string, std::string>> mPlaceholders;
};

/**
 * Keep-alive heartbeat packet.
 * Packet ID: 0x07, Direction: Bidirectional
 * Wire: long timestamp
 */
class KeepAlivePacket : public Packet {
public:
    KeepAlivePacket() : mTimestamp(0) {}
    explicit KeepAlivePacket(int64_t timestamp) : mTimestamp(timestamp) {}

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::KEEP_ALIVE; }

    void write(PacketBuffer& buf) const override {
        buf.writeLong(mTimestamp);
    }

    void read(PacketBuffer& buf) override {
        mTimestamp = buf.readLong();
    }

    [[nodiscard]] int64_t getTimestamp() const { return mTimestamp; }

private:
    int64_t mTimestamp;
};

/**
 * Channel action packet.
 * Packet ID: 0x04, Direction: Client -> Server
 *
 * Wire: byte action | string channelId | string password |
 *       varint extraCount | (string key | string value) * extraCount
 */
class ChannelActionPacket : public Packet {
public:
    ChannelActionPacket() = default;
    ChannelActionPacket(ChannelAction action, const std::string& channelId,
                        const std::string& password = "")
        : mAction(action), mChannelId(channelId), mPassword(password) {}

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::CHANNEL_ACTION; }

    void write(PacketBuffer& buf) const override {
        buf.writeByte(static_cast<uint8_t>(mAction));
        buf.writeString(mChannelId);
        buf.writeString(mPassword);
        buf.writeVarInt(static_cast<int32_t>(mExtra.size()));
        for (const auto& kv : mExtra) {
            buf.writeString(kv.first);
            buf.writeString(kv.second);
        }
    }

    void read(PacketBuffer& buf) override {
        mAction = static_cast<ChannelAction>(buf.readByte());
        mChannelId = buf.readString(ProtocolLimits::MAX_CHANNEL_ID);
        mPassword = buf.readString(ProtocolLimits::MAX_CHANNEL_PASSWORD);
        // Extra map (optional for legacy implementations).
        if (buf.readableBytes() <= 0) {
            mExtra.clear();
            return;
        }
        int32_t size = buf.readVarInt();
        mExtra.clear();
        for (int32_t i = 0; i < size; ++i) {
            std::string key = buf.readString(ProtocolLimits::MAX_METADATA_KEY);
            std::string value = buf.readString(ProtocolLimits::MAX_METADATA_VALUE);
            mExtra.emplace(std::move(key), std::move(value));
        }
    }

    [[nodiscard]] ChannelAction getAction() const { return mAction; }
    [[nodiscard]] const std::string& getChannelId() const { return mChannelId; }
    [[nodiscard]] const std::string& getPassword() const { return mPassword; }
    [[nodiscard]] const std::unordered_map<std::string, std::string>& getExtra() const { return mExtra; }

    void addExtra(const std::string& key, const std::string& value) {
        mExtra.emplace(key, value);
    }

private:
    ChannelAction mAction = ChannelAction::JOIN;
    std::string mChannelId;
    std::string mPassword;
    std::unordered_map<std::string, std::string> mExtra;
};

/**
 * Channel action response packet.
 * Packet ID: 0x05, Direction: Server -> Client
 *
 * Wire: bool success | byte action | string channelId |
 *       string errorCode | string message |
 *       varint extraCount | (string key | string value) * extraCount
 */
class ChannelActionResponsePacket : public Packet {
public:
    ChannelActionResponsePacket() = default;

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::CHANNEL_ACTION_RESPONSE; }

    void write(PacketBuffer& buf) const override {
        buf.writeBoolean(mSuccess);
        buf.writeByte(static_cast<uint8_t>(mAction));
        buf.writeString(mChannelId);
        buf.writeString(mErrorCode);
        buf.writeString(mMessage);
        buf.writeVarInt(static_cast<int32_t>(mExtra.size()));
        for (const auto& kv : mExtra) {
            buf.writeString(kv.first);
            buf.writeString(kv.second);
        }
    }

    void read(PacketBuffer& buf) override {
        mSuccess = buf.readBoolean();
        mAction = static_cast<ChannelAction>(buf.readByte());
        mChannelId = buf.readString(ProtocolLimits::MAX_CHANNEL_ID);
        mErrorCode = buf.readString(ProtocolLimits::MAX_ERROR_CODE);
        mMessage = buf.readString(ProtocolLimits::MAX_ERROR_MESSAGE);
        if (buf.readableBytes() <= 0) {
            mExtra.clear();
            return;
        }
        int32_t size = buf.readVarInt();
        mExtra.clear();
        for (int32_t i = 0; i < size; ++i) {
            std::string key = buf.readString(ProtocolLimits::MAX_METADATA_KEY);
            std::string value = buf.readString(ProtocolLimits::MAX_METADATA_VALUE);
            mExtra.emplace(std::move(key), std::move(value));
        }
    }

    [[nodiscard]] bool isSuccess() const { return mSuccess; }
    [[nodiscard]] ChannelAction getAction() const { return mAction; }
    [[nodiscard]] const std::string& getChannelId() const { return mChannelId; }
    [[nodiscard]] const std::string& getErrorCode() const { return mErrorCode; }
    [[nodiscard]] const std::string& getMessage() const { return mMessage; }
    [[nodiscard]] const std::unordered_map<std::string, std::string>& getExtra() const { return mExtra; }

private:
    bool mSuccess = false;
    ChannelAction mAction = ChannelAction::JOIN;
    std::string mChannelId;
    std::string mErrorCode;
    std::string mMessage;
    std::unordered_map<std::string, std::string> mExtra;
};

/**
 * Configuration sync packet (Server -> Client).
 * Packet ID: 0x06
 * Wire: string configJson | long timestamp
 */
class ConfigSyncPacket : public Packet {
public:
    ConfigSyncPacket() = default;
    ConfigSyncPacket(const std::string& configJson, int64_t timestamp)
        : mConfigJson(configJson), mTimestamp(timestamp) {}

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::CONFIG_SYNC; }

    void write(PacketBuffer& buf) const override {
        // Java only normalizes null configJson to "{}" (the member default
        // here); an explicit empty string must be preserved on the wire.
        buf.writeString(mConfigJson);
        buf.writeLong(mTimestamp);
    }

    void read(PacketBuffer& buf) override {
        mConfigJson = buf.readString(ProtocolLimits::MAX_CONFIG_SYNC_JSON);
        mTimestamp = buf.readLong();
    }

    [[nodiscard]] const std::string& getConfigJson() const { return mConfigJson; }
    [[nodiscard]] int64_t getTimestamp() const { return mTimestamp; }

private:
    std::string mConfigJson = "{}";
    int64_t mTimestamp = 0;
};

/**
 * Title message packet (Server -> Client).
 * Packet ID: 0x09
 * Wire: string channelId | string title | string subtitle |
 *       int fadeIn | int stay | int fadeOut | uuid senderId
 */
class TitlePacket : public Packet {
public:
    TitlePacket() { mFadeIn = 10; mStay = 70; mFadeOut = 20; }

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::TITLE; }

    void write(PacketBuffer& buf) const override {
        buf.writeString(mChannelId);
        buf.writeString(mTitle);
        buf.writeString(mSubtitle);
        buf.writeInt(mFadeIn);
        buf.writeInt(mStay);
        buf.writeInt(mFadeOut);
        buf.writeUUID(mSenderId);
    }

    void read(PacketBuffer& buf) override {
        mChannelId = buf.readString(ProtocolLimits::MAX_CHANNEL_ID);
        mTitle = buf.readString(ProtocolLimits::MAX_TITLE);
        mSubtitle = buf.readString(ProtocolLimits::MAX_SUBTITLE);
        mFadeIn = buf.readInt();
        mStay = buf.readInt();
        mFadeOut = buf.readInt();
        mSenderId = buf.readUUID();
    }

    [[nodiscard]] const std::string& getChannelId() const { return mChannelId; }
    [[nodiscard]] const std::string& getTitle() const { return mTitle; }
    [[nodiscard]] const std::string& getSubtitle() const { return mSubtitle; }
    [[nodiscard]] int32_t getFadeIn() const { return mFadeIn; }
    [[nodiscard]] int32_t getStay() const { return mStay; }
    [[nodiscard]] int32_t getFadeOut() const { return mFadeOut; }
    [[nodiscard]] const UUID& getSenderId() const { return mSenderId; }

private:
    std::string mChannelId;
    std::string mTitle;
    std::string mSubtitle;
    int32_t mFadeIn = 10;
    int32_t mStay = 70;
    int32_t mFadeOut = 20;
    UUID mSenderId;
};

/**
 * Admin action packet (Client -> Server).
 * Packet ID: 0x0B
 * Wire: byte action | uuid playerId | string passwordHash | string target |
 *       varint extraCount | (string key | string value) * extraCount
 */
class AdminActionPacket : public Packet {
public:
    AdminActionPacket() = default;

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::ADMIN_ACTION; }

    void write(PacketBuffer& buf) const override {
        buf.writeByte(static_cast<uint8_t>(mAction));
        buf.writeUUID(mPlayerId);
        buf.writeString(mPasswordHash);
        buf.writeString(mTarget);
        buf.writeVarInt(static_cast<int32_t>(mExtra.size()));
        for (const auto& kv : mExtra) {
            buf.writeString(kv.first);
            buf.writeString(kv.second);
        }
    }

    void read(PacketBuffer& buf) override {
        mAction = static_cast<AdminAction>(buf.readByte());
        mPlayerId = buf.readUUID();
        mPasswordHash = buf.readString(ProtocolLimits::MAX_PASSWORD_HASH);
        mTarget = buf.readString(ProtocolLimits::MAX_CHANNEL_ID);
        int32_t size = buf.readVarInt();
        mExtra.clear();
        for (int32_t i = 0; i < size; ++i) {
            std::string key = buf.readString(ProtocolLimits::MAX_METADATA_KEY);
            std::string value = buf.readString(ProtocolLimits::MAX_METADATA_VALUE);
            mExtra.emplace(std::move(key), std::move(value));
        }
    }

    [[nodiscard]] AdminAction getAction() const { return mAction; }
    [[nodiscard]] const UUID& getPlayerId() const { return mPlayerId; }
    [[nodiscard]] const std::string& getPasswordHash() const { return mPasswordHash; }
    [[nodiscard]] const std::string& getTarget() const { return mTarget; }
    // Extra map accessors (parity with ChannelActionPacket). The /nc announce
    // path stashes type/operatorName/content here so the backend handleStatus
    // dispatch (handleAnnounce) can route the broadcast without a dedicated
    // announcement packet (FEATURE-002 deprecates the orphan 0x0A).
    [[nodiscard]] const std::unordered_map<std::string, std::string>& getExtra() const { return mExtra; }
    [[nodiscard]] std::string getExtra(const std::string& key) const {
        auto it = mExtra.find(key);
        return it != mExtra.end() ? it->second : "";
    }
    void addExtra(const std::string& key, const std::string& value) {
        mExtra.emplace(key, value);
    }
    void setExtra(const std::unordered_map<std::string, std::string>& extra) { mExtra = extra; }

    void setAction(AdminAction action) { mAction = action; }
    void setPlayerId(const UUID& id) { mPlayerId = id; }
    void setPasswordHash(const std::string& hash) { mPasswordHash = hash; }
    void setTarget(const std::string& target) { mTarget = target; }

private:
    AdminAction mAction = AdminAction::AUTH;
    UUID mPlayerId;
    std::string mPasswordHash;
    std::string mTarget;
    std::unordered_map<std::string, std::string> mExtra;
};

/**
 * Admin action response packet (Server -> Client).
 * Packet ID: 0x0C
 * Wire: byte action | bool success | string errorCode | string message
 */
class AdminActionResponsePacket : public Packet {
public:
    AdminActionResponsePacket() = default;

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::ADMIN_ACTION_RESPONSE; }

    void write(PacketBuffer& buf) const override {
        buf.writeByte(static_cast<uint8_t>(mAction));
        buf.writeBoolean(mSuccess);
        buf.writeString(mErrorCode);
        buf.writeString(mMessage);
    }

    void read(PacketBuffer& buf) override {
        mAction = static_cast<AdminAction>(buf.readByte());
        mSuccess = buf.readBoolean();
        mErrorCode = buf.readString(ProtocolLimits::MAX_ERROR_CODE);
        mMessage = buf.readString(ProtocolLimits::MAX_ERROR_MESSAGE);
    }

    [[nodiscard]] AdminAction getAction() const { return mAction; }
    [[nodiscard]] bool isSuccess() const { return mSuccess; }
    [[nodiscard]] const std::string& getErrorCode() const { return mErrorCode; }
    [[nodiscard]] const std::string& getMessage() const { return mMessage; }

private:
    AdminAction mAction = AdminAction::AUTH;
    bool mSuccess = false;
    std::string mErrorCode;
    std::string mMessage;
};

/**
 * Item display packet (Bidirectional) - [item]/[i] tag display.
 * Packet ID: 0x10
 * Wire: uuid senderId | string senderName | string channelId |
 *       string itemJson | long timestamp
 */
class ItemDisplayPacket : public Packet {
public:
    ItemDisplayPacket() = default;
    ItemDisplayPacket(const UUID& senderId, const std::string& senderName,
                      const std::string& channelId, const std::string& itemJson,
                      int64_t timestamp)
        : mSenderId(senderId)
        , mSenderName(senderName)
        , mChannelId(channelId)
        , mItemJson(itemJson)
        , mTimestamp(timestamp) {}

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::ITEM_DISPLAY; }

    void write(PacketBuffer& buf) const override {
        buf.writeUUID(mSenderId);
        buf.writeString(mSenderName);
        buf.writeString(mChannelId);
        buf.writeString(mItemJson);
        buf.writeLong(mTimestamp);
    }

    void read(PacketBuffer& buf) override {
        mSenderId = buf.readUUID();
        mSenderName = buf.readString(ProtocolLimits::MAX_SENDER_NAME);
        mChannelId = buf.readString(ProtocolLimits::MAX_CHANNEL_ID);
        mItemJson = buf.readString(ProtocolLimits::MAX_ITEM_JSON);
        mTimestamp = buf.readLong();
    }

    [[nodiscard]] const UUID& getSenderId() const { return mSenderId; }
    [[nodiscard]] const std::string& getSenderName() const { return mSenderName; }
    [[nodiscard]] const std::string& getChannelId() const { return mChannelId; }
    [[nodiscard]] const std::string& getItemJson() const { return mItemJson; }
    [[nodiscard]] int64_t getTimestamp() const { return mTimestamp; }

private:
    UUID mSenderId;
    std::string mSenderName;
    std::string mChannelId;
    std::string mItemJson;
    int64_t mTimestamp = 0;
};

/**
 * Mention notification packet (Server -> Client) - @mention highlight.
 * Packet ID: 0x12
 * Wire: uuid mentionerId | string mentionerName | uuid mentionedId |
 *       string channelId | string messagePreview | long timestamp
 */
class MentionPacket : public Packet {
public:
    MentionPacket() = default;
    MentionPacket(const UUID& mentionerId, const std::string& mentionerName,
                  const UUID& mentionedId, const std::string& channelId,
                  const std::string& messagePreview, int64_t timestamp)
        : mMentionerId(mentionerId)
        , mMentionerName(mentionerName)
        , mMentionedId(mentionedId)
        , mChannelId(channelId)
        , mMessagePreview(messagePreview)
        , mTimestamp(timestamp) {}

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::MENTION; }

    void write(PacketBuffer& buf) const override {
        buf.writeUUID(mMentionerId);
        buf.writeString(mMentionerName);
        buf.writeUUID(mMentionedId);
        buf.writeString(mChannelId);
        buf.writeString(mMessagePreview);
        buf.writeLong(mTimestamp);
    }

    void read(PacketBuffer& buf) override {
        mMentionerId = buf.readUUID();
        mMentionerName = buf.readString(ProtocolLimits::MAX_SENDER_NAME);
        mMentionedId = buf.readUUID();
        mChannelId = buf.readString(ProtocolLimits::MAX_CHANNEL_ID);
        mMessagePreview = buf.readString(ProtocolLimits::MAX_MESSAGE_PREVIEW);
        mTimestamp = buf.readLong();
    }

    [[nodiscard]] const UUID& getMentionerId() const { return mMentionerId; }
    [[nodiscard]] const std::string& getMentionerName() const { return mMentionerName; }
    [[nodiscard]] const UUID& getMentionedId() const { return mMentionedId; }
    [[nodiscard]] const std::string& getChannelId() const { return mChannelId; }
    [[nodiscard]] const std::string& getMessagePreview() const { return mMessagePreview; }
    [[nodiscard]] int64_t getTimestamp() const { return mTimestamp; }

private:
    UUID mMentionerId;
    std::string mMentionerName;
    UUID mMentionedId;
    std::string mChannelId;
    std::string mMessagePreview;
    int64_t mTimestamp = 0;
};

/**
 * Private message packet (Bidirectional) - cross-server /msg + /reply.
 * Packet ID: 0x14
 * Wire: uuid senderId | string senderName | string senderClientId |
 *       string targetName | uuid targetId | string content | long timestamp
 *
 * Client -> Server: sender fields + targetName are filled; targetId may be the
 * nil UUID (backend resolves the target by name). Server -> Client: the backend
 * fills the real targetId and the authoritative timestamp.
 */
class PrivateMessagePacket : public Packet {
public:
    PrivateMessagePacket() = default;
    PrivateMessagePacket(const UUID& senderId, const std::string& senderName,
                         const std::string& senderClientId, const std::string& targetName,
                         const UUID& targetId, const std::string& content,
                         int64_t timestamp)
        : mSenderId(senderId)
        , mSenderName(senderName)
        , mSenderClientId(senderClientId)
        , mTargetName(targetName)
        , mTargetId(targetId)
        , mContent(content)
        , mTimestamp(timestamp) {}

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::PRIVATE_MESSAGE; }

    void write(PacketBuffer& buf) const override {
        buf.writeUUID(mSenderId);
        buf.writeString(mSenderName);
        buf.writeString(mSenderClientId);
        buf.writeString(mTargetName);
        buf.writeUUID(mTargetId);
        buf.writeString(mContent);
        buf.writeLong(mTimestamp);
    }

    void read(PacketBuffer& buf) override {
        mSenderId = buf.readUUID();
        mSenderName = buf.readString(ProtocolLimits::MAX_SENDER_NAME);
        mSenderClientId = buf.readString(ProtocolLimits::MAX_CLIENT_ID);
        mTargetName = buf.readString(ProtocolLimits::MAX_TARGET_NAME);
        mTargetId = buf.readUUID();
        mContent = buf.readString(ProtocolLimits::MAX_MESSAGE_CONTENT);
        mTimestamp = buf.readLong();
    }

    [[nodiscard]] const UUID& getSenderId() const { return mSenderId; }
    [[nodiscard]] const std::string& getSenderName() const { return mSenderName; }
    [[nodiscard]] const std::string& getSenderClientId() const { return mSenderClientId; }
    [[nodiscard]] const std::string& getTargetName() const { return mTargetName; }
    [[nodiscard]] const UUID& getTargetId() const { return mTargetId; }
    [[nodiscard]] const std::string& getContent() const { return mContent; }
    [[nodiscard]] int64_t getTimestamp() const { return mTimestamp; }

private:
    UUID mSenderId;
    std::string mSenderName;
    std::string mSenderClientId;
    std::string mTargetName;
    UUID mTargetId;
    std::string mContent;
    int64_t mTimestamp = 0;
};

} // namespace novachat::protocol
