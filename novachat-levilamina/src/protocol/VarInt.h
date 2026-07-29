#pragma once

#include <cstdint>
#include <vector>
#include <stdexcept>

namespace novachat::protocol {

/**
 * VarInt encoder/decoder for NovaProtocol.
 * VarInt is a variable-length integer encoding that uses 1-5 bytes.
 * Each byte uses 7 bits for data and 1 bit (MSB) as continuation flag.
 */
class VarInt {
public:
    static constexpr int SEGMENT_BITS = 0x7F;
    static constexpr int CONTINUE_BIT = 0x80;
    static constexpr int MAX_VARINT_SIZE = 5;

    /**
     * Encodes an integer value to a byte vector as VarInt.
     * @param value the integer value to encode
     * @return the encoded byte vector
     */
    static std::vector<uint8_t> encode(int32_t value) {
        std::vector<uint8_t> result;
        result.reserve(MAX_VARINT_SIZE);
        
        uint32_t uvalue = static_cast<uint32_t>(value);
        while (true) {
            if ((uvalue & ~SEGMENT_BITS) == 0) {
                result.push_back(static_cast<uint8_t>(uvalue));
                return result;
            }
            result.push_back(static_cast<uint8_t>((uvalue & SEGMENT_BITS) | CONTINUE_BIT));
            uvalue >>= 7;
        }
    }

    /**
     * Decodes a VarInt from a byte buffer.
     * @param data pointer to the data buffer
     * @param size size of the buffer
     * @param bytesRead output parameter for number of bytes consumed
     * @return the decoded integer value
     * @throws std::runtime_error if the VarInt is too large
     */
    static int32_t decode(const uint8_t* data, size_t size, size_t& bytesRead) {
        int32_t value = 0;
        int position = 0;
        bytesRead = 0;

        while (bytesRead < size) {
            uint8_t currentByte = data[bytesRead++];
            value |= (currentByte & SEGMENT_BITS) << position;

            if ((currentByte & CONTINUE_BIT) == 0) {
                return value;
            }

            position += 7;
            if (position >= 32) {
                throw std::runtime_error("VarInt is too big");
            }
        }

        throw std::runtime_error("Incomplete VarInt");
    }

    /**
     * Calculates the number of bytes needed to encode the given value as a VarInt.
     * @param value the integer value
     * @return the number of bytes needed (1-5)
     */
    static int getVarIntSize(int32_t value) {
        uint32_t uvalue = static_cast<uint32_t>(value);
        if ((uvalue & (0xFFFFFFFF << 7)) == 0) return 1;
        if ((uvalue & (0xFFFFFFFF << 14)) == 0) return 2;
        if ((uvalue & (0xFFFFFFFF << 21)) == 0) return 3;
        if ((uvalue & (0xFFFFFFFF << 28)) == 0) return 4;
        return 5;
    }

    /**
     * Tries to read a VarInt from buffer without consuming it.
     * @param data pointer to the data buffer
     * @param size size of the buffer
     * @param value output parameter for the decoded value
     * @param bytesRead output parameter for number of bytes needed
     * @return true if a complete VarInt was found, false otherwise
     */
    static bool tryPeek(const uint8_t* data, size_t size, int32_t& value, size_t& bytesRead) {
        value = 0;
        int position = 0;
        bytesRead = 0;

        while (bytesRead < size && bytesRead < MAX_VARINT_SIZE) {
            uint8_t currentByte = data[bytesRead++];
            value |= (currentByte & SEGMENT_BITS) << position;

            if ((currentByte & CONTINUE_BIT) == 0) {
                return true;
            }

            position += 7;
            if (position >= 32) {
                return false; // Invalid VarInt
            }
        }

        return false; // Incomplete
    }
};

} // namespace novachat::protocol
