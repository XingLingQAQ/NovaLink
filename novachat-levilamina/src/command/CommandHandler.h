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

private:
    // Command handlers
    void handleHelp(const std::string& playerName, const std::vector<std::string>& args);
    void handleJoin(const std::string& playerName, const std::string& playerUuid, 
                    const std::vector<std::string>& args);
    void handleLeave(const std::string& playerName, const std::string& playerUuid,
                     const std::vector<std::string>& args);
    void handleToggle(const std::string& playerName, const std::string& playerUuid,
                      const std::vector<std::string>& args);
    void handleDebug(const std::string& playerName, const std::vector<std::string>& args);
    void handleReload(const std::string& playerName, const std::vector<std::string>& args);

    // Send message to player
    void sendMessage(const std::string& playerName, const std::string& message);

    NovaChatPlugin& mPlugin;
    bool mCommandsRegistered = false;
};

} // namespace novachat
