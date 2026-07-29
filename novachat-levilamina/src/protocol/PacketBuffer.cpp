#include "PacketBuffer.h"
#include <random>
#include <sstream>
#include <iomanip>

namespace novachat::protocol {

UUID UUID::random() {
    static std::random_device rd;
    static std::mt19937_64 gen(rd());
    static std::uniform_int_distribution<uint64_t> dis;

    UUID uuid;
    uuid.mostSigBits = dis(gen);
    uuid.leastSigBits = dis(gen);

    // Set version to 4 (random UUID)
    uuid.mostSigBits = (uuid.mostSigBits & 0xFFFFFFFFFFFF0FFFULL) | 0x0000000000004000ULL;
    // Set variant to 2 (RFC 4122)
    uuid.leastSigBits = (uuid.leastSigBits & 0x3FFFFFFFFFFFFFFFULL) | 0x8000000000000000ULL;

    return uuid;
}

std::string UUID::toString() const {
    std::ostringstream oss;
    oss << std::hex << std::setfill('0');
    
    // Format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
    oss << std::setw(8) << ((mostSigBits >> 32) & 0xFFFFFFFF) << '-';
    oss << std::setw(4) << ((mostSigBits >> 16) & 0xFFFF) << '-';
    oss << std::setw(4) << (mostSigBits & 0xFFFF) << '-';
    oss << std::setw(4) << ((leastSigBits >> 48) & 0xFFFF) << '-';
    oss << std::setw(12) << (leastSigBits & 0xFFFFFFFFFFFFULL);
    
    return oss.str();
}

} // namespace novachat::protocol
