#include "ChatInterceptor.h"
#include "../NovaChatPlugin.h"
#include "../config/NovaChatConfig.h"
#include "../network/NetworkClient.h"
#include "../protocol/Packet.h"
#include "../protocol/PacketBuffer.h"
#include "../i18n/I18n.h"

#include <ll/api/io/Logger.h>
#include <ll/api/event/EventBus.h>
#include <ll/api/event/player/PlayerChatEvent.h>
#include <ll/api/event/player/PlayerJoinEvent.h>
#include <ll/api/event/player/PlayerDisconnectEvent.h>
#include <ll/api/service/Bedrock.h>
#include <mc/world/level/Level.h>
#include <mc/world/actor/player/Player.h>
#include <mc/network/packet/TextPacket.h>
#include <mc/network/packet/SetTitlePacket.h>

#include <nlohmann/json.hpp>
#include <regex>
#include <algorithm>
#include <string>
#include <unordered_set>

namespace novachat {

using namespace novachat::protocol;
using namespace novachat::network;

// Event listener handles
static ll::event::ListenerPtr sChatListener;
static ll::event::ListenerPtr sJoinListener;
static ll::event::ListenerPtr sLeaveListener;

ChatInterceptor::ChatInterceptor(NovaChatPlugin& plugin)
    : mPlugin(plugin) {
    // Load replace_vanilla setting from config
    if (auto* config = plugin.getConfig()) {
        mReplaceVanilla = config->isReplaceVanilla();
    }
}

ChatInterceptor::~ChatInterceptor() {
    unregisterHooks();
}

void ChatInterceptor::registerHooks() {
    if (mHooksRegistered) {
        return;
    }

    auto& logger = mPlugin.getSelf().getLogger();
    logger.info("Registering chat hooks...");

    auto& eventBus = ll::event::EventBus::getInstance();
    
    // Register chat event listener - intercepts player chat messages
    sChatListener = eventBus.emplaceListener<ll::event::player::PlayerChatEvent>(
        [this](ll::event::player::PlayerChatEvent& event) {
            auto& player = event.self();
            std::string playerName = player.getName();
            std::string playerUuid = player.getUuid().asString();
            std::string message = event.message();

            // Update name to UUID mapping
            {
                std::lock_guard<std::mutex> lock(mNameMapMutex);
                mNameToUuid[playerName] = playerUuid;
            }

            // Handle the chat message
            bool handled = handlePlayerChat(playerName, playerUuid, message);

            // Get player's chat mode
            auto& state = getPlayerState(playerUuid);
            
            // Cancel vanilla chat if:
            // 1. Global replace mode is enabled, OR
            // 2. Player's individual mode is REPLACE
            // AND the message was handled successfully
            if (handled && (mReplaceVanilla || state.chatMode == ChatMode::REPLACE)) {
                event.cancel();
            }
        }
    );

    // Register player join event - initialize player state
    sJoinListener = eventBus.emplaceListener<ll::event::player::PlayerJoinEvent>(
        [this](ll::event::player::PlayerJoinEvent& event) {
            auto& player = event.self();
            std::string playerName = player.getName();
            std::string playerUuid = player.getUuid().asString();

            // Read the player's client locale straight from the Bedrock login
            // chain (Player::getLocaleCode() returns the language code the
            // client sent in its login packet, e.g. "zh_CN"/"en_US"/"ja_JP").
            // Empty string is fine -- onPlayerJoin falls back to zh_CN.
            std::string localeCode;
            try {
                localeCode = player.getLocaleCode();
            } catch (...) {
                // getLocaleCode should not throw, but guard anyway: an empty
                // locale simply triggers the hard-default fallback below.
            }

            onPlayerJoin(playerName, playerUuid, localeCode);
        }
    );

    // Register player leave event - cleanup player state.
    // levilamina 26.20.7 renamed PlayerLeaveEvent -> PlayerDisconnectEvent.
    sLeaveListener = eventBus.emplaceListener<ll::event::player::PlayerDisconnectEvent>(
        [this](ll::event::player::PlayerDisconnectEvent& event) {
            auto& player = event.self();
            std::string playerUuid = player.getUuid().asString();

            onPlayerLeave(playerUuid);
        }
    );

    // Register packet handlers for incoming messages from backend
    registerPacketHandlers();

    mHooksRegistered = true;
    logger.info("Chat hooks registered successfully.");
}

void ChatInterceptor::registerPacketHandlers() {
    auto* networkClient = mPlugin.getNetworkClient();
    if (!networkClient) {
        return;
    }

    auto& logger = mPlugin.getSelf().getLogger();

    // Handle incoming chat messages from backend
    networkClient->registerHandler(PacketIds::CHAT_MESSAGE,
        [this](std::unique_ptr<Packet> packet) {
            auto* chatPacket = static_cast<ChatMessagePacket*>(packet.get());

            // Format the message using local format configuration
            std::string formatted = formatMessage(
                chatPacket->getChannelId(),
                chatPacket->getSenderName(),
                chatPacket->getContent()
            );

            // Convert color codes for Bedrock
            formatted = convertColorCodes(formatted);

            // Broadcast to all players in the channel
            broadcastToChannel(chatPacket->getChannelId(), formatted);

            if (mPlugin.getConfig()->isDebug()) {
                mPlugin.getSelf().getLogger().debug(
                    "Received chat from backend: [{}] {}: {}",
                    chatPacket->getChannelId(),
                    chatPacket->getSenderName(),
                    chatPacket->getContent()
                );
            }
        }
    );

    // Handle channel action responses — route kick/mute to the target player,
    // surface join/leave results, and track the channel in known channels.
    networkClient->registerHandler(PacketIds::CHANNEL_ACTION_RESPONSE,
        [this](std::unique_ptr<Packet> packet) {
            auto* response = static_cast<ChannelActionResponsePacket*>(packet.get());
            auto& log = mPlugin.getSelf().getLogger();

            // Track the channel as known regardless of outcome.
            addKnownChannel(response->getChannelId());

            if (response->isSuccess()) {
                log.info("Channel action successful: {}", response->getMessage());
                // Route kick/mute target-side notifications.
                ChannelAction action = response->getAction();
                if (action == ChannelAction::KICK || action == ChannelAction::MUTE) {
                    // The "extra" map carries operatorName and (for mute) duration.
                    const auto& extra = response->getExtra();
                    std::string operatorName = extra.count("operatorName")
                        ? extra.at("operatorName") : "";
                    std::string targetUuid = extra.count("targetUuid")
                        ? extra.at("targetUuid") : "";
                    std::string duration = extra.count("duration")
                        ? extra.at("duration") : "";
                    if (!targetUuid.empty()) {
                        if (action == ChannelAction::KICK) {
                            notifyKickTarget(targetUuid, operatorName, response->getChannelId());
                        } else {
                            notifyMuteTarget(targetUuid, operatorName,
                                             response->getChannelId(), duration);
                        }
                    }
                }
            } else {
                log.warn("Channel action failed: {} ({})",
                    response->getMessage(), response->getErrorCode());
            }
        }
    );

    // Handle ConfigSync — mirror Java ConfigSyncChannels.extract: build the
    // known-channel set from global_channels keys + the matching clients[]
    // entry's channels keys (filtered by this client's username), then replace
    // the known-channel registry. Only mKnownChannels is touched; the active
    // per-player channel (PlayerChatState::currentChannel) is never overwritten.
    // Parsing is best-effort: a bad payload logs a warning and leaves the
    // existing registry intact rather than throwing.
    //
    // TODO(no-toolchain): there is no xmake/MSVC on this host, so the C++ is
    // NOT compiled. Verify against the shared fixture
    // NovaChat/Bedrock/test-fixtures/config-sync-payload.json (plus the
    // empty/malformed siblings) on a tooled host. The Endstone/PMMP siblings
    // carry executable tests for the same contract.
    networkClient->registerHandler(PacketIds::CONFIG_SYNC,
        [this](std::unique_ptr<Packet> packet) {
            auto* sync = static_cast<ConfigSyncPacket*>(packet.get());
            if (mPlugin.getConfig()->isDebug()) {
                mPlugin.getSelf().getLogger().debug(
                    "Received config sync ({} bytes)", sync->getConfigJson().size());
            }

            const std::string& configJson = sync->getConfigJson();
            if (configJson.empty()) {
                return;
            }

            std::unordered_set<std::string> channels;
            try {
                auto root = nlohmann::json::parse(configJson);
                if (!root.is_object()) {
                    mPlugin.getSelf().getLogger().warn(
                        "ConfigSync: expected JSON object");
                    return;
                }

                // Global channels: keys of the global_channels mapping.
                if (root.contains("global_channels")) {
                    const auto& gc = root.at("global_channels");
                    if (gc.is_object()) {
                        for (auto it = gc.begin(); it != gc.end(); ++it) {
                            channels.insert(it.key());
                        }
                    } else if (!gc.is_null()) {
                        mPlugin.getSelf().getLogger().warn(
                            "ConfigSync: global_channels must be an object");
                    }
                }

                // Per-client channels for this client only. Blank username
                // -> globals only (matches the Java extractor).
                const std::string& username = mPlugin.getConfig()->getUsername();
                if (!username.empty()) {
                    if (root.contains("clients")) {
                        const auto& clients = root.at("clients");
                        if (clients.is_array()) {
                            for (const auto& entry : clients) {
                                if (!entry.is_object() || !entry.contains("username")) {
                                    continue;
                                }
                                const auto& entryUser = entry.at("username");
                                if (!entryUser.is_string()) {
                                    continue;
                                }
                                if (entryUser.get<std::string>() != username) {
                                    continue;
                                }
                                if (entry.contains("channels")) {
                                    const auto& cc = entry.at("channels");
                                    if (cc.is_object()) {
                                        for (auto it = cc.begin(); it != cc.end(); ++it) {
                                            channels.insert(it.key());
                                        }
                                    } else if (!cc.is_null()) {
                                        mPlugin.getSelf().getLogger().warn(
                                            "ConfigSync: client.channels must be an object");
                                    }
                                }
                                break;
                            }
                        } else if (!clients.is_null()) {
                            mPlugin.getSelf().getLogger().warn(
                                "ConfigSync: clients must be an array");
                        }
                    }
                }

                std::vector<std::string> sorted(channels.begin(), channels.end());
                std::sort(sorted.begin(), sorted.end());
                std::lock_guard<std::mutex> lock(mKnownChannelsMutex);
                mKnownChannels = std::unordered_set<std::string>(
                    sorted.begin(), sorted.end());
                if (mPlugin.getConfig()->isDebug()) {
                    mPlugin.getSelf().getLogger().debug(
                        "ConfigSync: updated known channels ({})", mKnownChannels.size());
                }
            } catch (const nlohmann::json::exception& e) {
                mPlugin.getSelf().getLogger().warn(
                    "ConfigSync: malformed config JSON: {}", e.what());
            } catch (...) {
                mPlugin.getSelf().getLogger().warn(
                    "ConfigSync: failed to parse config JSON");
            }
        }
    );

    // Handle Title packets from backend — display to players in the channel.
    networkClient->registerHandler(PacketIds::TITLE,
        [this](std::unique_ptr<Packet> packet) {
            auto* title = static_cast<TitlePacket*>(packet.get());
            auto level = ll::service::getLevel();
            if (!level) {
                return;
            }
            level->forEachPlayer([&](Player& player) {
                std::string playerUuid = player.getUuid().asString();
                if (isPlayerInChannel(playerUuid, title->getChannelId())) {
                    // Send title timing packet
                    SetTitlePacket timingPacket;
                    timingPacket.mType = SetTitlePacketPayload::TitleType::Times;
                    timingPacket.mFadeInTime = title->getFadeIn();
                    timingPacket.mStayTime = title->getStay();
                    timingPacket.mFadeOutTime = title->getFadeOut();
                    player.sendNetworkPacket(timingPacket);

                    if (!title->getTitle().empty()) {
                        SetTitlePacket titlePacket;
                        titlePacket.mType = SetTitlePacketPayload::TitleType::Title;
                        titlePacket.mTitleText = convertColorCodes(title->getTitle());
                        player.sendNetworkPacket(titlePacket);
                    }
                    if (!title->getSubtitle().empty()) {
                        SetTitlePacket subtitlePacket;
                        subtitlePacket.mType = SetTitlePacketPayload::TitleType::Subtitle;
                        subtitlePacket.mTitleText = convertColorCodes(title->getSubtitle());
                        player.sendNetworkPacket(subtitlePacket);
                    }
                }
                return true;
            });
        }
    );

    // Handle Mention packets — highlight + title to the mentioned player.
    networkClient->registerHandler(PacketIds::MENTION,
        [this](std::unique_ptr<Packet> packet) {
            auto* mention = static_cast<MentionPacket*>(packet.get());
            auto level = ll::service::getLevel();
            if (!level) {
                return;
            }
            std::string mentionedUuidStr = mention->getMentionedId().toString();
            std::string locale = getPlayerLocale(mentionedUuidStr);
            auto& i18n = i18n::I18n::getInstance();
            std::string subtitle = i18n.get("chat.mention.subtitle", locale,
                                            {mention->getChannelId()});

            level->forEachPlayer([&](Player& player) {
                if (player.getUuid().asString() == mentionedUuidStr) {
                    // Title flash
                    SetTitlePacket timingPacket;
                    timingPacket.mType = SetTitlePacketPayload::TitleType::Times;
                    timingPacket.mFadeInTime = 10;
                    timingPacket.mStayTime = 40;
                    timingPacket.mFadeOutTime = 20;
                    player.sendNetworkPacket(timingPacket);

                    SetTitlePacket titlePacket;
                    titlePacket.mType = SetTitlePacketPayload::TitleType::Title;
                    titlePacket.mTitleText = "§e@§r" +
                        convertColorCodes(mention->getMentionerName());
                    player.sendNetworkPacket(titlePacket);

                    SetTitlePacket subtitlePacket;
                    subtitlePacket.mType = SetTitlePacketPayload::TitleType::Subtitle;
                    subtitlePacket.mTitleText = convertColorCodes(subtitle);
                    player.sendNetworkPacket(subtitlePacket);

                    return false;
                }
                return true;
            });

            if (mPlugin.getConfig()->isDebug()) {
                mPlugin.getSelf().getLogger().debug(
                    "Mention from {} to {} in {}",
                    mention->getMentionerName(), mentionedUuidStr,
                    mention->getChannelId());
            }
        }
    );

    // Handle ItemDisplay packets — forward [item] display to channel members.
    networkClient->registerHandler(PacketIds::ITEM_DISPLAY,
        [this](std::unique_ptr<Packet> packet) {
            auto* display = static_cast<ItemDisplayPacket*>(packet.get());
            std::string formatted = "[" + display->getSenderName() + ": " +
                                    display->getItemJson() + "]";
            formatted = convertColorCodes(formatted);
            broadcastToChannel(display->getChannelId(), formatted);
            if (mPlugin.getConfig()->isDebug()) {
                mPlugin.getSelf().getLogger().debug(
                    "ItemDisplay in {}: {}", display->getChannelId(),
                    display->getItemJson());
            }
        }
    );

    // Handle PrivateMessage packets — render the echo line to the sender and
    // the received line to the target. The backend delivers a completed
    // PrivateMessagePacket to BOTH the sender's client (echo) and the target's
    // client; here we dispatch per local player. Per-player directed; never
    // broadcasts to a channel.
    networkClient->registerHandler(PacketIds::PRIVATE_MESSAGE,
        [this](std::unique_ptr<Packet> packet) {
            auto* pm = static_cast<PrivateMessagePacket*>(packet.get());
            handlePrivateMessage(*pm);
            if (mPlugin.getConfig()->isDebug()) {
                mPlugin.getSelf().getLogger().debug(
                    "PrivateMessage from {} to {}: {}",
                    pm->getSenderName(), pm->getTargetName(), pm->getContent());
            }
        }
    );

    // Handle AdminActionResponse packets (0x0C) — route the async outcome of
    // /nc auth and /nc announce back to the originating player. The request
    // UUID is correlated via mPendingAdminActions (populated at send time in
    // CommandHandler::handleAuth/handleAnnounce). Mirrors the bukkit
    // handleAdminActionResponse path. FEATURE-002: the orphan 0x0A
    // Announcement handler that used to live here is gone — the backend
    // broadcasts announcements via AdminAction STATUS + type=ANNOUNCE extra
    // -> routeMessage (0x03), never via 0x0A.
    networkClient->registerHandler(PacketIds::ADMIN_ACTION_RESPONSE,
        [this](std::unique_ptr<Packet> packet) {
            auto* resp = static_cast<AdminActionResponsePacket*>(packet.get());
            handleAdminActionResponse(*resp);
            if (mPlugin.getConfig()->isDebug()) {
                mPlugin.getSelf().getLogger().debug(
                    "AdminActionResponse: action={} success={} code={}",
                    static_cast<int>(resp->getAction()),
                    resp->isSuccess(), resp->getErrorCode());
            }
        }
    );

    logger.debug("Packet handlers registered.");
}

void ChatInterceptor::unregisterHooks() {
    if (!mHooksRegistered) {
        return;
    }

    auto& logger = mPlugin.getSelf().getLogger();
    logger.info("Unregistering chat hooks...");

    auto& eventBus = ll::event::EventBus::getInstance();

    // Remove event listeners
    if (sChatListener) {
        eventBus.removeListener(sChatListener);
        sChatListener = nullptr;
    }
    if (sJoinListener) {
        eventBus.removeListener(sJoinListener);
        sJoinListener = nullptr;
    }
    if (sLeaveListener) {
        eventBus.removeListener(sLeaveListener);
        sLeaveListener = nullptr;
    }

    mHooksRegistered = false;
    logger.info("Chat hooks unregistered.");
}

void ChatInterceptor::onPlayerJoin(const std::string& playerName, const std::string& playerUuid,
                                   const std::string& localeCode) {
    auto& logger = mPlugin.getSelf().getLogger();

    // Update name mapping
    {
        std::lock_guard<std::mutex> lock(mNameMapMutex);
        mNameToUuid[playerName] = playerUuid;
    }

    // Initialize player state with default channel and the client locale read
    // from the Bedrock login chain (Player::getLocaleCode()). Fall back to the
    // hard default (zh_CN) only when the client sent no language code -- this
    // matches the i18n fallback chain (unknown locale -> zh_CN).
    auto& state = getPlayerState(playerUuid);
    if (!localeCode.empty()) {
        // Trim whitespace: a blank-but-non-empty code (e.g. "   ") should not
        // override the hard default.
        std::string trimmed = localeCode;
        auto first = trimmed.find_first_not_of(" \t\r\n");
        if (first != std::string::npos) {
            auto last = trimmed.find_last_not_of(" \t\r\n");
            trimmed = trimmed.substr(first, last - first + 1);
        }
        if (!trimmed.empty()) {
            state.locale = trimmed;
        } else {
            state.locale = "zh_CN";
        }
    } else if (state.locale.empty()) {
        state.locale = "zh_CN";
    }
    if (auto* config = mPlugin.getConfig()) {
        state.currentChannel = config->getDefaultChannel();
        state.joinedChannels.insert(state.currentChannel);
        addKnownChannel(state.currentChannel);
    }

    if (mPlugin.getConfig()->isDebug()) {
        logger.debug("Player {} ({}) joined, assigned to channel: {}",
            playerName, playerUuid, state.currentChannel);
    }
}

void ChatInterceptor::onPlayerLeave(const std::string& playerUuid) {
    // Remove player state
    removePlayerState(playerUuid);
    
    if (mPlugin.getConfig()->isDebug()) {
        mPlugin.getSelf().getLogger().debug("Player {} left, state cleaned up", playerUuid);
    }
}

bool ChatInterceptor::handlePlayerChat(const std::string& playerName, const std::string& playerUuid,
                                        const std::string& message) {
    auto& logger = mPlugin.getSelf().getLogger();
    auto& state = getPlayerState(playerUuid);

    // Check if player is muted
    if (state.muted) {
        std::string errorMsg = mPlugin.getConfig()->getErrorFormat();
        size_t pos = errorMsg.find("{message}");
        if (pos != std::string::npos) {
            errorMsg.replace(pos, 9, "You are muted and cannot send messages.");
        }
        displayMessage(playerName, convertColorCodes(errorMsg));
        return true; // Message handled (blocked)
    }

    // Get current channel
    std::string channelId = state.currentChannel;

    if (mPlugin.getConfig()->isDebug()) {
        logger.debug("Player {} sending message to channel {}: {}", 
            playerName, channelId, message);
    }

    // Send to backend for routing
    sendToBackend(playerName, playerUuid, channelId, message);

    return true; // Message handled
}

bool ChatInterceptor::handleTextPacket(const std::string& playerName, const std::string& message, int type) {
    // This method can be used to intercept TextPackets if needed
    // For now, we primarily use PlayerChatEvent for interception
    
    if (mPlugin.getConfig()->isDebug()) {
        mPlugin.getSelf().getLogger().debug(
            "TextPacket intercepted for {}: type={}, message={}", 
            playerName, type, message);
    }
    
    return false; // Don't cancel by default
}

void ChatInterceptor::displayMessage(const std::string& playerName, const std::string& formattedMessage) {
    auto level = ll::service::getLevel();
    if (!level) {
        return;
    }

    // Find player by name and send TextPacket
    level->forEachPlayer([&](Player& player) {
        if (player.getName() == playerName) {
            TextPacket packet = TextPacket::createRawMessage(formattedMessage);
            player.sendNetworkPacket(packet);
            return false; // Stop iteration
        }
        return true; // Continue iteration
    });
}

void ChatInterceptor::displayMessageByUuid(const std::string& playerUuid, const std::string& formattedMessage) {
    auto level = ll::service::getLevel();
    if (!level) {
        return;
    }

    level->forEachPlayer([&](Player& player) {
        if (player.getUuid().asString() == playerUuid) {
            TextPacket packet = TextPacket::createRawMessage(formattedMessage);
            player.sendNetworkPacket(packet);
            return false;
        }
        return true;
    });
}

void ChatInterceptor::handlePrivateMessage(const protocol::PrivateMessagePacket& packet) {
    // The backend delivers a completed PrivateMessagePacket to BOTH the
    // sender's client (echo) and the target's client. Render the "sent" line
    // to the local player matching senderId and the "received" line to the
    // local player matching targetId (when distinct). Per-player directed;
    // never broadcasts to a channel.
    static const protocol::UUID nilUuid{};
    auto& i18n = i18n::I18n::getInstance();

    const auto& senderId = packet.getSenderId();
    const auto& targetId = packet.getTargetId();

    // Echo line: the local player is the sender.
    if (!(senderId == nilUuid)) {
        std::string senderUuidStr = senderId.toString();
        std::string locale = getPlayerLocale(senderUuidStr);
        std::string message = i18n.get("chat.msg.sent", locale,
                                       {packet.getTargetName(), packet.getContent()});
        displayMessageByUuid(senderUuidStr, convertColorCodes(message));
    }

    // Received line: the local player is the (distinct) target.
    if (!(targetId == nilUuid) && !(targetId == senderId)) {
        std::string targetUuidStr = targetId.toString();
        std::string locale = getPlayerLocale(targetUuidStr);
        std::string message = i18n.get("chat.msg.received", locale,
                                       {packet.getSenderName(), packet.getContent()});
        displayMessageByUuid(targetUuidStr, convertColorCodes(message));
    }
}

void ChatInterceptor::broadcastToChannel(const std::string& channelId, const std::string& formattedMessage) {
    auto level = ll::service::getLevel();
    if (!level) {
        return;
    }

    // Broadcast to all players in the specified channel
    level->forEachPlayer([&](Player& player) {
        std::string playerUuid = player.getUuid().asString();
        
        // Check if player is in this channel
        if (isPlayerInChannel(playerUuid, channelId)) {
            TextPacket packet = TextPacket::createRawMessage(formattedMessage);
            player.sendNetworkPacket(packet);
        }
        return true; // Continue iteration
    });
}

void ChatInterceptor::broadcastToAll(const std::string& formattedMessage) {
    auto level = ll::service::getLevel();
    if (!level) {
        return;
    }

    level->forEachPlayer([&](Player& player) {
        TextPacket packet = TextPacket::createRawMessage(formattedMessage);
        player.sendNetworkPacket(packet);
        return true;
    });
}

void ChatInterceptor::sendTitle(const std::string& playerName, const std::string& title,
                                 const std::string& subtitle, int fadeIn, int stay, int fadeOut) {
    auto level = ll::service::getLevel();
    if (!level) {
        return;
    }

    level->forEachPlayer([&](Player& player) {
        if (player.getName() == playerName) {
            // Send title timing packet
            SetTitlePacket timingPacket;
            timingPacket.mType = SetTitlePacketPayload::TitleType::Times;
            timingPacket.mFadeInTime = fadeIn;
            timingPacket.mStayTime = stay;
            timingPacket.mFadeOutTime = fadeOut;
            player.sendNetworkPacket(timingPacket);

            // Send main title
            if (!title.empty()) {
                SetTitlePacket titlePacket;
                titlePacket.mType = SetTitlePacketPayload::TitleType::Title;
                titlePacket.mTitleText = convertColorCodes(title);
                player.sendNetworkPacket(titlePacket);
            }

            // Send subtitle
            if (!subtitle.empty()) {
                SetTitlePacket subtitlePacket;
                subtitlePacket.mType = SetTitlePacketPayload::TitleType::Subtitle;
                subtitlePacket.mTitleText = convertColorCodes(subtitle);
                player.sendNetworkPacket(subtitlePacket);
            }

            return false;
        }
        return true;
    });
}

PlayerChatState& ChatInterceptor::getPlayerState(const std::string& playerUuid) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    
    auto it = mPlayerStates.find(playerUuid);
    if (it == mPlayerStates.end()) {
        // Create default state
        PlayerChatState state;
        if (auto* config = mPlugin.getConfig()) {
            state.currentChannel = config->getDefaultChannel();
            state.joinedChannels.insert(state.currentChannel);
        }
        auto [newIt, _] = mPlayerStates.emplace(playerUuid, state);
        return newIt->second;
    }
    return it->second;
}

void ChatInterceptor::removePlayerState(const std::string& playerUuid) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    mPlayerStates.erase(playerUuid);
    
    // Also remove from name mapping
    std::lock_guard<std::mutex> nameLock(mNameMapMutex);
    for (auto it = mNameToUuid.begin(); it != mNameToUuid.end(); ) {
        if (it->second == playerUuid) {
            it = mNameToUuid.erase(it);
        } else {
            ++it;
        }
    }
}

void ChatInterceptor::setPlayerChannel(const std::string& playerUuid, const std::string& channelId) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    auto& state = mPlayerStates[playerUuid];
    state.currentChannel = channelId;
    state.joinedChannels.insert(channelId);
}

void ChatInterceptor::addPlayerToChannel(const std::string& playerUuid, const std::string& channelId) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    mPlayerStates[playerUuid].joinedChannels.insert(channelId);
}

void ChatInterceptor::removePlayerFromChannel(const std::string& playerUuid, const std::string& channelId) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    auto it = mPlayerStates.find(playerUuid);
    if (it != mPlayerStates.end()) {
        it->second.joinedChannels.erase(channelId);
        
        // If removed from current channel, switch to default
        if (it->second.currentChannel == channelId) {
            if (auto* config = mPlugin.getConfig()) {
                it->second.currentChannel = config->getDefaultChannel();
                it->second.joinedChannels.insert(it->second.currentChannel);
            }
        }
    }
}

bool ChatInterceptor::isPlayerInChannel(const std::string& playerUuid, const std::string& channelId) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    auto it = mPlayerStates.find(playerUuid);
    if (it != mPlayerStates.end()) {
        return it->second.joinedChannels.count(channelId) > 0 ||
               it->second.currentChannel == channelId;
    }
    return false;
}

ChatMode ChatInterceptor::toggleChatMode(const std::string& playerUuid) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    auto& state = mPlayerStates[playerUuid];
    
    if (state.chatMode == ChatMode::HYBRID) {
        state.chatMode = ChatMode::REPLACE;
    } else {
        state.chatMode = ChatMode::HYBRID;
    }
    
    return state.chatMode;
}

void ChatInterceptor::setPlayerMuted(const std::string& playerUuid, bool muted) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    mPlayerStates[playerUuid].muted = muted;
}

std::string ChatInterceptor::getPlayerLocale(const std::string& playerUuid) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    auto it = mPlayerStates.find(playerUuid);
    if (it != mPlayerStates.end()) {
        return it->second.locale;
    }
    return "zh_CN";
}

void ChatInterceptor::setPlayerLocale(const std::string& playerUuid, const std::string& locale) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    mPlayerStates[playerUuid].locale = locale;
}

std::vector<std::string> ChatInterceptor::getKnownChannels() const {
    std::lock_guard<std::mutex> lock(mKnownChannelsMutex);
    return {mKnownChannels.begin(), mKnownChannels.end()};
}

void ChatInterceptor::addKnownChannel(const std::string& channelId) {
    if (channelId.empty()) {
        return;
    }
    std::lock_guard<std::mutex> lock(mKnownChannelsMutex);
    mKnownChannels.insert(channelId);
}

void ChatInterceptor::whoChannel(const std::string& playerUuid, const std::string& channelId) {
    auto* networkClient = mPlugin.getNetworkClient();
    if (!networkClient || !networkClient->isConnected()) {
        return;
    }
    auto packet = std::make_unique<ChannelActionPacket>(ChannelAction::WHO, channelId);
    // Track the request so the response can be correlated if needed.
    std::string reqId = packet->getRequestId().toString();
    {
        std::lock_guard<std::mutex> lock(mPendingActionsMutex);
        mPendingActions.emplace(reqId, channelId);
    }
    networkClient->sendPacket(std::move(packet));
}

void ChatInterceptor::notifyKickTarget(const std::string& targetUuid, const std::string& operatorName,
                                       const std::string& channelId) {
    std::string locale = getPlayerLocale(targetUuid);
    auto& i18n = i18n::I18n::getInstance();
    std::string opName = operatorName.empty()
        ? i18n.get("notice.operator.fallback", locale) : operatorName;

    // Title flash
    sendTitleByUuid(targetUuid,
        i18n.get("chat.notice.kick_title", locale),
        i18n.get("chat.notice.kick_subtitle", locale, {opName, channelId}));

    // Action bar message
    std::string actionbar = i18n.get("chat.notice.kick_actionbar", locale, {opName, channelId});
    displayMessageByUuid(targetUuid, convertColorCodes(actionbar));
}

void ChatInterceptor::notifyMuteTarget(const std::string& targetUuid, const std::string& operatorName,
                                       const std::string& channelId, const std::string& duration) {
    std::string locale = getPlayerLocale(targetUuid);
    auto& i18n = i18n::I18n::getInstance();
    std::string opName = operatorName.empty()
        ? i18n.get("notice.operator.fallback", locale) : operatorName;
    std::string dur = duration.empty()
        ? i18n.get("notice.duration.unknown", locale) : duration;

    sendTitleByUuid(targetUuid,
        i18n.get("chat.notice.mute_title", locale),
        i18n.get("chat.notice.mute_subtitle", locale, {channelId, dur}));

    std::string actionbar = i18n.get("chat.notice.mute_actionbar", locale, {dur, channelId});
    displayMessageByUuid(targetUuid, convertColorCodes(actionbar));
}

void ChatInterceptor::sendTitleByUuid(const std::string& playerUuid, const std::string& title,
                                       const std::string& subtitle) {
    auto level = ll::service::getLevel();
    if (!level) {
        return;
    }
    level->forEachPlayer([&](Player& player) {
        if (player.getUuid().asString() == playerUuid) {
            SetTitlePacket timingPacket;
            timingPacket.mType = SetTitlePacketPayload::TitleType::Times;
            timingPacket.mFadeInTime = 10;
            timingPacket.mStayTime = 70;
            timingPacket.mFadeOutTime = 20;
            player.sendNetworkPacket(timingPacket);

            if (!title.empty()) {
                SetTitlePacket titlePacket;
                titlePacket.mType = SetTitlePacketPayload::TitleType::Title;
                titlePacket.mTitleText = convertColorCodes(title);
                player.sendNetworkPacket(titlePacket);
            }
            if (!subtitle.empty()) {
                SetTitlePacket subtitlePacket;
                subtitlePacket.mType = SetTitlePacketPayload::TitleType::Subtitle;
                subtitlePacket.mTitleText = convertColorCodes(subtitle);
                player.sendNetworkPacket(subtitlePacket);
            }
            return false;
        }
        return true;
    });
}

std::string ChatInterceptor::convertColorCodes(const std::string& message) {
    std::string result = message;
    
    // Convert & color codes to § (Bedrock section symbol)
    // Supported codes: 0-9, a-f, k-o, r
    for (size_t i = 0; i < result.length() - 1; ++i) {
        if (result[i] == '&') {
            char code = result[i + 1];
            // Check if it's a valid color/format code
            if ((code >= '0' && code <= '9') ||
                (code >= 'a' && code <= 'f') ||
                (code >= 'A' && code <= 'F') ||
                (code >= 'k' && code <= 'o') ||
                (code >= 'K' && code <= 'O') ||
                code == 'r' || code == 'R') {
                // Replace the 1-byte '&' with the 2-byte UTF-8 sequence for § (0xC2 0xA7)
                result.replace(i, 1, "\xC2\xA7", 2);
                ++i; // Skip the inserted 0xA7 byte to avoid re-processing it
            }
        }
    }
    
    // Also handle hex color codes: &#RRGGBB -> §x§R§R§G§G§B§B
    std::regex hexPattern("&#([0-9A-Fa-f]{6})");
    std::string hexResult;
    std::sregex_iterator it(result.begin(), result.end(), hexPattern);
    std::sregex_iterator end;
    size_t lastPos = 0;
    
    while (it != end) {
        hexResult += result.substr(lastPos, it->position() - lastPos);
        std::string hex = (*it)[1].str();
        hexResult += "§x";
        for (char c : hex) {
            hexResult += "§";
            hexResult += static_cast<char>(std::tolower(c));
        }
        lastPos = it->position() + it->length();
        ++it;
    }
    hexResult += result.substr(lastPos);
    
    return hexResult.empty() ? result : hexResult;
}

std::string ChatInterceptor::stripColorCodes(const std::string& message) {
    std::string result;
    result.reserve(message.length());
    
    for (size_t i = 0; i < message.length(); ++i) {
        // Check for § symbol (UTF-8: 0xC2 0xA7)
        if (i + 1 < message.length() && 
            static_cast<unsigned char>(message[i]) == 0xC2 && 
            static_cast<unsigned char>(message[i + 1]) == 0xA7) {
            // Skip the § and the following code character
            i += 2; // Skip §
            if (i < message.length()) {
                ++i; // Skip the code character
            }
            --i; // Adjust for loop increment
        }
        // Check for & color codes
        else if (message[i] == '&' && i + 1 < message.length()) {
            char code = message[i + 1];
            if ((code >= '0' && code <= '9') ||
                (code >= 'a' && code <= 'f') ||
                (code >= 'A' && code <= 'F') ||
                (code >= 'k' && code <= 'o') ||
                (code >= 'K' && code <= 'O') ||
                code == 'r' || code == 'R') {
                ++i; // Skip the code character
                continue;
            }
            result += message[i];
        }
        else {
            result += message[i];
        }
    }
    
    return result;
}

std::string ChatInterceptor::formatMessage(const std::string& channelId, const std::string& playerName,
                                            const std::string& message) {
    auto* config = mPlugin.getConfig();
    if (!config) {
        return message;
    }

    std::string format = config->getChannelFormat(channelId);
    
    // Replace placeholders
    std::string result = format;
    
    // {player} -> player name
    size_t pos = 0;
    while ((pos = result.find("{player}", pos)) != std::string::npos) {
        result.replace(pos, 8, playerName);
        pos += playerName.length();
    }
    
    // {message} -> message content
    pos = 0;
    while ((pos = result.find("{message}", pos)) != std::string::npos) {
        result.replace(pos, 9, message);
        pos += message.length();
    }
    
    // {channel_name} -> channel ID (could be enhanced with display names)
    pos = 0;
    while ((pos = result.find("{channel_name}", pos)) != std::string::npos) {
        result.replace(pos, 14, channelId);
        pos += channelId.length();
    }
    
    // {channel} -> channel ID
    pos = 0;
    while ((pos = result.find("{channel}", pos)) != std::string::npos) {
        result.replace(pos, 9, channelId);
        pos += channelId.length();
    }

    return result;
}

void ChatInterceptor::sendToBackend(const std::string& playerName, const std::string& playerUuid,
                                     const std::string& channelId, const std::string& message) {
    auto* networkClient = mPlugin.getNetworkClient();
    if (!networkClient || !networkClient->isConnected()) {
        auto& logger = mPlugin.getSelf().getLogger();
        logger.warn("Cannot send message: not connected to backend");
        
        // In offline mode, broadcast locally
        std::string formatted = formatMessage(channelId, playerName, message);
        formatted = convertColorCodes(formatted);
        broadcastToChannel(channelId, formatted);
        return;
    }

    // Parse Minecraft UUID string (format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)
    UUID senderId;
    std::string hexUuid = playerUuid;
    hexUuid.erase(std::remove(hexUuid.begin(), hexUuid.end(), '-'), hexUuid.end());
    if (hexUuid.size() == 32 &&
        hexUuid.find_first_not_of("0123456789abcdefABCDEF") == std::string::npos) {
        senderId.mostSigBits  = std::stoull(hexUuid.substr(0, 16),  nullptr, 16);
        senderId.leastSigBits = std::stoull(hexUuid.substr(16, 16), nullptr, 16);
    } else {
        std::hash<std::string> hasher;
        senderId.mostSigBits  = hasher(playerUuid);
        senderId.leastSigBits = hasher(playerUuid + "salt");
    }

    auto packet = std::make_unique<ChatMessagePacket>(
        senderId,
        playerName,
        mPlugin.getConfig()->getUsername(), // Client ID
        channelId,
        message
    );

    networkClient->sendPacket(std::move(packet));
}

void ChatInterceptor::registerPendingAdminAction(const std::string& requestId, const std::string& playerUuid) {
    if (requestId.empty()) {
        return;
    }
    std::lock_guard<std::mutex> lock(mPendingAdminActionsMutex);
    mPendingAdminActions.emplace(requestId, playerUuid);
}

void ChatInterceptor::handleAdminActionResponse(const protocol::AdminActionResponsePacket& packet) {
    // Correlate the async response back to the originating player by the request
    // UUID generated at send time in CommandHandler::handleAuth/handleAnnounce
    // and registered via registerPendingAdminAction. Mirrors the bukkit
    // NetworkClient.pendingAdminRequests.remove(response.getRequestId()) path.
    std::string playerUuid;
    bool isConsole = false;
    {
        std::string reqId = packet.getRequestId().toString();
        std::lock_guard<std::mutex> lock(mPendingAdminActionsMutex);
        auto it = mPendingAdminActions.find(reqId);
        if (it != mPendingAdminActions.end()) {
            playerUuid = it->second;
            mPendingAdminActions.erase(it);
        }
    }

    // Default-constructed UUID (both bits 0) is the console sentinel used by
    // /nc announce when originated from the server console / RCON. The tracking
    // value stored by handleAnnounce is the sentinel's canonical hyphenated
    // string form, so compare strings rather than re-parsing.
    static const std::string consoleSentinelStr = protocol::UUID{}.toString();
    if (playerUuid == consoleSentinelStr) {
        isConsole = true;
    }

    auto& i18n = i18n::I18n::getInstance();
    std::string locale = playerUuid.empty() ? "zh_CN" : getPlayerLocale(playerUuid);

    // Console/RCON origin has no in-game player to message; route to the logger
    // (parity with the bukkit console branch in handleAdminActionResponse).
    auto routeOutcome = [&](const std::string& message) {
        if (isConsole || playerUuid.empty()) {
            mPlugin.getSelf().getLogger().info("AdminAction: {}", stripColorCodes(message));
        } else {
            displayMessageByUuid(playerUuid, convertColorCodes(message));
        }
    };

    if (packet.isSuccess()) {
        routeOutcome(i18n.get("chat.action.success", locale));
        return;
    }

    // FEATURE-002: the backend NC-403 gate in AdminActionHandler.handleStatus
    // is the sole authority on super-admin session state; the client never
    // tracks it locally. A failure with action==STATUS && errorCode=="NC-403"
    // means the player needs to /nc auth first.
    bool needsSuperAdmin =
        (packet.getAction() == protocol::AdminAction::STATUS) &&
        (packet.getErrorCode() == "NC-403");

    if (needsSuperAdmin) {
        routeOutcome(i18n.get("chat.error.super_admin_required", locale));
        routeOutcome(i18n.get("chat.error.super_admin_required_suggestion", locale));
    } else {
        std::string message = i18n.get("chat.action.failed", locale);
        if (!packet.getMessage().empty()) {
            message += ": " + packet.getMessage();
        }
        routeOutcome(message);
    }
}

} // namespace novachat
