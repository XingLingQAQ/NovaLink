#include "CommandHandler.h"
#include "../NovaChatPlugin.h"
#include "../config/NovaChatConfig.h"
#include "../chat/ChatInterceptor.h"
#include "../network/NetworkClient.h"
#include "../protocol/Packet.h"
#include "../i18n/I18n.h"

#include <ll/api/io/Logger.h>
#include <ll/api/command/Command.h>
#include <ll/api/command/CommandHandle.h>
#include <ll/api/command/CommandRegistrar.h>
#include <ll/api/service/Bedrock.h>
#include <mc/world/level/Level.h>
#include <mc/world/actor/player/Player.h>
#include <mc/server/commands/CommandOrigin.h>
#include <mc/server/commands/CommandOutput.h>
#include <mc/server/commands/CommandPermissionLevel.h>
#include <mc/network/packet/TextPacket.h>

#include <algorithm>

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

struct ListParams {};

struct WhoParams {
    std::string channelId;
};

struct ToggleParams {};

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

    auto& registrar = ll::command::CommandRegistrar::getServerInstance();

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
            const bool isAdmin = origin.getPermissionsLevel() >= CommandPermissionLevel::GameDirectors;
            if (auto* player = origin.getEntity(); player && player->isPlayer()) {
                auto* p = static_cast<Player*>(player);
                handleHelp(p->getName(), p->getUuid().asString(), {}, isAdmin);
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

    // /nc list
    ncCommand.overload<ListParams>()
        .text("list")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, ListParams const&) {
            if (auto* player = origin.getEntity(); player && player->isPlayer()) {
                auto* p = static_cast<Player*>(player);
                handleList(p->getName(), p->getUuid().asString(), {});
            }
            output.success();
        });

    // /nc who [channel]
    ncCommand.overload<WhoParams>()
        .text("who")
        .optional("channelId")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, WhoParams const& params) {
            if (auto* player = origin.getEntity(); player && player->isPlayer()) {
                auto* p = static_cast<Player*>(player);
                handleWho(p->getName(), p->getUuid().asString(),
                          params.channelId.empty() ? std::vector<std::string>{} :
                          std::vector<std::string>{params.channelId});
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

    // /nc reload (admin only)
    //
    // LeviLamina's Overload API has no per-overload permission gate (the
    // Overload<Params> builder only exposes optional/required/text/.../execute,
    // there is no .permission() chain). The /nc command itself is registered at
    // CommandPermissionLevel::Any so basic-user subcommands (help/join/...)
    // remain usable by everyone, so reload must gate on admin at execute time.
    // We compare the origin's CommandPermissionLevel against GameDirectors (OP)
    // — the same level the LeviLamina core uses for its own admin-only commands
    // (see ll/core/Config.h: `CommandPermissionLevel::GameDirectors`).
    ncCommand.overload<ReloadParams>()
        .text("reload")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, ReloadParams const&) {
            if (origin.getPermissionsLevel() < CommandPermissionLevel::GameDirectors) {
                auto& i18n = i18n::I18n::getInstance();
                output.error(i18n.get("chat.command.no_permission_code", origin.getLocaleCode()));
                return;
            }
            if (auto* player = origin.getEntity(); player && player->isPlayer()) {
                auto* p = static_cast<Player*>(player);
                handleReload(p->getName(), p->getUuid().asString(), {});
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

std::vector<std::string> CommandHandler::completeChannel(const std::string& partial) const {
    std::vector<std::string> result;
    if (auto* interceptor = mPlugin.getChatInterceptor()) {
        auto known = interceptor->getKnownChannels();
        for (const auto& ch : known) {
            if (ch.rfind(partial, 0) == 0) {
                result.push_back(ch);
            }
        }
    }
    return result;
}

void CommandHandler::handleHelp(const std::string& playerName, const std::string& playerUuid,
                                const std::vector<std::string>& args, bool isAdmin) {
    auto& i18n = i18n::I18n::getInstance();
    std::string locale = "zh_CN";
    if (auto* interceptor = mPlugin.getChatInterceptor()) {
        locale = interceptor->getPlayerLocale(playerUuid);
    }
    std::string prefix = mPlugin.getConfig()->getPrefix();

    sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix + i18n.get("chat.command.help.title", locale)));
    sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.help.line_help", locale)));
    sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.help.line_join", locale)));
    sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.help.line_leave", locale)));
    sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.help.line_list", locale)));
    sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.help.line_who", locale)));
    sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.help.line_toggle", locale)));
    // Mirror the Java/Python platforms: only reveal the reload line to admins.
    if (isAdmin) {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.help.line_reload", locale)));
    }
}

void CommandHandler::handleJoin(const std::string& playerName, const std::string& playerUuid,
                                 const std::vector<std::string>& args) {
    auto& i18n = i18n::I18n::getInstance();
    std::string locale = "zh_CN";
    if (auto* interceptor = mPlugin.getChatInterceptor()) {
        locale = interceptor->getPlayerLocale(playerUuid);
    }
    std::string prefix = mPlugin.getConfig()->getPrefix();

    if (args.empty()) {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix + i18n.get("chat.command.usage.join", locale)));
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

        sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get("chat.join.joined", locale, {channelId})));
    } else {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get("chat.network.not_connected", locale)));
    }
}

void CommandHandler::handleLeave(const std::string& playerName, const std::string& playerUuid,
                                  const std::vector<std::string>& args) {
    auto* interceptor = mPlugin.getChatInterceptor();
    auto& i18n = i18n::I18n::getInstance();
    std::string locale = interceptor ? interceptor->getPlayerLocale(playerUuid) : "zh_CN";
    std::string prefix = mPlugin.getConfig()->getPrefix();

    if (!interceptor) {
        return;
    }

    auto& state = interceptor->getPlayerState(playerUuid);
    std::string currentChannel = state.currentChannel;
    std::string defaultChannel = mPlugin.getConfig()->getDefaultChannel();

    if (currentChannel == defaultChannel) {
        // Already on the default channel; nothing to leave.
        sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get("chat.action.leave_simple", locale, {currentChannel})));
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

    sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
        i18n.get("chat.leave.left", locale, {currentChannel, defaultChannel})));
}

void CommandHandler::handleList(const std::string& playerName, const std::string& playerUuid,
                                 const std::vector<std::string>& args) {
    auto& i18n = i18n::I18n::getInstance();
    std::string locale = "zh_CN";
    if (auto* interceptor = mPlugin.getChatInterceptor()) {
        locale = interceptor->getPlayerLocale(playerUuid);
    }
    std::string prefix = mPlugin.getConfig()->getPrefix();

    sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix + i18n.get("chat.command.list.title", locale)));

    auto* interceptor = mPlugin.getChatInterceptor();
    if (!interceptor) {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.list.empty", locale)));
        return;
    }

    auto known = interceptor->getKnownChannels();
    if (known.empty()) {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.list.empty", locale)));
    } else {
        // Sort for stable output.
        std::sort(known.begin(), known.end());
        for (const auto& ch : known) {
            sendMessage(playerName, ChatInterceptor::convertColorCodes("&7- &b" + ch));
        }
    }

    sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.list.tail", locale)));
}

void CommandHandler::handleWho(const std::string& playerName, const std::string& playerUuid,
                                const std::vector<std::string>& args) {
    auto* interceptor = mPlugin.getChatInterceptor();
    auto& i18n = i18n::I18n::getInstance();
    std::string locale = interceptor ? interceptor->getPlayerLocale(playerUuid) : "zh_CN";
    std::string prefix = mPlugin.getConfig()->getPrefix();

    std::string channelId;
    if (!args.empty()) {
        channelId = args[0];
    } else if (interceptor) {
        channelId = interceptor->getPlayerState(playerUuid).currentChannel;
    }

    if (channelId.empty()) {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get("chat.who.specify_channel", locale)));
        return;
    }

    auto* networkClient = mPlugin.getNetworkClient();
    if (!networkClient || !networkClient->isConnected()) {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get("chat.who.unavailable", locale)));
        return;
    }

    // Fire a WHO channel action; the backend replies with a
    // ChannelActionResponse whose extra carries the member list.
    if (interceptor) {
        interceptor->whoChannel(playerUuid, channelId);
    }
    sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
        i18n.get("chat.who.fetching", locale, {channelId})));
}

void CommandHandler::handleToggle(const std::string& playerName, const std::string& playerUuid,
                                   const std::vector<std::string>& args) {
    auto* interceptor = mPlugin.getChatInterceptor();
    auto& i18n = i18n::I18n::getInstance();
    std::string locale = interceptor ? interceptor->getPlayerLocale(playerUuid) : "zh_CN";
    std::string prefix = mPlugin.getConfig()->getPrefix();

    if (!interceptor) {
        return;
    }

    ChatMode newMode = interceptor->toggleChatMode(playerUuid);

    std::string modeStr = (newMode == ChatMode::REPLACE) ? "NovaChat" : "Hybrid";
    sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
        i18n.get("chat.command.toggle.switched", locale, {modeStr})));
}

void CommandHandler::handleReload(const std::string& playerName, const std::string& playerUuid,
                                   const std::vector<std::string>& args) {
    // Permission check is enforced at the /nc reload overload entry point
    // (CommandPermissionLevel::GameDirectors gate in registerCommands()).
    auto& i18n = i18n::I18n::getInstance();
    std::string locale = "zh_CN";
    if (auto* interceptor = mPlugin.getChatInterceptor()) {
        locale = interceptor->getPlayerLocale(playerUuid);
    }
    std::string prefix = mPlugin.getConfig()->getPrefix();

    if (mPlugin.getConfig()->reload()) {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get("chat.command.reload.success", locale)));
    } else {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get("chat.action.failed", locale)));
    }
}

void CommandHandler::sendLocalized(const std::string& playerName, const std::string& playerUuid,
                                    const std::string& key, const std::vector<std::string>& args) {
    auto& i18n = i18n::I18n::getInstance();
    std::string locale = "zh_CN";
    if (auto* interceptor = mPlugin.getChatInterceptor()) {
        locale = interceptor->getPlayerLocale(playerUuid);
    }
    sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get(key, locale, args)));
}

void CommandHandler::sendMessage(const std::string& playerName, const std::string& message) {
    auto level = ll::service::getLevel();
    if (!level) {
        return;
    }

    level->forEachPlayer([&](Player& player) {
        if (player.getName() == playerName) {
            TextPacket packet = TextPacket::createRawMessage(message);
            player.sendNetworkPacket(packet);
            return false;
        }
        return true;
    });
}

} // namespace novachat
