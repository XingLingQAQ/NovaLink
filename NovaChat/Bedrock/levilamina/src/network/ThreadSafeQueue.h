#pragma once

#include <queue>
#include <mutex>
#include <condition_variable>
#include <optional>
#include <chrono>

namespace novachat::network {

/**
 * Thread-safe queue for inter-thread communication.
 * Used for passing packets between network thread and main thread.
 */
template<typename T>
class ThreadSafeQueue {
public:
    ThreadSafeQueue() = default;
    ~ThreadSafeQueue() = default;

    // Non-copyable
    ThreadSafeQueue(const ThreadSafeQueue&) = delete;
    ThreadSafeQueue& operator=(const ThreadSafeQueue&) = delete;

    /**
     * Push an item to the queue.
     * @param item the item to push
     */
    void push(T item) {
        {
            std::lock_guard<std::mutex> lock(mMutex);
            mQueue.push(std::move(item));
        }
        mCondition.notify_one();
    }

    /**
     * Try to pop an item from the queue without blocking.
     * @return the item if available, std::nullopt otherwise
     */
    std::optional<T> tryPop() {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mQueue.empty()) {
            return std::nullopt;
        }
        T item = std::move(mQueue.front());
        mQueue.pop();
        return item;
    }

    /**
     * Pop an item from the queue, blocking if empty.
     * @return the item
     */
    T pop() {
        std::unique_lock<std::mutex> lock(mMutex);
        mCondition.wait(lock, [this] { return !mQueue.empty() || mStopped; });
        if (mStopped && mQueue.empty()) {
            throw std::runtime_error("Queue stopped");
        }
        T item = std::move(mQueue.front());
        mQueue.pop();
        return item;
    }

    /**
     * Pop an item from the queue with timeout.
     * @param timeout the maximum time to wait
     * @return the item if available within timeout, std::nullopt otherwise
     */
    template<typename Rep, typename Period>
    std::optional<T> popWithTimeout(const std::chrono::duration<Rep, Period>& timeout) {
        std::unique_lock<std::mutex> lock(mMutex);
        if (!mCondition.wait_for(lock, timeout, [this] { return !mQueue.empty() || mStopped; })) {
            return std::nullopt;
        }
        if (mStopped && mQueue.empty()) {
            return std::nullopt;
        }
        T item = std::move(mQueue.front());
        mQueue.pop();
        return item;
    }

    /**
     * Check if the queue is empty.
     * @return true if empty
     */
    [[nodiscard]] bool empty() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return mQueue.empty();
    }

    /**
     * Get the size of the queue.
     * @return the number of items in the queue
     */
    [[nodiscard]] size_t size() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return mQueue.size();
    }

    /**
     * Clear all items from the queue.
     */
    void clear() {
        std::lock_guard<std::mutex> lock(mMutex);
        std::queue<T> empty;
        std::swap(mQueue, empty);
    }

    /**
     * Stop the queue, waking up all waiting threads.
     */
    void stop() {
        {
            std::lock_guard<std::mutex> lock(mMutex);
            mStopped = true;
        }
        mCondition.notify_all();
    }

    /**
     * Reset the stopped state.
     */
    void reset() {
        std::lock_guard<std::mutex> lock(mMutex);
        mStopped = false;
    }

private:
    mutable std::mutex mMutex;
    std::condition_variable mCondition;
    std::queue<T> mQueue;
    bool mStopped = false;
};

} // namespace novachat::network
