#pragma once

#include "PacketBuffer.h"
#include "PacketIds.h"
#include <memory>
#include <unordered_map>
#include <string>

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
        mClientId = buf.readString();
        mPasswordHash = buf.readString();
        mPlatform = static_cast<PlatformType>(buf.readByte());
        // Optional trailing field (protocol v2+); old v1 peers omit it.
        if (buf.readableBytes() > 0) {
            mServerVersion = buf.readString();
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
        mErrorCode = buf.readString();
        mMessage = buf.readString();
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
        // Placeholders map (optional). Keep empty for this client.
        buf.writeVarInt(0);
    }

    void read(PacketBuffer& buf) override {
        mSenderId = buf.readUUID();
        mSenderName = buf.readString();
        mClientId = buf.readString();
        mChannelId = buf.readString();
        mContent = buf.readString();
        // Consume optional placeholders map if present (ignore contents).
        if (buf.readableBytes() > 0) {
            int32_t size = buf.readVarInt();
            for (int32_t i = 0; i < size; ++i) {
                (void) buf.readString();
                (void) buf.readString();
            }
        }
    }

    [[nodiscard]] const UUID& getSenderId() const { return mSenderId; }
    [[nodiscard]] const std::string& getSenderName() const { return mSenderName; }
    [[nodiscard]] const std::string& getClientId() const { return mClientId; }
    [[nodiscard]] const std::string& getChannelId() const { return mChannelId; }
    [[nodiscard]] const std::string& getContent() const { return mContent; }

private:
    UUID mSenderId;
    std::string mSenderName;
    std::string mClientId;
    std::string mChannelId;
    std::string mContent;
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
        mChannelId = buf.readString();
        mPassword = buf.readString();
        // Extra map (optional for legacy implementations).
        if (buf.readableBytes() <= 0) {
            mExtra.clear();
            return;
        }
        int32_t size = buf.readVarInt();
        mExtra.clear();
        for (int32_t i = 0; i < size; ++i) {
            std::string key = buf.readString();
            std::string value = buf.readString();
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
        mChannelId = buf.readString();
        mErrorCode = buf.readString();
        mMessage = buf.readString();
        if (buf.readableBytes() <= 0) {
            mExtra.clear();
            return;
        }
        int32_t size = buf.readVarInt();
        mExtra.clear();
        for (int32_t i = 0; i < size; ++i) {
            std::string key = buf.readString();
            std::string value = buf.readString();
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
        buf.writeString(mConfigJson.empty() ? std::string("{}") : mConfigJson);
        buf.writeLong(mTimestamp);
    }

    void read(PacketBuffer& buf) override {
        mConfigJson = buf.readString();
        mTimestamp = buf.readLong();
    }

    [[nodiscard]] const std::string& getConfigJson() const { return mConfigJson; }
    [[nodiscard]] int64_t getTimestamp() const { return mTimestamp; }

private:
    std::string mConfigJson;
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
        mChannelId = buf.readString();
        mTitle = buf.readString();
        mSubtitle = buf.readString();
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
        mPasswordHash = buf.readString();
        mTarget = buf.readString();
        int32_t size = buf.readVarInt();
        mExtra.clear();
        for (int32_t i = 0; i < size; ++i) {
            std::string key = buf.readString();
            std::string value = buf.readString();
            mExtra.emplace(std::move(key), std::move(value));
        }
    }

    [[nodiscard]] AdminAction getAction() const { return mAction; }
    [[nodiscard]] const UUID& getPlayerId() const { return mPlayerId; }
    [[nodiscard]] const std::string& getPasswordHash() const { return mPasswordHash; }
    [[nodiscard]] const std::string& getTarget() const { return mTarget; }

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
        mErrorCode = buf.readString();
        mMessage = buf.readString();
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
        mSenderName = buf.readString();
        mChannelId = buf.readString();
        mItemJson = buf.readString();
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
        mMentionerName = buf.readString();
        mMentionedId = buf.readUUID();
        mChannelId = buf.readString();
        mMessagePreview = buf.readString();
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

} // namespace novachat::protocol
