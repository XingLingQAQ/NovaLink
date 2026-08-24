#pragma once

#include <ll/api/mod/NativeMod.h>
#include <ll/api/mod/RegisterHelper.h>
#include <memory>
#include <string>

namespace novachat {

// Forward declarations
class ChatInterceptor;
class NovaChatConfig;
class CommandHandler;

namespace network {
    class NetworkClient;
}
using NetworkClient = network::NetworkClient;

/**
 * NovaChat-LeviLamina Mod
 *
 * Main mod class for the LeviLamina (BDS) implementation of NovaChat.
 * Handles mod lifecycle, configuration, and component management.
 *
 * API note: levilamina 26.20.7 renamed the plugin framework from "Plugin" to
 * "Mod" -- ll::plugin::NativePlugin became ll::mod::NativeMod, the
 * ll/api/plugin/* headers moved to ll/api/mod/*, and LL_REGISTER_PLUGIN became
 * LL_REGISTER_MOD. The lifecycle methods (load/enable/disable returning bool)
 * are unchanged -- LL_REGISTER_MOD's concepts (Loadable/Enableable/Disableable)
 * require exactly these.
 *
 * Construction follows the official mod-template pattern: a no-arg constructor
 * that grabs its own NativeMod handle via ll::mod::NativeMod::current(), and a
 * getInstance() returning a reference to a function-local static instance.
 * LL_REGISTER_MOD(CLAZZ, getInstance()) then expands to bindToMod(instance, self)
 * + instance.load(), which requires the binder to be a T& (not a unique_ptr).
 */
class NovaChatPlugin {
public:
    NovaChatPlugin();
    ~NovaChatPlugin();

    // Mod lifecycle
    bool load();
    bool enable();
    bool disable();
    bool reloadConfiguration();

    // Accessors
    [[nodiscard]] ll::mod::NativeMod& getSelf() const { return mSelf; }
    [[nodiscard]] NetworkClient* getNetworkClient() const { return mNetworkClient.get(); }
    [[nodiscard]] ChatInterceptor* getChatInterceptor() const { return mChatInterceptor.get(); }
    [[nodiscard]] NovaChatConfig* getConfig() const { return mConfig.get(); }
    [[nodiscard]] CommandHandler* getCommandHandler() const { return mCommandHandler.get(); }

    // Singleton access (function-local static -- matches official mod template)
    static NovaChatPlugin& getInstance();

private:
    ll::mod::NativeMod& mSelf;

    // Components
    std::unique_ptr<NovaChatConfig> mConfig;
    std::unique_ptr<NetworkClient> mNetworkClient;
    std::unique_ptr<ChatInterceptor> mChatInterceptor;
    std::unique_ptr<CommandHandler> mCommandHandler;

    // State
    bool mEnabled = false;
};

} // namespace novachat
