#pragma once

#include "VarInt.h"
#include "ProtocolLimits.h"
#include <cstdint>
#include <vector>
#include <string>
#include <array>
#include <stdexcept>
#include <cstring>

namespace novachat::protocol {

/**
 * UUID representation (128-bit)
 */
struct UUID {
    uint64_t mostSigBits = 0;
    uint64_t leastSigBits = 0;

    bool operator==(const UUID& other) const {
        return mostSigBits == other.mostSigBits && leastSigBits == other.leastSigBits;
    }

    static UUID random();
    std::string toString() const;
};

/**
 * Packet buffer for reading and writing data in big-endian format.
 * Provides convenient methods for common data types used in NovaProtocol.
 */
class PacketBuffer {
public:
    PacketBuffer() = default;
    explicit PacketBuffer(std::vector<uint8_t> data) : mData(std::move(data)), mReadPos(0) {}

    // ==================== Write Operations ====================

    void writeByte(uint8_t value) {
        mData.push_back(value);
    }

    void writeBytes(const uint8_t* data, size_t length) {
        mData.insert(mData.end(), data, data + length);
    }

    void writeVarInt(int32_t value) {
        auto encoded = VarInt::encode(value);
        mData.insert(mData.end(), encoded.begin(), encoded.end());
    }

    void writeString(const std::string& value) {
        writeVarInt(static_cast<int32_t>(value.size()));
        mData.insert(mData.end(), value.begin(), value.end());
    }

    void writeUUID(const UUID& uuid) {
        writeLong(uuid.mostSigBits);
        writeLong(uuid.leastSigBits);
    }

    void writeBoolean(bool value) {
        writeByte(value ? 1 : 0);
    }

    // Big-endian writes
    void writeShort(int16_t value) {
        uint16_t uval = static_cast<uint16_t>(value);
        mData.push_back(static_cast<uint8_t>((uval >> 8) & 0xFF));
        mData.push_back(static_cast<uint8_t>(uval & 0xFF));
    }

    void writeInt(int32_t value) {
        uint32_t uval = static_cast<uint32_t>(value);
        mData.push_back(static_cast<uint8_t>((uval >> 24) & 0xFF));
        mData.push_back(static_cast<uint8_t>((uval >> 16) & 0xFF));
        mData.push_back(static_cast<uint8_t>((uval >> 8) & 0xFF));
        mData.push_back(static_cast<uint8_t>(uval & 0xFF));
    }

    void writeLong(int64_t value) {
        uint64_t uval = static_cast<uint64_t>(value);
        for (int i = 7; i >= 0; --i) {
            mData.push_back(static_cast<uint8_t>((uval >> (i * 8)) & 0xFF));
        }
    }

    // ==================== Read Operations ====================

    uint8_t readByte() {
        checkReadable(1);
        return mData[mReadPos++];
    }

    void readBytes(uint8_t* dest, size_t length) {
        checkReadable(length);
        std::memcpy(dest, mData.data() + mReadPos, length);
        mReadPos += length;
    }

    int32_t readVarInt() {
        size_t bytesRead;
        int32_t value = VarInt::decode(mData.data() + mReadPos, mData.size() - mReadPos, bytesRead);
        mReadPos += bytesRead;
        return value;
    }

    std::string readString() {
        int32_t length = readVarInt();
        if (length < 0) {
            throw std::runtime_error("Negative string length");
        }
        // Hard cap at the frame ceiling so an absurd declared length is
        // rejected before the allocation, mirroring the JVM
        // Varint21FrameDecoder ceiling (PROTO-002).
        if (static_cast<size_t>(length) > ProtocolLimits::MAX_FRAME_LENGTH) {
            throw std::runtime_error("Invalid string length");
        }
        checkReadable(static_cast<size_t>(length));
        std::string result(reinterpret_cast<const char*>(mData.data() + mReadPos), length);
        mReadPos += length;
        return result;
    }

    /**
     * Reads a length-prefixed UTF-8 string, rejecting fields whose declared
     * wire length exceeds `maxLength` before the bytes are allocated.
     *
     * PROTO-003: this is the bounded counterpart to the JVM
     * `PacketBuffer.readString(buf, max)`. C++ overloads by arity, so the
     * legacy zero-arg `readString()` above is preserved for backward
     * compatibility (e.g. golden-byte decoders reading arbitrary fields)
     * while packet `read()` methods call this overload with the matching
     * `ProtocolLimits` constant. An oversized field is rejected with a
     * `std::runtime_error` whose message contains "exceeds maximum" so the
     * non-JVM forks stay byte-for-byte consistent with the Java error
     * contract.
     *
     * @param maxLength Maximum UTF-8 byte length for this field.
     * @return The decoded string.
     * @throws std::runtime_error If the declared length is negative, exceeds
     *         `ProtocolLimits::MAX_FRAME_LENGTH`, exceeds `maxLength`, or
     *         exceeds the remaining bytes.
     */
    std::string readString(size_t maxLength) {
        int32_t length = readVarInt();
        if (length < 0) {
            throw std::runtime_error("Negative string length");
        }
        if (static_cast<size_t>(length) > ProtocolLimits::MAX_FRAME_LENGTH) {
            throw std::runtime_error("Invalid string length");
        }
        if (static_cast<size_t>(length) > maxLength) {
            throw std::runtime_error(
                "String length " + std::to_string(length) +
                " exceeds maximum " + std::to_string(maxLength)
            );
        }
        checkReadable(static_cast<size_t>(length));
        std::string result(reinterpret_cast<const char*>(mData.data() + mReadPos), length);
        mReadPos += length;
        return result;
    }

    UUID readUUID() {
        UUID uuid;
        uuid.mostSigBits = readLong();
        uuid.leastSigBits = readLong();
        return uuid;
    }

    bool readBoolean() {
        return readByte() != 0;
    }

    // Big-endian reads
    int16_t readShort() {
        checkReadable(2);
        uint16_t value = (static_cast<uint16_t>(mData[mReadPos]) << 8) |
                         static_cast<uint16_t>(mData[mReadPos + 1]);
        mReadPos += 2;
        return static_cast<int16_t>(value);
    }

    int32_t readInt() {
        checkReadable(4);
        uint32_t value = (static_cast<uint32_t>(mData[mReadPos]) << 24) |
                         (static_cast<uint32_t>(mData[mReadPos + 1]) << 16) |
                         (static_cast<uint32_t>(mData[mReadPos + 2]) << 8) |
                         static_cast<uint32_t>(mData[mReadPos + 3]);
        mReadPos += 4;
        return static_cast<int32_t>(value);
    }

    int64_t readLong() {
        checkReadable(8);
        uint64_t value = 0;
        for (int i = 0; i < 8; ++i) {
            value = (value << 8) | mData[mReadPos + i];
        }
        mReadPos += 8;
        return static_cast<int64_t>(value);
    }

    // ==================== Buffer Operations ====================

    [[nodiscard]] const std::vector<uint8_t>& getData() const { return mData; }
    [[nodiscard]] size_t size() const { return mData.size(); }
    [[nodiscard]] size_t readableBytes() const { return mData.size() - mReadPos; }
    [[nodiscard]] size_t readerIndex() const { return mReadPos; }
    
    void clear() {
        mData.clear();
        mReadPos = 0;
    }

    void resetReaderIndex() {
        mReadPos = 0;
    }

private:
    void checkReadable(size_t bytes) const {
        if (mReadPos + bytes > mData.size()) {
            throw std::runtime_error("Buffer underflow");
        }
    }

    std::vector<uint8_t> mData;
    size_t mReadPos = 0;
};

} // namespace novachat::protocol
