#include "CommandHandler.h"
#include "../NovaChatPlugin.h"
#include "../config/NovaChatConfig.h"
#include "../chat/ChatInterceptor.h"
#include "../network/NetworkClient.h"
#include "../protocol/Packet.h"

#include <ll/api/Logger.h>
#include <ll/api/command/Command.h>
#include <ll/api/command/CommandHandle.h>
#include <ll/api/command/CommandRegistrar.h>
#include <ll/api/service/Bedrock.h>
#include <mc/world/actor/player/Player.h>
#include <mc/server/commands/CommandOrigin.h>
#include <mc/server/commands/CommandOutput.h>
#include <mc/server/commands/CommandPermissionLevel.h>
#include <mc/network/packet/TextPacket.h>

namespace novachat {

using namespace novachat::protocol;
using namespace novachat::network;

// Command parameter structures
struct HelpParams {};

struct JoinParams {
    std::string channelId;
    std::string password;
};

struct LeaveParams {};

struct ToggleParams {};

struct DebugParams {
    bool enable;
};

struct ReloadParams {};

CommandHandler::CommandHandler(NovaChatPlugin& plugin)
    : mPlugin(plugin) {}

CommandHandler::~CommandHandler() {
    unregisterCommands();
}

void CommandHandler::registerCommands() {
    if (mCommandsRegistered) {
        return;
    }

    auto& logger = mPlugin.getSelf().getLogger();
    logger.info("Registering commands...");

    auto& registrar = ll::command::CommandRegistrar::getInstance();

    // Register /nc command
    auto& ncCommand = registrar.getOrCreateCommand(
        "nc",
        "NovaChat commands",
        CommandPermissionLevel::Any
    );

    // /nc help
    ncCommand.overload<HelpParams>()
        .text("help")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, HelpParams const&) {
            if (auto* player = origin.getEntity(); player && player->isPlayer()) {
                handleHelp(static_cast<Player*>(player)->getName(), {});
            }
            output.success();
        });

    // /nc join <channel> [password]
    ncCommand.overload<JoinParams>()
        .text("join")
        .required("channelId")
        .optional("password")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, JoinParams const& params) {
            if (auto* player = origin.getEntity(); player && player->isPlayer()) {
                auto* p = static_cast<Player*>(player);
                handleJoin(p->getName(), p->getUuid().asString(), 
                    {params.channelId, params.password});
            }
            output.success();
        });

    // /nc leave
    ncCommand.overload<LeaveParams>()
        .text("leave")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, LeaveParams const&) {
            if (auto* player = origin.getEntity(); player && player->isPlayer()) {
                auto* p = static_cast<Player*>(player);
                handleLeave(p->getName(), p->getUuid().asString(), {});
            }
            output.success();
        });

    // /nc toggle
    ncCommand.overload<ToggleParams>()
        .text("toggle")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, ToggleParams const&) {
            if (auto* player = origin.getEntity(); player && player->isPlayer()) {
                auto* p = static_cast<Player*>(player);
                handleToggle(p->getName(), p->getUuid().asString(), {});
            }
            output.success();
        });

    // /nc debug <on|off> (admin only)
    ncCommand.overload<DebugParams>()
        .text("debug")
        .required("enable")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, DebugParams const& params) {
            if (auto* player = origin.getEntity(); player && player->isPlayer()) {
                handleDebug(static_cast<Player*>(player)->getName(), 
                    {params.enable ? "on" : "off"});
            }
            output.success();
        });

    // /nc reload (admin only)
    ncCommand.overload<ReloadParams>()
        .text("reload")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, ReloadParams const&) {
            if (auto* player = origin.getEntity(); player && player->isPlayer()) {
                handleReload(static_cast<Player*>(player)->getName(), {});
            }
            output.success();
        });

    mCommandsRegistered = true;
    logger.info("Commands registered successfully.");
}

void CommandHandler::unregisterCommands() {
    if (!mCommandsRegistered) {
        return;
    }

    // Commands are automatically unregistered when plugin unloads
    mCommandsRegistered = false;
}

void CommandHandler::handleHelp(const std::string& playerName, const std::vector<std::string>& args) {
    std::string prefix = mPlugin.getConfig()->getPrefix();
    
    sendMessage(playerName, prefix + "§e=== NovaChat 帮助 ===");
    sendMessage(playerName, "§7/nc help §f- 显示此帮助");
    sendMessage(playerName, "§7/nc join <频道> [密码] §f- 加入频道");
    sendMessage(playerName, "§7/nc leave §f- 离开当前频道");
    sendMessage(playerName, "§7/nc toggle §f- 切换聊天模式");
}

void CommandHandler::handleJoin(const std::string& playerName, const std::string& playerUuid,
                                 const std::vector<std::string>& args) {
    if (args.empty()) {
        sendMessage(playerName, mPlugin.getConfig()->getPrefix() + "§c用法: /nc join <频道> [密码]");
        return;
    }

    std::string channelId = args[0];
    std::string password = args.size() > 1 ? args[1] : "";

    // Send join request to backend
    auto* networkClient = mPlugin.getNetworkClient();
    if (networkClient && networkClient->isConnected()) {
        auto packet = std::make_unique<ChannelActionPacket>(
            ChannelAction::JOIN, channelId, password
        );
        networkClient->sendPacket(std::move(packet));
        
        // Update local state
        if (auto* interceptor = mPlugin.getChatInterceptor()) {
            interceptor->setPlayerChannel(playerUuid, channelId);
        }
        
        sendMessage(playerName, mPlugin.getConfig()->getPrefix() + 
            "§a正在加入频道: " + channelId);
    } else {
        sendMessage(playerName, mPlugin.getConfig()->getPrefix() + 
            "§c无法连接到后端服务器");
    }
}

void CommandHandler::handleLeave(const std::string& playerName, const std::string& playerUuid,
                                  const std::vector<std::string>& args) {
    auto* interceptor = mPlugin.getChatInterceptor();
    if (!interceptor) {
        return;
    }

    auto& state = interceptor->getPlayerState(playerUuid);
    std::string currentChannel = state.currentChannel;
    std::string defaultChannel = mPlugin.getConfig()->getDefaultChannel();

    if (currentChannel == defaultChannel) {
        sendMessage(playerName, mPlugin.getConfig()->getPrefix() + 
            "§c你已经在默认频道中");
        return;
    }

    // Send leave request to backend
    auto* networkClient = mPlugin.getNetworkClient();
    if (networkClient && networkClient->isConnected()) {
        auto packet = std::make_unique<ChannelActionPacket>(
            ChannelAction::LEAVE, currentChannel
        );
        networkClient->sendPacket(std::move(packet));
    }

    // Update local state
    interceptor->setPlayerChannel(playerUuid, defaultChannel);
    
    sendMessage(playerName, mPlugin.getConfig()->getPrefix() + 
        "§a已离开频道: " + currentChannel);
}

void CommandHandler::handleToggle(const std::string& playerName, const std::string& playerUuid,
                                   const std::vector<std::string>& args) {
    auto* interceptor = mPlugin.getChatInterceptor();
    if (!interceptor) {
        return;
    }

    ChatMode newMode = interceptor->toggleChatMode(playerUuid);
    
    std::string modeStr = (newMode == ChatMode::REPLACE) ? "NovaChat模式" : "混合模式";
    sendMessage(playerName, mPlugin.getConfig()->getPrefix() + 
        "§a聊天模式已切换为: §e" + modeStr);
}

void CommandHandler::handleDebug(const std::string& playerName, const std::vector<std::string>& args) {
    // TODO: Add permission check
    
    if (args.empty()) {
        bool current = mPlugin.getConfig()->isDebug();
        sendMessage(playerName, mPlugin.getConfig()->getPrefix() + 
            "§7调试模式: " + (current ? "§a开启" : "§c关闭"));
        return;
    }

    bool enable = (args[0] == "on" || args[0] == "true" || args[0] == "1");
    mPlugin.getConfig()->setDebug(enable);
    
    sendMessage(playerName, mPlugin.getConfig()->getPrefix() + 
        "§a调试模式已" + (enable ? "开启" : "关闭"));
}

void CommandHandler::handleReload(const std::string& playerName, const std::vector<std::string>& args) {
    // TODO: Add permission check
    
    if (mPlugin.getConfig()->reload()) {
        sendMessage(playerName, mPlugin.getConfig()->getPrefix() + 
            "§a配置已重新加载");
    } else {
        sendMessage(playerName, mPlugin.getConfig()->getPrefix() + 
            "§c配置重载失败");
    }
}

void CommandHandler::sendMessage(const std::string& playerName, const std::string& message) {
    auto* level = ll::service::getLevel();
    if (!level) {
        return;
    }

    level->forEachPlayer([&](Player& player) {
        if (player.getName() == playerName) {
            TextPacket packet;
            packet.mType = TextPacketType::Raw;
            packet.mMessage = message;
            player.sendNetworkPacket(packet);
            return false;
        }
        return true;
    });
}

} // namespace novachat
