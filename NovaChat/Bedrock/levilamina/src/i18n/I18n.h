#pragma once

#include <filesystem>
#include <string>
#include <unordered_map>
#include <vector>

namespace novachat::i18n {

/**
 * I18n message lookup for NovaChat-LeviLamina.
 *
 * Translations live as external ``lang/<locale>.json`` files (one file per
 * locale, keyed by filename stem). At construction the singleton scans the
 * lang directory and loads every ``*.json`` file it finds, so adding a new
 * language is just dropping a new ``lang/<locale>.json`` next to the
 * built module -- no code change required.
 *
 * Keys and color codes (&e, §c) stay inside the values; only natural language
 * swaps between locales. Keys mirror the Java client-core message bundles
 * (messages_zh_CN.properties / messages_en_US.properties) for cross-platform
 * parity.
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

    /** Load every <locale>.json from the first candidate dir that exists. */
    void loadLangDir(const std::filesystem::path& dir);

    /** Parse a flat JSON object of string->string into a bundle. */
    static bool parseLangJson(const std::string& text,
                              std::unordered_map<std::string, std::string>& out);

    static std::string format(const std::string& template_, const std::vector<std::string>& args);

    std::unordered_map<std::string, std::unordered_map<std::string, std::string>> mBundles;
};

} // namespace novachat::i18n
