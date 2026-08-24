#pragma once

#include <string>
#include <unordered_map>
#include <unordered_set>
#include <memory>
#include <mutex>
#include <functional>
#include <vector>

namespace novachat {

class NovaChatPlugin;

namespace protocol {
    struct UUID;
    class ChannelActionResponsePacket;
    class PrivateMessagePacket;
    class AdminActionResponsePacket;
}

/**
 * Chat mode for players
 */
enum class ChatMode {
    HYBRID,   // Both vanilla and NovaChat
    REPLACE   // NovaChat only
};

/**
 * Player chat state
 */
struct PlayerChatState {
    std::string currentChannel;
    ChatMode chatMode = ChatMode::HYBRID;
    bool muted = false;
    std::string locale = "zh_CN";
    std::unordered_set<std::string> joinedChannels;
};

/**
 * Chat interceptor for LeviLamina.
 * 
 * Hooks into TextPacket handling to intercept player chat messages
 * and route them through NovaChat channels.
 * 
 * Features:
 * - TextPacket interception for Bedrock chat
 * - Message routing through NovaChat channels
 * - Bedrock-specific color code support (§ codes)
 * - Player state management
 */
class ChatInterceptor {
public:
    explicit ChatInterceptor(NovaChatPlugin& plugin);
    ~ChatInterceptor();

    // Non-copyable
    ChatInterceptor(const ChatInterceptor&) = delete;
    ChatInterceptor& operator=(const ChatInterceptor&) = delete;

    /**
     * Register chat hooks with LeviLamina.
     * Hooks into PlayerChatEvent and TextPacket handling.
     */
    void registerHooks();

    /**
     * Unregister chat hooks.
     */
    void unregisterHooks();

    /**
     * Handle incoming chat message from a player.
     * @param playerName the player's name
     * @param playerUuid the player's UUID string
     * @param message the chat message
     * @return true if the message was handled (should cancel vanilla)
     */
    bool handlePlayerChat(const std::string& playerName, const std::string& playerUuid,
                          const std::string& message);

    /**
     * Handle incoming TextPacket from network.
     * Used for intercepting server-to-client chat messages.
     * @param playerName target player name
     * @param message the message content
     * @param type the TextPacket type
     * @return true if packet should be cancelled
     */
    bool handleTextPacket(const std::string& playerName, const std::string& message, int type);

    /**
     * Display a chat message to a player using TextPacket.
     * @param playerName the target player's name
     * @param formattedMessage the formatted message to display
     */
    void displayMessage(const std::string& playerName, const std::string& formattedMessage);

    /**
     * Display a message to a player by UUID.
     * @param playerUuid the target player's UUID
     * @param formattedMessage the formatted message to display
     */
    void displayMessageByUuid(const std::string& playerUuid, const std::string& formattedMessage);

    /**
     * Broadcast a message to all players in a channel.
     * @param channelId the channel ID
     * @param formattedMessage the formatted message
     */
    void broadcastToChannel(const std::string& channelId, const std::string& formattedMessage);

    /**
     * Broadcast a message to all online players.
     * @param formattedMessage the formatted message
     */
    void broadcastToAll(const std::string& formattedMessage);

    /**
     * Send a Title message to a player.
     * @param playerName the target player's name
     * @param title the main title text
     * @param subtitle the subtitle text (optional)
     * @param fadeIn fade in duration in ticks
     * @param stay stay duration in ticks
     * @param fadeOut fade out duration in ticks
     */
    void sendTitle(const std::string& playerName, const std::string& title,
                   const std::string& subtitle = "", int fadeIn = 10, int stay = 70, int fadeOut = 20);

    /**
     * Get or create player state.
     * @param playerUuid the player's UUID
     * @return reference to the player's chat state
     */
    PlayerChatState& getPlayerState(const std::string& playerUuid);

    /**
     * Remove player state when player disconnects.
     * @param playerUuid the player's UUID
     */
    void removePlayerState(const std::string& playerUuid);

    /**
     * Set player's current channel.
     * @param playerUuid the player's UUID
     * @param channelId the channel ID
     */
    void setPlayerChannel(const std::string& playerUuid, const std::string& channelId);

    /**
     * Add player to a channel.
     * @param playerUuid the player's UUID
     * @param channelId the channel ID
     */
    void addPlayerToChannel(const std::string& playerUuid, const std::string& channelId);

    /**
     * Remove player from a channel.
     * @param playerUuid the player's UUID
     * @param channelId the channel ID
     */
    void removePlayerFromChannel(const std::string& playerUuid, const std::string& channelId);

    /**
     * Check if player is in a channel.
     * @param playerUuid the player's UUID
     * @param channelId the channel ID
     * @return true if player is in the channel
     */
    bool isPlayerInChannel(const std::string& playerUuid, const std::string& channelId);

    /**
     * Toggle player's chat mode.
     * @param playerUuid the player's UUID
     * @return the new chat mode
     */
    ChatMode toggleChatMode(const std::string& playerUuid);

    /**
     * Set player's mute status.
     * @param playerUuid the player's UUID
     * @param muted true to mute the player
     */
    void setPlayerMuted(const std::string& playerUuid, bool muted);

    /**
     * Get a player's locale (default zh_CN).
     */
    std::string getPlayerLocale(const std::string& playerUuid);

    /**
     * Set a player's locale.
     */
    void setPlayerLocale(const std::string& playerUuid, const std::string& locale);

    /**
     * Get the set of known channels (populated from ConfigSync).
     */
    std::vector<std::string> getKnownChannels() const;

    /**
     * Add a channel to the known channels set.
     */
    void addKnownChannel(const std::string& channelId);

    /**
     * Track an outgoing admin-action request (e.g. /nc auth, /nc announce) so
     * the ADMIN_ACTION_RESPONSE handler can route the outcome back to the
     * originating player. Mirrors the bukkit NetworkClient.pendingAdminRequests
     * map: request UUID -> player UUID (or the all-zeros console sentinel
     * UUID when the request originated from the console).
     *
     * The client never tracks super-admin session state locally (the backend
     * NC-403 gate in AdminActionHandler.handleStatus is the sole authority);
     * this map only correlates the async response with the sender for UX.
     */
    void registerPendingAdminAction(const std::string& requestId, const std::string& playerUuid);

    /**
     * Send a WHO channel action for a player.
     */
    void whoChannel(const std::string& playerUuid, const std::string& channelId);

    /**
     * Notify a target player that they were kicked from a channel.
     */
    void notifyKickTarget(const std::string& targetUuid, const std::string& operatorName,
                          const std::string& channelId);

    /**
     * Notify a target player that they were muted in a channel.
     */
    void notifyMuteTarget(const std::string& targetUuid, const std::string& operatorName,
                          const std::string& channelId, const std::string& duration);

    /**
     * Check if replace vanilla mode is enabled globally.
     * @return true if vanilla chat should be replaced
     */
    [[nodiscard]] bool isReplaceVanilla() const { return mReplaceVanilla; }

    /**
     * Set replace vanilla mode.
     * @param replace true to replace vanilla chat
     */
    void setReplaceVanilla(bool replace) { mReplaceVanilla = replace; }

    /**
     * Convert legacy color codes (&) to Bedrock section codes (§).
     * @param message the message with legacy codes
     * @return message with Bedrock color codes
     */
    static std::string convertColorCodes(const std::string& message);

    /**
     * Strip all color codes from a message.
     * @param message the message with color codes
     * @return message without color codes
     */
    static std::string stripColorCodes(const std::string& message);

private:
    // Register packet handlers for incoming messages from backend
    void registerPacketHandlers();

    // Format message using channel format
    std::string formatMessage(const std::string& channelId, const std::string& playerName,
                              const std::string& message);

    // Send message to backend
    void sendToBackend(const std::string& playerName, const std::string& playerUuid,
                       const std::string& channelId, const std::string& message);

    // Handle player join event.
    // localeCode is the player's client locale read from the Bedrock login
    // chain via Player::getLocaleCode() (e.g. "zh_CN", "en_US", "ja_JP").
    // An empty string means the locale could not be read; onPlayerJoin falls
    // back to the hard default (zh_CN) in that case.
    void onPlayerJoin(const std::string& playerName, const std::string& playerUuid,
                      const std::string& localeCode = "");

    // Handle player leave event
    void onPlayerLeave(const std::string& playerUuid);

    // Send a title (with timing) to a single player by UUID.
    void sendTitleByUuid(const std::string& playerUuid, const std::string& title,
                         const std::string& subtitle);

    // Handle incoming private message (0x14) from backend.
    // The backend delivers a completed PrivateMessagePacket to BOTH the
    // sender's client (echo) and the target's client; this renders the
    // "sent" line to the local player matching senderId and the "received"
    // line to the local player matching targetId (when distinct). Per-player
    // directed; never broadcasts to a channel.
    void handlePrivateMessage(const protocol::PrivateMessagePacket& packet);

    // Handle incoming AdminActionResponsePacket (0x0C) from backend. Pops the
    // originating player UUID from mPendingAdminActions (correlated by request
    // UUID at send time). Mirrors the bukkit handleAdminActionResponse +
    // isSuperAdminRequired path: on success -> chat.action.success; on failure
    // with action==STATUS && errorCode=="NC-403" -> chat.error.super_admin_required
    // + _suggestion (the backend hasSuperAdminSession gate); otherwise the
    // generic i18n error. The client never tracks super-admin session locally.
    void handleAdminActionResponse(const protocol::AdminActionResponsePacket& packet);

    NovaChatPlugin& mPlugin;
    bool mReplaceVanilla = false;
    bool mHooksRegistered = false;

    // Player states
    std::unordered_map<std::string, PlayerChatState> mPlayerStates;
    mutable std::mutex mStateMutex;

    // Player name to UUID mapping for quick lookups
    std::unordered_map<std::string, std::string> mNameToUuid;
    mutable std::mutex mNameMapMutex;

    // Known channels (populated from ConfigSync / channel action responses)
    std::unordered_set<std::string> mKnownChannels;
    mutable std::mutex mKnownChannelsMutex;

    // Pending channel action request tracking: request UUID -> channel id
    std::unordered_map<std::string, std::string> mPendingActions;
    mutable std::mutex mPendingActionsMutex;

    // Pending admin action request tracking: request UUID -> player UUID (or
    // the all-zeros console sentinel UUID for console-originated /nc announce).
    // Populated by registerPendingAdminAction at send time, consumed by
    // handleAdminActionResponse to route the async outcome back to the sender.
    std::unordered_map<std::string, std::string> mPendingAdminActions;
    mutable std::mutex mPendingAdminActionsMutex;
};

} // namespace novachat
