#pragma once

#include <string>
#include <unordered_map>
#include <vector>

namespace novachat::i18n {

/**
 * I18n message lookup for NovaChat-LeviLamina.
 *
 * Mirrors the Java client-core message bundles
 * (messages_zh_CN.properties / messages_en_US.properties). Keys and color
 * codes (&e, §c) stay inside the values; only natural language swaps
 * between locales.
 *
 * Fallback chain: requested locale -> zh_CN (hard default) -> key itself,
 * matching the Java Utf8Control fallback behaviour.
 */
class I18n {
public:
    static I18n& getInstance();

    /** Look up a localized message and substitute {0}, {1}, ... placeholders. */
    std::string get(const std::string& key, const std::string& locale,
                    const std::vector<std::string>& args = {}) const;

    /**
     * Build a human-readable error message from an NC-* error code.
     * Combines error.<code>.message + error.<code>.suggestion.
     */
    std::string errorMessage(const std::string& errorCode, const std::string& locale) const;

    static constexpr const char* DEFAULT_LOCALE = "zh_CN";

private:
    I18n();

    std::unordered_map<std::string, std::unordered_map<std::string, std::string>> mBundles;

    static std::string format(const std::string& template_, const std::vector<std::string>& args);
};

} // namespace novachat::i18n
