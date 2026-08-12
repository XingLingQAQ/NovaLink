#include "I18n.h"

#include <algorithm>
#include <cstdio>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif
namespace novachat::i18n {

namespace {

/// Minimal flat JSON object parser (string -> string).
///
/// The lang files are simple ``{"key": "value", ...}`` objects whose values
/// are always JSON strings, so this parser only needs to:
///   - skip whitespace / trailing commas,
///   - read a quoted key (with standard escape decoding),
///   - skip the colon,
///   - and read a quoted value (with the same escape decoding).
///
/// Non-string values (numbers/bool/null) are accepted but skipped -- they are
/// not used by the i18n layer. Nested objects/arrays are not expected in lang
/// files and are not supported; an unexpected token aborts the parse so a
/// malformed file is ignored rather than loading partial data.
struct FlatJsonParser {
    const std::string& src;
    size_t pos = 0;

    explicit FlatJsonParser(const std::string& s) : src(s) {}

    bool parse(std::unordered_map<std::string, std::string>& out) {
        skipWs();
        if (!consume('{')) return false;
        skipWs();
        // Empty object.
        if (peek('}')) { ++pos; return true; }
        while (true) {
            skipWs();
            std::string key;
            if (!readString(key)) return false;
            skipWs();
            if (!consume(':')) return false;
            skipWs();
            std::string value;
            bool isString = readString(value);
            if (!isString) {
                // Non-string value: skip until the next comma/brace so the
                // file still parses (the key simply is not collected).
                if (!skipValue()) return false;
            } else {
                out[std::move(key)] = std::move(value);
            }
            skipWs();
            if (peek(',')) { ++pos; skipWs(); continue; }
            if (peek('}')) { ++pos; return true; }
            return false;
        }
    }

private:
    void skipWs() {
        while (pos < src.size()) {
            char c = src[pos];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') { ++pos; }
            else break;
        }
    }

    bool peek(char c) { return pos < src.size() && src[pos] == c; }

    bool consume(char c) {
        if (!peek(c)) return false;
        ++pos;
        return true;
    }

    bool readString(std::string& out) {
        if (!peek('"')) return false;
        ++pos; // opening quote
        out.clear();
        while (pos < src.size()) {
            char c = src[pos++];
            if (c == '"') return true; // closing quote
            if (c == '\\') {
                if (pos >= src.size()) return false;
                char esc = src[pos++];
                switch (esc) {
                    case '"': out.push_back('"'); break;
                    case '\\': out.push_back('\\'); break;
                    case '/': out.push_back('/'); break;
                    case 'b': out.push_back('\b'); break;
                    case 'f': out.push_back('\f'); break;
                    case 'n': out.push_back('\n'); break;
                    case 'r': out.push_back('\r'); break;
                    case 't': out.push_back('\t'); break;
                    case 'u': {
                        uint32_t cp = readHex4();
                        if (cp == 0xFFFFFFFF) return false;
                        // Handle UTF-16 surrogate pairs.
                        if (cp >= 0xD800 && cp <= 0xDBFF) {
                            if (pos + 1 < src.size() && src[pos] == '\\' && src[pos+1] == 'u') {
                                pos += 2;
                                uint32_t lo = readHex4();
                                if (lo < 0xDC00 || lo > 0xDFFF) return false;
                                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                            } else {
                                return false;
                            }
                        }
                        appendUtf8(out, cp);
                        break;
                    }
                    default: return false; // unknown escape
                }
            } else {
                out.push_back(c);
            }
        }
        return false; // unterminated string
    }

    uint32_t readHex4() {
        if (pos + 4 > src.size()) return 0xFFFFFFFF;
        uint32_t v = 0;
        for (int i = 0; i < 4; ++i) {
            char c = src[pos++];
            v <<= 4;
            if (c >= '0' && c <= '9') v |= (c - '0');
            else if (c >= 'a' && c <= 'f') v |= (c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') v |= (c - 'A' + 10);
            else return 0xFFFFFFFF;
        }
        return v;
    }

    static void appendUtf8(std::string& out, uint32_t cp) {
        if (cp <= 0x7F) {
            out.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    /// Skip a non-string JSON value (number, true, false, null, object, array).
    bool skipValue() {
        skipWs();
        if (pos >= src.size()) return false;
        char c = src[pos];
        if (c == '{' || c == '[') {
            char open = c;
            char close = (open == '{') ? '}' : ']';
            int depth = 0;
            bool inStr = false;
            while (pos < src.size()) {
                char ch = src[pos++];
                if (inStr) {
                    if (ch == '\\' && pos < src.size()) { ++pos; }
                    else if (ch == '"') inStr = false;
                } else {
                    if (ch == '"') inStr = true;
                    else if (ch == open) ++depth;
                    else if (ch == close) { --depth; if (depth == 0) return true; }
                }
            }
            return false;
        }
        if (c == '"') {
            std::string tmp;
            return readString(tmp);
        }
        // Bare token: number / true / false / null -- read until delimiter.
        while (pos < src.size()) {
            char ch = src[pos];
            if (ch == ',' || ch == '}' || ch == ']' ||
                ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') break;
            ++pos;
        }
        return true;
    }
};

/// Read a file as UTF-8 text. Returns empty on failure (caller distinguishes
/// "missing file" from "empty file" via filesystem checks upstream).
std::string readFile(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in.is_open()) return {};
    std::ostringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

} // namespace

I18n& I18n::getInstance() {
    static I18n instance;
    return instance;
}

I18n::I18n() {
    // Candidate lang/ directories, in priority order. The first one that
    // exists and yields a valid zh_CN bundle wins. Each candidate keeps the
    // "drop a file = add a language" contract:
    //   1. <exe-dir>/lang        -- the built test binary / packed mod ships
    //                                lang/ next to the executable.
    //   2. <src>/i18n/lang        -- dev checkout (src/i18n/lang/).
    //   3. plugins/NovaChat/lang  -- BDS runtime data dir (best-effort).
    std::vector<std::filesystem::path> candidates;
#ifdef _WIN32
    // 1. Executable directory.
    wchar_t exeBuf[MAX_PATH];
    DWORD exeLen = GetModuleFileNameW(nullptr, exeBuf, MAX_PATH);
    if (exeLen > 0 && exeLen < MAX_PATH) {
        std::filesystem::path exePath(exeBuf);
        candidates.push_back(exePath.parent_path() / "lang");
    }
#endif
    // 2. Source tree (resolved relative to this file at build time via the
    //    __FILE__ macro -- works for the standalone test target which is
    //    compiled from src/i18n/I18n.cpp).
    {
        std::filesystem::path file(__FILE__);
        candidates.push_back(file.parent_path() / "lang");
    }
    // 3. BDS plugin data dir (only relevant at runtime in production).
    candidates.push_back(std::filesystem::path("plugins") / "NovaChat" / "lang");

    for (const auto& dir : candidates) {
        if (!std::filesystem::is_directory(dir)) continue;
        loadLangDir(dir);
        // Stop once the hard-default locale is available so the fallback
        // chain is guaranteed to work.
        auto it = mBundles.find(DEFAULT_LOCALE);
        if (it != mBundles.end() && !it->second.empty()) break;
    }
}

void I18n::loadLangDir(const std::filesystem::path& dir) {
    std::error_code ec;
    for (const auto& entry : std::filesystem::directory_iterator(dir, ec)) {
        if (ec) break;
        if (!entry.is_regular_file()) continue;
        const auto& path = entry.path();
        if (path.extension() != ".json") continue;
        std::string locale = path.stem().string();
        std::string text = readFile(path);
        if (text.empty()) continue;
        std::unordered_map<std::string, std::string> bundle;
        if (!parseLangJson(text, bundle) || bundle.empty()) continue;
        mBundles[std::move(locale)] = std::move(bundle);
    }
}

bool I18n::parseLangJson(const std::string& text,
                         std::unordered_map<std::string, std::string>& out) {
    FlatJsonParser parser(text);
    return parser.parse(out);
}

std::string I18n::get(const std::string& key, const std::string& locale,
                      const std::vector<std::string>& args) const {
    auto it = mBundles.find(locale);
    const auto* bundle = (it != mBundles.end()) ? &it->second : nullptr;

    std::string template_;
    if (bundle) {
        auto kit = bundle->find(key);
        if (kit != bundle->end()) {
            template_ = kit->second;
        }
    }
    if (template_.empty()) {
        // Fallback to default locale.
        auto defIt = mBundles.find(DEFAULT_LOCALE);
        if (defIt != mBundles.end()) {
            auto kit = defIt->second.find(key);
            if (kit != defIt->second.end()) {
                template_ = kit->second;
            }
        }
    }
    if (template_.empty()) {
        template_ = key; // Final fallback: the key itself.
    }
    return format(template_, args);
}

std::string I18n::errorMessage(const std::string& errorCode, const std::string& locale) const {
    std::string message = get("error." + errorCode + ".message", locale, {errorCode});
    std::string suggestion = get("error." + errorCode + ".suggestion", locale);
    std::string prefix = get("error.suggestion_prefix", locale);
    return "§c" + message + " §7" + prefix + " " + suggestion;
}

std::string I18n::format(const std::string& tmpl, const std::vector<std::string>& args) {
    std::string result = tmpl;
    for (size_t i = 0; i < args.size(); ++i) {
        std::string placeholder = "{" + std::to_string(i) + "}";
        size_t pos = 0;
        while ((pos = result.find(placeholder, pos)) != std::string::npos) {
            result.replace(pos, placeholder.size(), args[i]);
            pos += args[i].size();
        }
    }
    return result;
}

} // namespace novachat::i18n
