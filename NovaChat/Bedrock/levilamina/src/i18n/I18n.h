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

    /**
     * Register an extra lang/ directory and load it immediately.
     *
     * The constructor's built-in candidates (<exe-dir>/lang, the __FILE__-relative
     * dev path, plugins/NovaChat/lang) do NOT include the mod's own directory.
     * A LeviLamina mod is installed as plugins/<modDir>/ and its bundled lang/
     * sits at plugins/<modDir>/lang, so without an explicit registration every
     * lookup falls through to "return the key itself" and the player sees raw
     * keys such as "chat.command.help.title" instead of the translated text.
     *
     * Loading happens eagerly rather than being queued for a later pass: the
     * singleton may already be constructed (the first getInstance() call runs
     * the candidate scan), so a queued path would never be read. Callers pass
     * the path once during load(); re-registering the same directory just
     * reloads it.
     */
    void registerLangDir(const std::filesystem::path& dir);

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
