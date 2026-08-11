#include "NovaChatPlugin.h"
#include "config/NovaChatConfig.h"
#include "network/NetworkClient.h"
#include "chat/ChatInterceptor.h"
#include "command/CommandHandler.h"

#include <ll/api/io/Logger.h>
#include <ll/api/mod/NativeMod.h>
#include <ll/api/mod/RegisterHelper.h>
#include <ll/api/event/EventBus.h>
#include <ll/api/event/Listener.h>
#include <ll/api/event/server/ServerStartedEvent.h>
#include <ll/api/event/world/ServerLevelTickEvent.h>

namespace novachat {

static ll::event::ListenerPtr sServerStartedListener;
// levilamina 26.20.7 removed the ll/api/schedule/* API (ServerTimeScheduler +
// RepeatTask no longer exist). We drive per-tick packet processing by
// listening to ServerLevelTickEvent instead, which fires once per server tick
// (~50ms) -- the same cadence the old RepeatTask(50ms) used.
static ll::event::ListenerPtr sTickListener;

NovaChatPlugin::~NovaChatPlugin() = default;

// 构造函数放 .cpp（out-of-line）：头文件里内联构造会让包含 NovaChatPlugin.h 但
// 只前向声明 CommandHandler 的翻译单元（如 ChatInterceptor.cpp）在生成构造的
// 异常清理路径时实例化 unique_ptr<CommandHandler> 的析构，触发 "can't delete an
// incomplete type" (C2338)。这里 #include 了 CommandHandler.h，类型完整可见。
NovaChatPlugin::NovaChatPlugin() : mSelf(*ll::mod::NativeMod::current()) {}

NovaChatPlugin& NovaChatPlugin::getInstance() {
    static NovaChatPlugin instance;
    return instance;
}

bool NovaChatPlugin::load() {
    auto& logger = mSelf.getLogger();
    logger.info("Loading NovaChat-LeviLamina...");

    // Load configuration
    mConfig = std::make_unique<NovaChatConfig>(mSelf.getDataDir());
    if (!mConfig->load()) {
        logger.error("Failed to load configuration!");
        return false;
    }

    logger.info("NovaChat-LeviLamina loaded successfully.");
    return true;
}

bool NovaChatPlugin::enable() {
    auto& logger = mSelf.getLogger();
    logger.info("Enabling NovaChat-LeviLamina...");

    // Initialize network client
    mNetworkClient = std::make_unique<network::NetworkClient>(
        mConfig->getBackendHost(),
        mConfig->getBackendPort(),
        mConfig->getUsername(),
        mConfig->getPassword(),
        mConfig->getServerVersion()
    );

    // Initialize chat interceptor
    mChatInterceptor = std::make_unique<ChatInterceptor>(*this);

    // Initialize command handler
    mCommandHandler = std::make_unique<CommandHandler>(*this);

    // Connect to backend
    if (!mNetworkClient->connect()) {
        logger.warn("Failed to connect to NovaLink backend. Will retry in background.");
    }

    // Register chat hooks
    mChatInterceptor->registerHooks();

    // Register commands
    mCommandHandler->registerCommands();

    // Process incoming packets on the server thread every tick (~50ms).
    // This replaces the old ll::schedule::ServerTimeScheduler + RepeatTask
    // (removed in levilamina 26.20.7) with a ServerLevelTickEvent listener.
    auto& bus = ll::event::EventBus::getInstance();
    sTickListener = bus.emplaceListener<ll::event::ServerLevelTickEvent>(
        [this](ll::event::ServerLevelTickEvent&) {
            if (mNetworkClient && mEnabled) {
                mNetworkClient->processIncomingPackets();
            }
        }
    );

    mEnabled = true;
    logger.info("NovaChat-LeviLamina enabled successfully.");
    return true;
}

bool NovaChatPlugin::disable() {
    auto& logger = mSelf.getLogger();
    logger.info("Disabling NovaChat-LeviLamina...");

    mEnabled = false;

    // Remove tick listener (replaces old sScheduler.clear())
    if (sTickListener) {
        ll::event::EventBus::getInstance().removeListener<ll::event::ServerLevelTickEvent>(sTickListener);
        sTickListener = nullptr;
    }

    // Remove server started listener
    if (sServerStartedListener) {
        ll::event::EventBus::getInstance().removeListener(sServerStartedListener);
        sServerStartedListener = nullptr;
    }

    // Unregister commands
    if (mCommandHandler) {
        mCommandHandler->unregisterCommands();
    }

    // Unregister hooks
    if (mChatInterceptor) {
        mChatInterceptor->unregisterHooks();
    }

    // Disconnect from backend
    if (mNetworkClient) {
        mNetworkClient->disconnect();
    }

    logger.info("NovaChat-LeviLamina disabled.");
    return true;
}

} // namespace novachat

// Mod registration (LL_REGISTER_PLUGIN -> LL_REGISTER_MOD for levilamina 26.20.7).
// Binder must be a T& (matches bindToMod(T&, ll::mod::Mod&) + (BINDER).load()),
// so we pass getInstance() which returns NovaChatPlugin& -- not the old
// std::unique_ptr gInstance which had no .load() and did not convert to T&.
LL_REGISTER_MOD(novachat::NovaChatPlugin, novachat::NovaChatPlugin::getInstance());
