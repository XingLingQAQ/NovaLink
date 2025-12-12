#pragma once

#include "PacketBuffer.h"
#include "PacketIds.h"
#include <memory>

namespace novachat::protocol {

/**
 * Base class for all NovaProtocol packets.
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

/**
 * Handshake packet sent by client to authenticate with the backend.
 * Packet ID: 0x01, Direction: Client → Server
 */
class HandshakePacket : public Packet {
public:
    HandshakePacket() = default;
    HandshakePacket(int32_t protocolVersion, const std::string& clientId, 
                    const std::string& passwordHash, PlatformType platform)
        : mProtocolVersion(protocolVersion)
        , mClientId(clientId)
        , mPasswordHash(passwordHash)
        , mPlatform(platform) {}

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::HANDSHAKE; }

    void write(PacketBuffer& buf) const override {
        buf.writeVarInt(mProtocolVersion);
        buf.writeString(mClientId);
        buf.writeString(mPasswordHash);
        buf.writeByte(static_cast<uint8_t>(mPlatform));
    }

    void read(PacketBuffer& buf) override {
        mProtocolVersion = buf.readVarInt();
        mClientId = buf.readString();
        mPasswordHash = buf.readString();
        mPlatform = static_cast<PlatformType>(buf.readByte());
    }

    [[nodiscard]] int32_t getProtocolVersion() const { return mProtocolVersion; }
    [[nodiscard]] const std::string& getClientId() const { return mClientId; }
    [[nodiscard]] const std::string& getPasswordHash() const { return mPasswordHash; }
    [[nodiscard]] PlatformType getPlatform() const { return mPlatform; }

private:
    int32_t mProtocolVersion = 1;
    std::string mClientId;
    std::string mPasswordHash;
    PlatformType mPlatform = PlatformType::LEVILAMINA;
};

/**
 * Handshake response from server.
 * Packet ID: 0x02, Direction: Server → Client
 */
class HandshakeResponsePacket : public Packet {
public:
    HandshakeResponsePacket() = default;

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::HANDSHAKE_RESPONSE; }

    void write(PacketBuffer& buf) const override {
        buf.writeBoolean(mSuccess);
        buf.writeString(mMessage);
        buf.writeString(mErrorCode);
    }

    void read(PacketBuffer& buf) override {
        mSuccess = buf.readBoolean();
        mMessage = buf.readString();
        mErrorCode = buf.readString();
    }

    [[nodiscard]] bool isSuccess() const { return mSuccess; }
    [[nodiscard]] const std::string& getMessage() const { return mMessage; }
    [[nodiscard]] const std::string& getErrorCode() const { return mErrorCode; }

private:
    bool mSuccess = false;
    std::string mMessage;
    std::string mErrorCode;
};

/**
 * Chat message packet.
 * Packet ID: 0x03, Direction: Bidirectional
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
    }

    void read(PacketBuffer& buf) override {
        mSenderId = buf.readUUID();
        mSenderName = buf.readString();
        mClientId = buf.readString();
        mChannelId = buf.readString();
        mContent = buf.readString();
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
 * Packet ID: 0x04, Direction: Client → Server
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
    }

    void read(PacketBuffer& buf) override {
        mAction = static_cast<ChannelAction>(buf.readByte());
        mChannelId = buf.readString();
        mPassword = buf.readString();
    }

    [[nodiscard]] ChannelAction getAction() const { return mAction; }
    [[nodiscard]] const std::string& getChannelId() const { return mChannelId; }
    [[nodiscard]] const std::string& getPassword() const { return mPassword; }

private:
    ChannelAction mAction = ChannelAction::JOIN;
    std::string mChannelId;
    std::string mPassword;
};

/**
 * Channel action response packet.
 * Packet ID: 0x05, Direction: Server → Client
 */
class ChannelActionResponsePacket : public Packet {
public:
    ChannelActionResponsePacket() = default;

    [[nodiscard]] uint8_t getPacketId() const override { return PacketIds::CHANNEL_ACTION_RESPONSE; }

    void write(PacketBuffer& buf) const override {
        buf.writeBoolean(mSuccess);
        buf.writeString(mMessage);
        buf.writeString(mErrorCode);
        buf.writeString(mChannelId);
    }

    void read(PacketBuffer& buf) override {
        mSuccess = buf.readBoolean();
        mMessage = buf.readString();
        mErrorCode = buf.readString();
        mChannelId = buf.readString();
    }

    [[nodiscard]] bool isSuccess() const { return mSuccess; }
    [[nodiscard]] const std::string& getMessage() const { return mMessage; }
    [[nodiscard]] const std::string& getErrorCode() const { return mErrorCode; }
    [[nodiscard]] const std::string& getChannelId() const { return mChannelId; }

private:
    bool mSuccess = false;
    std::string mMessage;
    std::string mErrorCode;
    std::string mChannelId;
};

} // namespace novachat::protocol
