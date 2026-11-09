#include "../src/protocol/Packet.h"
#include <fstream>
#include <iostream>

int main(int argc, char** argv) {
    if (argc != 2) return 1;
    std::ifstream input(argv[1]);
    std::string hex;
    input >> hex;
    if (hex.empty() || hex.size() % 2 != 0) return 1;
    std::vector<uint8_t> bytes;
    for (size_t i = 0; i < hex.size(); i += 2) {
        bytes.push_back(static_cast<uint8_t>(std::stoul(hex.substr(i, 2), nullptr, 16)));
    }
    using namespace novachat::protocol;
    PacketBuffer buffer(bytes);
    if (buffer.readByte() != PacketIds::SPY_MESSAGE) return 1;
    const auto requestId = buffer.readUUID();
    SpyMessagePacket packet;
    packet.setRequestId(requestId);
    packet.read(buffer);
    if (buffer.readableBytes() != 0
            || packet.getTargetId().toString() != "22222222-2222-2222-2222-222222222222"
            || packet.getMessage().getSenderName() != "Sender"
            || packet.getMessage().getClientId() != "source"
            || packet.getMessage().getChannelId() != "staff"
            || packet.getMessage().getContent() != "observed"
            || packet.getMessage().getPlaceholders().size() != 1
            || packet.getMessage().getPlaceholders()[0] != std::pair<std::string, std::string>{"rank", "member"}) return 1;
    PacketBuffer output;
    output.writeByte(packet.getPacketId());
    output.writeUUID(packet.getRequestId());
    packet.write(output);
    if (output.getData() != bytes) return 1;
    std::cout << "spy fixture passed\n";
    return 0;
}
