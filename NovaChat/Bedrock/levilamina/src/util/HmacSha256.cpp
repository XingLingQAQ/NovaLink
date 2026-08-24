#include "HmacSha256.h"
#include "Sha256.h"

#include <sstream>
#include <iomanip>
#include <vector>
#include <cstring>

namespace novachat::util {

namespace {

// SHA-256 block size in bytes (RFC 2104 B).
constexpr size_t BLOCK_SIZE = 64;
constexpr uint8_t IPAD = 0x36;
constexpr uint8_t OPAD = 0x5c;

} // namespace

std::array<uint8_t, 32> HmacSha256::compute(const uint8_t* key, size_t keyLen,
                                             const uint8_t* message, size_t messageLen) {
    // RFC 2104 step 1: strength-normalize the key.
    // If key length > B, hash it down to 32 bytes; then zero-pad to B bytes.
    std::array<uint8_t, BLOCK_SIZE> keyBlock{};
    if (keyLen > BLOCK_SIZE) {
        auto digest = Sha256::compute(key, keyLen);
        std::memcpy(keyBlock.data(), digest.data(), digest.size());
        // remaining bytes stay zero
    } else if (keyLen > 0) {
        std::memcpy(keyBlock.data(), key, keyLen);
        // remaining bytes stay zero
    }

    // Step 2: ipad / opad.
    std::array<uint8_t, BLOCK_SIZE> ipad{};
    std::array<uint8_t, BLOCK_SIZE> opad{};
    for (size_t i = 0; i < BLOCK_SIZE; ++i) {
        ipad[i] = keyBlock[i] ^ IPAD;
        opad[i] = keyBlock[i] ^ OPAD;
    }

    // Step 3: inner = SHA256(ipad || message).
    std::vector<uint8_t> innerInput;
    innerInput.reserve(BLOCK_SIZE + messageLen);
    innerInput.insert(innerInput.end(), ipad.begin(), ipad.end());
    if (messageLen > 0) {
        innerInput.insert(innerInput.end(), message, message + messageLen);
    }
    auto innerDigest = Sha256::compute(innerInput.data(), innerInput.size());

    // Step 4: result = SHA256(opad || inner).
    std::vector<uint8_t> outerInput;
    outerInput.reserve(BLOCK_SIZE + innerDigest.size());
    outerInput.insert(outerInput.end(), opad.begin(), opad.end());
    outerInput.insert(outerInput.end(), innerDigest.begin(), innerDigest.end());
    return Sha256::compute(outerInput.data(), outerInput.size());
}

std::string HmacSha256::toHex(const std::array<uint8_t, 32>& digest) {
    std::ostringstream oss;
    for (uint8_t b : digest) {
        oss << std::hex << std::setw(2) << std::setfill('0') << static_cast<int>(b);
    }
    return oss.str();
}

} // namespace novachat::util
