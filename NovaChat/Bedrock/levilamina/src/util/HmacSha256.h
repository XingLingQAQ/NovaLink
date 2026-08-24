#pragma once

#include <string>
#include <cstdint>
#include <array>

namespace novachat::util {

/**
 * Minimal standalone HMAC-SHA256 implementation.
 *
 * Used by the AUTH-002 challenge-response handshake: the client proves it
 * knows the password by sending
 *   HMAC-SHA256(key = sha256hex(password), message = serverNonce || clientNonce)
 * to the backend, matching the Java client behaviour
 * (Mac("HmacSHA256") keyed by the UTF-8 bytes of the lowercase hex SHA-256 of
 * the password, over the UTF-8 bytes of the concatenated hex nonces, output
 * lowercase hex).
 *
 * Self-contained (no OpenSSL / CryptoAPI dependency) so it builds anywhere
 * the LeviLamina plugin builds. Reuses Sha256::compute for both inner and
 * outer digests.
 *
 * Implementation note: the key passed to hex() is treated as raw bytes. For
 * the handshake the caller passes Sha256::hex(password) (a 64-char lowercase
 * hex string == exactly the SHA-256 block size B=64), but the implementation
 * still applies the RFC 2104 key-strength normalization (hash-then-pad when
 * key length > B, zero-pad when < B) so it is correct for any key length.
 */
class HmacSha256 {
public:
    /**
     * Computes HMAC-SHA256 of @p message keyed by @p key and returns
     * lowercase hex.
     */
    static std::string hex(const std::string& key, const std::string& message) {
        auto digest = compute(reinterpret_cast<const uint8_t*>(key.data()), key.size(),
                              reinterpret_cast<const uint8_t*>(message.data()), message.size());
        return toHex(digest);
    }

    /** Computes HMAC-SHA256 of the given bytes (RFC 2104). */
    static std::array<uint8_t, 32> compute(const uint8_t* key, size_t keyLen,
                                           const uint8_t* message, size_t messageLen);

private:
    static std::string toHex(const std::array<uint8_t, 32>& digest);
};

} // namespace novachat::util
