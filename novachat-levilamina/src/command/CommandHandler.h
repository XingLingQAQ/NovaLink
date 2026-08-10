#pragma once

#include <string>
#include <vector>
#include <functional>
#include <unordered_map>

namespace novachat {

class NovaChatPlugin;

/**
 * Command handler for NovaChat commands.
 * Registers and handles /nc commands.
 *
 * Subcommands (parity with the 7 Java server-side platforms):
 *   help, join, leave, list, who, toggle, reload
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
    void handleHelp(const std::string& playerName, const std::vector<std::string>& args);
    void handleJoin(const std::string& playerName, const std::string& playerUuid,
                    const std::vector<std::string>& args);
    void handleLeave(const std::string& playerName, const std::string& playerUuid,
                     const std::vector<std::string>& args);
    void handleList(const std::string& playerName, const std::vector<std::string>& args);
    void handleWho(const std::string& playerName, const std::string& playerUuid,
                   const std::vector<std::string>& args);
    void handleToggle(const std::string& playerName, const std::string& playerUuid,
                      const std::vector<std::string>& args);
    void handleReload(const std::string& playerName, const std::vector<std::string>& args);

    // Send a localized message to a player by name.
    void sendLocalized(const std::string& playerName, const std::string& playerUuid,
                       const std::string& key, const std::vector<std::string>& args = {});
    // Send a raw message to a player.
    void sendMessage(const std::string& playerName, const std::string& message);

    NovaChatPlugin& mPlugin;
    bool mCommandsRegistered = false;
};

} // namespace novachat
