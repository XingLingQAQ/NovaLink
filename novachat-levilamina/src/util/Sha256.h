#pragma once

#include <string>
#include <cstdint>
#include <array>

namespace novachat::util {

/**
 * Minimal standalone SHA-256 implementation.
 *
 * Used to hash the NovaLink backend password before sending it in the
 * HandshakePacket, matching the Java client behaviour
 * (MessageDigest.getInstance("SHA-256") -> lowercase hex).
 *
 * Self-contained (no OpenSSL / CryptoAPI dependency) so it builds anywhere
 * the LeviLamina plugin builds.
 */
class Sha256 {
public:
    /** Computes SHA-256 of the given UTF-8 string and returns lowercase hex. */
    static std::string hex(const std::string& input) {
        auto digest = compute(reinterpret_cast<const uint8_t*>(input.data()), input.size());
        return toHex(digest);
    }

    /** Computes SHA-256 of the given bytes. */
    static std::array<uint8_t, 32> compute(const uint8_t* data, size_t length);

private:
    static std::string toHex(const std::array<uint8_t, 32>& digest);
};

} // namespace novachat::util
