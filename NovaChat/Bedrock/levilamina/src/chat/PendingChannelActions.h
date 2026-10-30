#pragma once

#include <chrono>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>

namespace novachat {

class PendingChannelActions {
public:
    using Clock = std::chrono::steady_clock;
    static constexpr auto timeout = std::chrono::seconds(30);

    void add(const std::string& requestId, const std::string& playerUuid,
             Clock::time_point now = Clock::now()) {
        if (requestId.empty() || playerUuid.empty()) {
            return;
        }
        std::lock_guard<std::mutex> lock(mMutex);
        std::erase_if(mRequests, [now](const auto& entry) { return entry.second.expiresAt <= now; });
        mRequests.emplace(requestId, Request{playerUuid, now + timeout});
    }

    std::string take(const std::string& requestId, Clock::time_point now = Clock::now()) {
        std::lock_guard<std::mutex> lock(mMutex);
        auto it = mRequests.find(requestId);
        if (it == mRequests.end()) {
            return {};
        }
        auto request = std::move(it->second);
        mRequests.erase(it);
        return request.expiresAt > now ? std::move(request.playerUuid) : std::string{};
    }

    void removePlayer(const std::string& playerUuid) {
        std::lock_guard<std::mutex> lock(mMutex);
        std::erase_if(mRequests, [&](const auto& entry) { return entry.second.playerUuid == playerUuid; });
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mMutex);
        mRequests.clear();
    }

private:
    struct Request {
        std::string playerUuid;
        Clock::time_point expiresAt;
    };

    std::unordered_map<std::string, Request> mRequests;
    std::mutex mMutex;
};

} // namespace novachat
