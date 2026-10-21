#include "CommandHandler.h"
#include "../NovaChatPlugin.h"
#include "../config/NovaChatConfig.h"
#include "../chat/ChatInterceptor.h"
#include "../network/NetworkClient.h"
#include "../protocol/Packet.h"
#include "../protocol/PacketBuffer.h"
#include "../i18n/I18n.h"
#include "../util/Sha256.h"

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

// FEATURE-002: /nc auth <password>. Player-only. The password is SHA-256
// hashed (lowercase hex, matching the Java MessageDigest path) before it ever
// leaves the client. The backend hasSuperAdminSession gate is the sole
// authority on the resulting session — the client never tracks it locally.
struct AuthParams {
    std::string password;
};

// FEATURE-002: /nc announce <channel> <content>. Player + console. Sends an
// AdminActionPacket(STATUS) with type=ANNOUNCE extra; the backend handleStatus
// dispatch (handleAnnounce) broadcasts via routeMessage (0x03). Console/RCON
// uses the all-zeros sentinel UUID + a "console"="true" extra.
struct AnnounceParams {
    std::string channelId;
    std::string content;
};

// Moderation parameters. All four take a target name first; the trailing
// optionals mirror the bukkit signatures:
//   /nc mute  <player> <duration> [channel]
//   /nc kick  <player> [channel]
//   /nc ban   <player> [duration] [channel]
//   /nc unban <player|uuid> [channel]
// LeviLamina's overload builder has no variadic/greedy tail, so each command
// gets a fixed optional tail and the rest is left to the builder's validation.
struct MuteParams {
    std::string targetName;
    std::string duration;
    std::string channelId;
};

struct KickParams {
    std::string targetName;
    std::string channelId;
};

struct BanParams {
    std::string targetName;
    std::string duration;
    std::string channelId;
};

struct UnbanParams {
    std::string targetName;
    std::string channelId;
};

// FEATURE-002 parity: /nc title <channel> <title> [subtitle]. Sends the same
// AdminActionPacket(STATUS) transport as announce with type=TITLE; the backend
// handleStatus -> handleTitle builds the TitlePacket. Requires a super-admin
// session (the backend hasSuperAdminSession gate in handleStatus), which a
// player obtains with /nc auth. Console cannot authenticate (auth is
// player-only), so a console title is refused by the backend with NC-403.
struct TitleParams {
    std::string channelId;
    std::string title;
    std::string subtitle;
};

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

    // /nc auth <password> — player-only. Hidden from /nc help (the help line
    // is not appended for non-admins and the command has no usage hint in the
    // standard help flow; it is surfaced only to admins via line_auth).
    ncCommand.overload<AuthParams>()
        .text("auth")
        .required("password")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, AuthParams const& params) {
            if (auto* player = origin.getEntity(); player && player->isPlayer()) {
                auto* p = static_cast<Player*>(player);
                handleAuth(p->getName(), p->getUuid().asString(),
                           {params.password});
            } else {
                auto& i18n = i18n::I18n::getInstance();
                output.error(i18n.get("chat.command.player_only", origin.getLocaleCode()));
            }
            output.success();
        });

    // /nc announce <channel> <content> — player + console/RCON. The content
    // parameter is a single token here; multi-word content capture relies on
    // LeviLamina's greedy string support, which needs host SDK verification
    // (see the toolchain note in the final report).
    ncCommand.overload<AnnounceParams>()
        .text("announce")
        .required("channelId")
        .required("content")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, AnnounceParams const& params) {
            std::string playerName;
            std::string playerUuid;
            bool isPlayer = false;
            if (auto* entity = origin.getEntity(); entity && entity->isPlayer()) {
                auto* p = static_cast<Player*>(entity);
                playerName = p->getName();
                playerUuid = p->getUuid().asString();
                isPlayer = true;
            } else {
                // Console/RCON: use a stable display name and the all-zeros
                // sentinel UUID so the backend (and our own
                // handleAdminActionResponse) can recognise the console origin.
                playerName = "Console";
                playerUuid = "00000000-0000-0000-0000-000000000000";
            }
            handleAnnounce(playerName, playerUuid,
                           {params.channelId, params.content, isPlayer ? "true" : "false"});
            output.success();
        });

    // ==================== Moderation subcommands ====================
    //
    // Every moderation action is admin-only. The gate is applied at execute
    // time against origin.getPermissionsLevel() for the same reason /nc reload
    // does it there: LeviLamina's Overload builder exposes no .permission()
    // chain, and /nc itself is registered at Any so the basic subcommands stay
    // usable by everyone.
    //
    // The ChannelAction id and extras are built by the pure builders in
    // ModerationCommands.cpp, which re-check the level so the wire contract is
    // testable without the BDS toolchain. These lambdas only gather the sender
    // identity and the raw argument tokens.
    ncCommand.overload<MuteParams>()
        .text("mute")
        .required("targetName")
        .required("duration")
        .optional("channelId")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, MuteParams const& params) {
            const int level = static_cast<int>(origin.getPermissionsLevel());
            if (level < moderation::MODERATION_MIN_PERMISSION_LEVEL) {
                auto& i18n = i18n::I18n::getInstance();
                output.error(i18n.get("chat.command.no_permission_code", origin.getLocaleCode()));
                return;
            }
            std::string const name = origin.getName();
            std::string uuid;
            bool isConsole = true;
            if (auto* entity = origin.getEntity(); entity && entity->isPlayer()) {
                uuid = static_cast<Player*>(entity)->getUuid().asString();
                isConsole = false;
            }
            handleMute(name, uuid, isConsole, level,
                       {params.targetName, params.duration, params.channelId});
            output.success();
        });

    ncCommand.overload<KickParams>()
        .text("kick")
        .required("targetName")
        .optional("channelId")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, KickParams const& params) {
            const int level = static_cast<int>(origin.getPermissionsLevel());
            if (level < moderation::MODERATION_MIN_PERMISSION_LEVEL) {
                auto& i18n = i18n::I18n::getInstance();
                output.error(i18n.get("chat.command.no_permission_code", origin.getLocaleCode()));
                return;
            }
            std::string const name = origin.getName();
            std::string uuid;
            bool isConsole = true;
            if (auto* entity = origin.getEntity(); entity && entity->isPlayer()) {
                uuid = static_cast<Player*>(entity)->getUuid().asString();
                isConsole = false;
            }
            handleKick(name, uuid, isConsole, level, {params.targetName, params.channelId});
            output.success();
        });

    ncCommand.overload<BanParams>()
        .text("ban")
        .required("targetName")
        .optional("duration")
        .optional("channelId")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, BanParams const& params) {
            const int level = static_cast<int>(origin.getPermissionsLevel());
            if (level < moderation::MODERATION_MIN_PERMISSION_LEVEL) {
                auto& i18n = i18n::I18n::getInstance();
                output.error(i18n.get("chat.command.no_permission_code", origin.getLocaleCode()));
                return;
            }
            std::string const name = origin.getName();
            std::string uuid;
            bool isConsole = true;
            if (auto* entity = origin.getEntity(); entity && entity->isPlayer()) {
                uuid = static_cast<Player*>(entity)->getUuid().asString();
                isConsole = false;
            }
            handleBan(name, uuid, isConsole, level,
                      {params.targetName, params.duration, params.channelId});
            output.success();
        });

    ncCommand.overload<UnbanParams>()
        .text("unban")
        .required("targetName")
        .optional("channelId")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, UnbanParams const& params) {
            const int level = static_cast<int>(origin.getPermissionsLevel());
            if (level < moderation::MODERATION_MIN_PERMISSION_LEVEL) {
                auto& i18n = i18n::I18n::getInstance();
                output.error(i18n.get("chat.command.no_permission_code", origin.getLocaleCode()));
                return;
            }
            std::string const name = origin.getName();
            std::string uuid;
            bool isConsole = true;
            if (auto* entity = origin.getEntity(); entity && entity->isPlayer()) {
                uuid = static_cast<Player*>(entity)->getUuid().asString();
                isConsole = false;
            }
            handleUnban(name, uuid, isConsole, level, {params.targetName, params.channelId});
            output.success();
        });

    // /nc title <channel> <title> [subtitle] — player + console/RCON transport,
    // but the backend STATUS gate requires a super-admin session and only a
    // player can obtain one (/nc auth is player-only). Console therefore gets
    // NC-403 back rather than a working title; that is the backend's decision,
    // not a silent no-op on our side.
    ncCommand.overload<TitleParams>()
        .text("title")
        .required("channelId")
        .required("title")
        .optional("subtitle")
        .execute([this](CommandOrigin const& origin, CommandOutput& output, TitleParams const& params) {
            std::string playerName;
            std::string playerUuid;
            if (auto* entity = origin.getEntity(); entity && entity->isPlayer()) {
                auto* p = static_cast<Player*>(entity);
                playerName = p->getName();
                playerUuid = p->getUuid().asString();
            } else {
                playerName = "Console";
                playerUuid = "00000000-0000-0000-0000-000000000000";
            }
            handleTitle(playerName, playerUuid,
                        {params.channelId, params.title, params.subtitle});
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
    // FEATURE-002: /nc auth + /nc announce are only revealed to admins.
    if (isAdmin) {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.help.line_auth", locale)));
        sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.help.line_announce", locale)));
        // Moderation is admin-gated too, so its usage lines stay hidden from
        // ordinary players.
        sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.help.line_mute", locale)));
        sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.help.line_kick", locale)));
        sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.help.line_ban", locale)));
        sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.help.line_unban", locale)));
        // /nc title shares announce's backend gate (super-admin session), so it
        // is advertised with the same audience.
        sendMessage(playerName, ChatInterceptor::convertColorCodes(i18n.get("chat.command.help.line_title", locale)));
    }
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
    if (mPlugin.reloadConfiguration()) {
        std::string prefix = mPlugin.getConfig()->getPrefix();
        sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get("chat.command.reload.success", locale)));
    } else {
        std::string prefix = mPlugin.getConfig()->getPrefix();
        mPlugin.getSelf().getLogger().error(
            "Configuration reload rejected; previous runtime values remain active: {}",
            mPlugin.getConfig()->getLastError());
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

// Parse a hyphenated Minecraft UUID string (xxxxxxxx-xxxx-xxxx-xxxx-
// xxxxxxxxxxxx) into the protocol UUID struct. Mirrors the parse already used
// in ChatInterceptor::sendToBackend. On any malformed input the nil UUID
// (both bits zero) is returned, which the backend treats as the console
// sentinel — so a bad player UUID degrades safely rather than masquerading as
// another player.
static protocol::UUID parsePlayerUuid(const std::string& playerUuid) {
    protocol::UUID uuid{};
    std::string hex = playerUuid;
    hex.erase(std::remove(hex.begin(), hex.end(), '-'), hex.end());
    if (hex.size() == 32 &&
        hex.find_first_not_of("0123456789abcdefABCDEF") == std::string::npos) {
        uuid.mostSigBits  = std::stoull(hex.substr(0, 16),  nullptr, 16);
        uuid.leastSigBits = std::stoull(hex.substr(16, 16), nullptr, 16);
    }
    return uuid;
}

void CommandHandler::handleAuth(const std::string& playerName, const std::string& playerUuid,
                                const std::vector<std::string>& args) {
    auto& i18n = i18n::I18n::getInstance();
    std::string locale = "zh_CN";
    if (auto* interceptor = mPlugin.getChatInterceptor()) {
        locale = interceptor->getPlayerLocale(playerUuid);
    }
    std::string prefix = mPlugin.getConfig()->getPrefix();

    // The /nc auth overload is declared .required("password"), so LeviLamina
    // rejects an empty password before this lambda runs; this guard is pure
    // defense and intentionally silent (there is no chat.auth.usage key in the
    // FEATURE-002 i18n set, and reusing an unrelated key would mislead).
    if (args.empty() || args[0].empty()) {
        return;
    }

    auto* networkClient = mPlugin.getNetworkClient();
    if (!networkClient || !networkClient->isConnected()) {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get("chat.network.not_connected", locale)));
        return;
    }

    // FEATURE-002: hash the password with SHA-256 lowercase hex before it
    // leaves the client (parity with the Java MessageDigest path). The client
    // never stores the hash or any super-admin session state — the backend
    // hasSuperAdminSession gate is the sole authority.
    std::string passwordHash = novachat::util::Sha256::hex(args[0]);

    auto packet = std::make_unique<AdminActionPacket>();
    packet->setAction(AdminAction::AUTH);
    packet->setPlayerId(parsePlayerUuid(playerUuid));
    packet->setPasswordHash(passwordHash);
    packet->addExtra("playerName", playerName);

    // Track the request so the async AdminActionResponse can be routed back
    // to this player (handleAdminActionResponse pops by request UUID).
    std::string reqId = packet->getRequestId().toString();
    if (auto* interceptor = mPlugin.getChatInterceptor()) {
        interceptor->registerPendingAdminAction(reqId, playerUuid);
    }

    networkClient->sendPacket(std::move(packet));

    // Show PROGRESS, not success — the backend NC-403 gate is authoritative.
    sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
        i18n.get("chat.auth.progress", locale)));
}

void CommandHandler::handleAnnounce(const std::string& playerName, const std::string& playerUuid,
                                    const std::vector<std::string>& args) {
    auto& i18n = i18n::I18n::getInstance();
    std::string locale = "zh_CN";
    if (auto* interceptor = mPlugin.getChatInterceptor()) {
        locale = interceptor->getPlayerLocale(playerUuid);
    }
    std::string prefix = mPlugin.getConfig()->getPrefix();

    if (args.size() < 2 || args[0].empty() || args[1].empty()) {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get("chat.announce.usage", locale)));
        return;
    }

    std::string channelId = args[0];
    std::string content = args[1];
    bool isPlayer = (args.size() >= 3) ? (args[2] == "true") : true;

    auto* networkClient = mPlugin.getNetworkClient();
    if (!networkClient || !networkClient->isConnected()) {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get("chat.network.not_connected", locale)));
        return;
    }

    // Console/RCON uses the all-zeros sentinel UUID (both bits zero) plus a
    // "console"="true" extra, matching the Java AnnounceCommand behaviour.
    protocol::UUID playerId = isPlayer
        ? parsePlayerUuid(playerUuid)
        : protocol::UUID{};

    auto packet = std::make_unique<AdminActionPacket>();
    packet->setAction(AdminAction::STATUS);
    packet->setPlayerId(playerId);
    packet->setTarget(channelId);
    packet->addExtra("type", "ANNOUNCE");
    packet->addExtra("operatorName", playerName);
    packet->addExtra("content", content);
    if (!isPlayer) {
        packet->addExtra("console", "true");
    }

    // Track the request so the async AdminActionResponse can be routed back
    // (player UUID for in-game senders; the all-zeros sentinel string for
    // console, which handleAdminActionResponse recognises as the console path).
    std::string reqId = packet->getRequestId().toString();
    std::string trackingUuid = isPlayer ? playerUuid
                                        : "00000000-0000-0000-0000-000000000000";
    if (auto* interceptor = mPlugin.getChatInterceptor()) {
        interceptor->registerPendingAdminAction(reqId, trackingUuid);
    }

    networkClient->sendPacket(std::move(packet));

    // Show PROGRESS, not success — the backend handleStatus/NC-403 gate is
    // authoritative and the broadcast is only complete once routeMessage fires.
    sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
        i18n.get("chat.announce.progress", locale, {channelId})));
}

// ==================== Moderation ====================

void CommandHandler::dispatchModeration(moderation::ModerationRequest request,
                                        const std::string& senderName,
                                        const std::string& senderUuid,
                                        const std::string& progressKey,
                                        const std::vector<std::string>& progressArgs) {
    auto& i18n = i18n::I18n::getInstance();
    std::string locale = "zh_CN";
    if (auto* interceptor = mPlugin.getChatInterceptor()) {
        if (!senderUuid.empty()) {
            locale = interceptor->getPlayerLocale(senderUuid);
        }
    }
    std::string prefix = mPlugin.getConfig()->getPrefix();

    if (!request.ok()) {
        // Rejection carries the i18n key and (for duration errors) the offending
        // token in {0}. Every moderation usage/validation key takes at most one
        // argument, so a single-element substitution covers all of them.
        std::vector<std::string> args;
        if (!request.errorArg.empty()) {
            args.push_back(request.errorArg);
        }
        sendMessage(senderName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get(request.errorKey, locale, args)));
        return;
    }

    auto* networkClient = mPlugin.getNetworkClient();
    if (!networkClient || !networkClient->isConnected()) {
        sendMessage(senderName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get("chat.network.not_connected", locale)));
        return;
    }

    // Correlate the request BEFORE it goes on the wire, otherwise a fast
    // backend response could arrive before the map is populated and be dropped
    // as uncorrelated. The response echoes only this request UUID, so it is the
    // sole way to attribute an outcome to the operator who issued the action.
    // A console sender has no player UUID; the sentinel makes
    // ChatInterceptor::reportOperatorOutcome log the result instead.
    if (auto* interceptor = mPlugin.getChatInterceptor()) {
        std::string const origin = senderUuid.empty()
            ? std::string("00000000-0000-0000-0000-000000000000")
            : senderUuid;
        interceptor->registerPendingChannelAction(request.packet->getRequestId().toString(), origin);
    }

    networkClient->sendPacket(std::move(request.packet));

    // Progress line only. The backend's moderation permission check and
    // validation are authoritative; the per-action outcome arrives later as a
    // ChannelActionResponse and is rendered by ChatInterceptor from the
    // correlation registered above.
    sendMessage(senderName, ChatInterceptor::convertColorCodes(prefix +
        i18n.get(progressKey, locale, progressArgs)));
}

void CommandHandler::handleMute(const std::string& senderName, const std::string& senderUuid,
                                bool isConsole, int permissionLevel,
                                const std::vector<std::string>& args) {
    // args: { targetName, duration, channelId }
    std::string targetName = args.size() > 0 ? args[0] : "";
    std::string duration   = args.size() > 1 ? args[1] : "";
    std::string channelId  = args.size() > 2 ? args[2] : "";

    // Mute is channel-scoped. A player who omits the channel gets their active
    // one (the same convenience /nc kick has); console has no active channel,
    // so an omitted channel there stays empty and the builder rejects it with
    // the "specify a channel" line.
    if (channelId.empty() && !isConsole) {
        if (auto* interceptor = mPlugin.getChatInterceptor()) {
            channelId = interceptor->getPlayerState(senderUuid).currentChannel;
        }
    }

    auto* interceptor = mPlugin.getChatInterceptor();
    std::string targetId;
    if (interceptor) {
        targetId = interceptor->getPlayerUuidByName(targetName);
    }

    auto const sender = isConsole ? moderation::ModerationSender::consoleSender()
                                  : moderation::ModerationSender::player(senderName, senderUuid);
    auto request = moderation::buildMuteRequest(permissionLevel, sender, targetName, targetId,
                                                duration, channelId);

    // The progress line quotes the resolved duration, matching the bukkit line.
    long const seconds = moderation::parseDurationSeconds(duration);
    std::string const prettyDuration = seconds > 0 ? std::to_string(seconds) + "s" : duration;

    dispatchModeration(std::move(request), senderName, senderUuid,
                       "chat.mute.progress", {targetName, channelId, prettyDuration});
}

void CommandHandler::handleKick(const std::string& senderName, const std::string& senderUuid,
                                bool isConsole, int permissionLevel,
                                const std::vector<std::string>& args) {
    // args: { targetName, channelId }
    std::string targetName = args.size() > 0 ? args[0] : "";
    std::string channelId  = args.size() > 1 ? args[1] : "";

    if (channelId.empty() && !isConsole) {
        if (auto* interceptor = mPlugin.getChatInterceptor()) {
            channelId = interceptor->getPlayerState(senderUuid).currentChannel;
        }
    }

    auto* interceptor = mPlugin.getChatInterceptor();
    std::string targetId;
    if (interceptor) {
        targetId = interceptor->getPlayerUuidByName(targetName);
    }

    auto const sender = isConsole ? moderation::ModerationSender::consoleSender()
                                  : moderation::ModerationSender::player(senderName, senderUuid);
    auto request = moderation::buildKickRequest(permissionLevel, sender, targetName, targetId,
                                                channelId);

    dispatchModeration(std::move(request), senderName, senderUuid,
                       "chat.kick.progress", {targetName, channelId});
}

void CommandHandler::handleBan(const std::string& senderName, const std::string& senderUuid,
                               bool isConsole, int permissionLevel,
                               const std::vector<std::string>& args) {
    // args: { targetName, duration, channelId }
    std::string targetName = args.size() > 0 ? args[0] : "";
    std::string duration   = args.size() > 1 ? args[1] : "";
    std::string channelId  = args.size() > 2 ? args[2] : "";

    // /nc ban's second argument may be either a duration or a channel, since
    // both are optional. Mirror bukkit BanCommand: if the token parses as a
    // duration it is one, otherwise it is the channel. A leading channel
    // argument is therefore honoured, and an empty channel stays empty -- the
    // network-wide form -- rather than being filled from the sender's active
    // channel.
    moderation::normalizeBanArgs(duration, channelId);

    auto* interceptor = mPlugin.getChatInterceptor();
    std::string targetId;
    if (interceptor) {
        targetId = interceptor->getPlayerUuidByName(targetName);
    }

    auto const sender = isConsole ? moderation::ModerationSender::consoleSender()
                                  : moderation::ModerationSender::player(senderName, senderUuid);
    auto request = moderation::buildBanRequest(permissionLevel, sender, targetName, targetId,
                                               duration, channelId);

    std::string scope = channelId.empty() ? "all channels" : channelId;
    dispatchModeration(std::move(request), senderName, senderUuid,
                       "chat.ban.progress", {targetName, scope});
}

void CommandHandler::handleUnban(const std::string& senderName, const std::string& senderUuid,
                                 bool isConsole, int permissionLevel,
                                 const std::vector<std::string>& args) {
    // args: { targetName-or-uuid, channelId }
    std::string targetArg = args.size() > 0 ? args[0] : "";
    std::string channelId = args.size() > 1 ? args[1] : "";

    auto* interceptor = mPlugin.getChatInterceptor();
    std::string locale = "zh_CN";
    if (interceptor && !senderUuid.empty()) {
        locale = interceptor->getPlayerLocale(senderUuid);
    }
    std::string prefix = mPlugin.getConfig()->getPrefix();

    // The ban target is usually offline -- that is the point of a ban -- so a
    // name that resolves to nobody is expected, not an error. Say so instead of
    // failing silently; the name is still forwarded for the backend to resolve
    // across all connected servers.
    if (interceptor && !targetArg.empty() && !moderation::looksLikeUuid(targetArg)
        && interceptor->getPlayerUuidByName(targetArg).empty()) {
        sendMessage(senderName, ChatInterceptor::convertColorCodes(prefix +
            i18n::I18n::getInstance().get("chat.unban.offline_hint", locale, {targetArg})));
    }

    auto const sender = isConsole ? moderation::ModerationSender::consoleSender()
                                  : moderation::ModerationSender::player(senderName, senderUuid);
    auto request = moderation::buildUnbanRequest(permissionLevel, sender, targetArg, channelId);

    std::string scope = channelId.empty() ? "all channels" : channelId;
    dispatchModeration(std::move(request), senderName, senderUuid,
                       "chat.unban.progress", {targetArg, scope});
}

void CommandHandler::handleTitle(const std::string& playerName, const std::string& playerUuid,
                                 const std::vector<std::string>& args) {
    auto& i18n = i18n::I18n::getInstance();
    std::string locale = "zh_CN";
    if (auto* interceptor = mPlugin.getChatInterceptor()) {
        locale = interceptor->getPlayerLocale(playerUuid);
    }
    std::string prefix = mPlugin.getConfig()->getPrefix();

    if (args.size() < 2 || args[0].empty() || args[1].empty()) {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get("chat.title.usage", locale)));
        return;
    }


    std::string channelId = args[0];
    std::string title = args[1];
    std::string subtitle = args.size() > 2 ? args[2] : "";

    auto* networkClient = mPlugin.getNetworkClient();
    if (!networkClient || !networkClient->isConnected()) {
        sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
            i18n.get("chat.network.not_connected", locale)));
        return;
    }

    // Same transport as /nc announce: STATUS + a type extra. Console uses the
    // all-zeros sentinel so the backend can recognise the origin.
    bool const isConsole = (playerUuid == "00000000-0000-0000-0000-000000000000");

    auto packet = std::make_unique<AdminActionPacket>();
    packet->setAction(AdminAction::STATUS);
    packet->setPlayerId(isConsole ? protocol::UUID{} : parsePlayerUuid(playerUuid));
    packet->setTarget(channelId);
    packet->addExtra("type", "TITLE");
    packet->addExtra("operatorName", playerName);
    packet->addExtra("title", title);
    packet->addExtra("subtitle", subtitle);
    if (isConsole) {
        packet->addExtra("console", "true");
    }

    std::string reqId = packet->getRequestId().toString();
    if (auto* interceptor = mPlugin.getChatInterceptor()) {
        interceptor->registerPendingAdminAction(reqId, playerUuid);
    }

    networkClient->sendPacket(std::move(packet));

    // Progress only: the backend hasSuperAdminSession gate decides. Without a
    // prior /nc auth the response is NC-403 and handleAdminActionResponse
    // surfaces it.
    sendMessage(playerName, ChatInterceptor::convertColorCodes(prefix +
        i18n.get("chat.title.progress", locale, {channelId})));
}

} // namespace novachat
