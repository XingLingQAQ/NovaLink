#pragma once

#include "../protocol/Packet.h"

#include <memory>
#include <string>

namespace novachat {

inline std::unique_ptr<protocol::ChannelActionPacket> makePlayerChannelAction(
    protocol::ChannelAction action,
    const std::string& channelId,
    const std::string& playerUuid,
    const std::string& playerName,
    const std::string& password = ""
) {
    auto packet = std::make_unique<protocol::ChannelActionPacket>(action, channelId, password);
    packet->addExtra("playerId", playerUuid);
    packet->addExtra("playerName", playerName);
    packet->addExtra("platform", "levilamina");
    return packet;
}

} // namespace novachat
