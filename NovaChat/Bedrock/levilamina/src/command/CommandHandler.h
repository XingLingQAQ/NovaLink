#pragma once

#include <string>
#include <vector>
#include <functional>
#include <unordered_map>

#include "ModerationCommands.h"

namespace novachat {

class NovaChatPlugin;

/**
 * Command handler for NovaChat commands.
 * Registers and handles /nc commands.
 *
 * Subcommands (parity with the Java server-side platforms):
 *   help, join, leave, list, who, toggle, reload, auth (hidden), announce,
 *   mute, kick, ban, unban (admin)
 */
class CommandHandler {
public:
    explicit CommandHandler(NovaChatPlugin& plugin);
    ~CommandHandler();

    /**
     * Register commands with LeviLamina.
     */
    void registerCommands();

    /**
     * Unregister commands.
     */
    void unregisterCommands();

    /**
     * Tab-complete channel names for a partial token.
     */
    std::vector<std::string> completeChannel(const std::string& partial) const;

private:
    // Command handlers
    /**
     * @param isAdmin When true, the reload line is appended (mirrors the
     *                Java/Python platforms' `novachat.admin`-gated help line).
     */
    void handleHelp(const std::string& playerName, const std::string& playerUuid,
                    const std::vector<std::string>& args, bool isAdmin = false);
    void handleJoin(const std::string& playerName, const std::string& playerUuid,
                    const std::vector<std::string>& args);
    void handleLeave(const std::string& playerName, const std::string& playerUuid,
                     const std::vector<std::string>& args);
    void handleList(const std::string& playerName, const std::string& playerUuid,
                    const std::vector<std::string>& args);
    void handleWho(const std::string& playerName, const std::string& playerUuid,
                   const std::vector<std::string>& args);
    void handleToggle(const std::string& playerName, const std::string& playerUuid,
                      const std::vector<std::string>& args);
    void handleReload(const std::string& playerName, const std::string& playerUuid,
                      const std::vector<std::string>& args);
    // FEATURE-002: super-admin authentication. Player-only. Sends an
    // AdminActionPacket(AUTH) with SHA-256(password) hex; the backend
    // hasSuperAdminSession gate is the sole authority, the client never
    // tracks the session locally. Mirrors bukkit AuthCommand.
    void handleAuth(const std::string& playerName, const std::string& playerUuid,
                    const std::vector<std::string>& args);
    // FEATURE-002: broadcast announcement. Sends an AdminActionPacket(STATUS)
    // with type=ANNOUNCE extra; console/RCON uses the all-zeros sentinel UUID.
    // Backend handleStatus -> handleAnnounce -> routeMessage. Mirrors bukkit
    // AnnounceCommand.
    void handleAnnounce(const std::string& playerName, const std::string& playerUuid,
                        const std::vector<std::string>& args);

    // ==================== Moderation (mute / kick / ban / unban) ====================
    // All four are admin-gated at the overload entry point (the same
    // GameDirectors gate /nc reload uses — LeviLamina exposes no per-overload
    // permission chain) and the builders in ModerationCommands re-check it so
    // the pure layer can be tested without the SDK.
    //
    // Packet shape is ChannelActionPacket with the extras the backend reads:
    // operatorId / console / operatorName / targetId / targetName / duration.
    // Console sends the all-zeros sentinel UUID plus console=true; it bypasses
    // the backend's moderation permission check entirely.
    void handleMute(const std::string& senderName, const std::string& senderUuid,
                    bool isConsole, int permissionLevel, const std::vector<std::string>& args);
    void handleKick(const std::string& senderName, const std::string& senderUuid,
                    bool isConsole, int permissionLevel, const std::vector<std::string>& args);
    void handleBan(const std::string& senderName, const std::string& senderUuid,
                   bool isConsole, int permissionLevel, const std::vector<std::string>& args);
    void handleUnban(const std::string& senderName, const std::string& senderUuid,
                     bool isConsole, int permissionLevel, const std::vector<std::string>& args);

    // FEATURE-002 parity with bukkit TitleCommand: AdminActionPacket(STATUS)
    // with type=TITLE. The backend gates STATUS on hasSuperAdminSession, so
    // this only succeeds after the sender has run /nc auth. Console cannot
    // auth (player-only), so console titles are always refused with NC-403.
    void handleTitle(const std::string& playerName, const std::string& playerUuid,
                     const std::vector<std::string>& args);

    /**
     * Sends one already-built moderation request and reports the outcome.
     *
     * Shared by all four moderation handlers: validates connectivity, sends
     * the packet and prints the progress line (never a success line — the
     * backend's own permission/validation result is authoritative and arrives
     * later as a ChannelActionResponse).
     *
     * @param request    the builder result (rejection or packet)
     * @param senderName who to report to (player name, or "Console")
     * @param senderUuid sender UUID for locale lookup ("" for console)
     * @param progressKey i18n key for the progress line
     * @param progressArgs arguments for the progress line
     */
    void dispatchModeration(moderation::ModerationRequest request,
                            const std::string& senderName, const std::string& senderUuid,
                            const std::string& progressKey,
                            const std::vector<std::string>& progressArgs);

    // Send a localized message to a player by name.
    void sendLocalized(const std::string& playerName, const std::string& playerUuid,
                       const std::string& key, const std::vector<std::string>& args = {});
    // Send a raw message to a player.
    void sendMessage(const std::string& playerName, const std::string& message);

    NovaChatPlugin& mPlugin;
    bool mCommandsRegistered = false;
};

} // namespace novachat
