#include "NovaChatConfig.h"

#include <nlohmann/json.hpp>

#include <algorithm>
#include <cctype>
#include <fstream>
#include <iterator>
#include <limits>
#include <random>
#include <set>
#include <stdexcept>
#include <system_error>
#include <utility>

#ifdef _WIN32
#include <Windows.h>
#endif

namespace novachat {
namespace {

using Json = nlohmann::ordered_json;

const std::set<std::string> DynamicMappings{
    "chat.channel_prefixes",
    "format.channels",
    "world_routing.mappings",
};

std::string readText(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input.is_open()) {
        throw std::runtime_error("Unable to open " + path.string());
    }
    return std::string(std::istreambuf_iterator<char>(input),
                       std::istreambuf_iterator<char>());
}

Json parseObject(const std::string& content, const std::string& source) {
    Json document = Json::parse(content);
    if (!document.is_object()) {
        throw std::runtime_error(source + " root must be a JSON object");
    }
    return document;
}

const Json& requireValue(const Json& parent, const std::string& key,
                         const std::string& path) {
    if (!parent.contains(key)) {
        throw std::runtime_error("Required configuration value " + path + " is missing");
    }
    return parent.at(key);
}

const Json& requireObject(const Json& parent, const std::string& key,
                          const std::string& path) {
    const Json& value = requireValue(parent, key, path);
    if (!value.is_object()) {
        throw std::runtime_error("Configuration value " + path + " must be an object");
    }
    return value;
}

bool isBlank(const std::string& value) {
    return std::all_of(value.begin(), value.end(), [](unsigned char character) {
        return std::isspace(character) != 0;
    });
}

std::string requireString(const Json& parent, const std::string& key,
                          const std::string& path, bool nonBlank = false) {
    const Json& value = requireValue(parent, key, path);
    if (!value.is_string()) {
        throw std::runtime_error("Configuration value " + path + " must be a string");
    }
    std::string result = value.get<std::string>();
    if (nonBlank && isBlank(result)) {
        throw std::runtime_error("Configuration value " + path + " must not be blank");
    }
    return result;
}

int64_t requireInteger(const Json& parent, const std::string& key,
                       const std::string& path) {
    const Json& value = requireValue(parent, key, path);
    if (!value.is_number_integer()) {
        throw std::runtime_error("Configuration value " + path + " must be an integer");
    }
    if (value.is_number_unsigned()) {
        const uint64_t unsignedValue = value.get<uint64_t>();
        if (unsignedValue > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
            throw std::runtime_error("Configuration value " + path + " is too large");
        }
        return static_cast<int64_t>(unsignedValue);
    }
    return value.get<int64_t>();
}

bool requireBoolean(const Json& parent, const std::string& key,
                    const std::string& path) {
    const Json& value = requireValue(parent, key, path);
    if (!value.is_boolean()) {
        throw std::runtime_error("Configuration value " + path + " must be a boolean");
    }
    return value.get<bool>();
}

bool mergeMissing(Json& target, const Json& configTemplate,
                  const std::string& parentPath = "") {
    bool changed = false;
    for (auto iterator = configTemplate.begin(); iterator != configTemplate.end(); ++iterator) {
        const std::string path = parentPath.empty()
            ? iterator.key() : parentPath + "." + iterator.key();
        if (!target.contains(iterator.key())) {
            // Dynamic mappings are owned by the operator/runtime. If they
            // are absent, keep them absent instead of restoring template
            // examples during an upgrade.
            if (DynamicMappings.contains(path)) {
                continue;
            }
            if (iterator.value().is_object()) {
                Json child = Json::object();
                mergeMissing(child, iterator.value(), path);
                target[iterator.key()] = std::move(child);
            } else {
                target[iterator.key()] = iterator.value();
            }
            changed = true;
            continue;
        }

        Json& current = target[iterator.key()];
        const Json& templateValue = iterator.value();
        if (templateValue.is_object()) {
            if (current.is_object() && !DynamicMappings.contains(path)) {
                changed |= mergeMissing(current, templateValue, path);
            }
        }
    }
    return changed;
}

void replaceFile(const std::filesystem::path& source,
                 const std::filesystem::path& target) {
#ifdef _WIN32
    if (!MoveFileExW(source.c_str(), target.c_str(),
                     MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        throw std::system_error(
            static_cast<int>(GetLastError()), std::system_category(),
            "Failed to atomically replace " + target.string());
    }
#else
    std::filesystem::rename(source, target);
#endif
}

std::filesystem::path createTempPath(const std::filesystem::path& target) {
    static thread_local std::mt19937_64 generator(std::random_device{}());
    for (int attempt = 0; attempt < 100; ++attempt) {
        const std::filesystem::path candidate = target.parent_path()
            / (target.filename().string() + "." + std::to_string(generator()) + ".tmp");
        if (!std::filesystem::exists(candidate)) {
            return candidate;
        }
    }
    throw std::runtime_error("Unable to allocate a temporary config file");
}

void writeAtomically(const std::filesystem::path& target,
                     const std::string& content) {
    const std::filesystem::path temp = createTempPath(target);
    std::error_code ignored;
    try {
        std::ofstream output(temp, std::ios::binary | std::ios::trunc);
        if (!output.is_open()) {
            throw std::runtime_error("Unable to open temporary config file");
        }
        output.write(content.data(), static_cast<std::streamsize>(content.size()));
        output.flush();
        if (!output.good()) {
            throw std::runtime_error("Unable to write temporary config file");
        }
        output.close();
        if (!output) {
            throw std::runtime_error("Unable to close temporary config file");
        }
        replaceFile(temp, target);
    } catch (...) {
        std::filesystem::remove(temp, ignored);
        throw;
    }
}

} // namespace

NovaChatConfig::NovaChatConfig(const std::filesystem::path& dataDir,
                               std::filesystem::path templatePath)
    : mDataDir(dataDir)
    , mConfigPath(dataDir / "config.json")
    , mTemplatePath(templatePath.empty()
        ? dataDir / "default-config.json" : std::move(templatePath)) {
}

bool NovaChatConfig::load() {
    try {
        std::filesystem::create_directories(mDataDir);
        const std::string templateContent = readText(mTemplatePath);
        const Json configTemplate = parseObject(templateContent, "Bundled template");

        bool created = false;
        bool updated = false;
        Json document;
        if (!std::filesystem::exists(mConfigPath)) {
            document = configTemplate;
            created = true;
        } else {
            document = parseObject(readText(mConfigPath), "Existing config.json");
            updated = mergeMissing(document, configTemplate);
        }

        const Json& backend = requireObject(document, "backend", "backend");
        const Json& chat = requireObject(document, "chat", "chat");
        const Json& format = requireObject(document, "format", "format");
        const int64_t configVersion = requireInteger(
            document, "config-version", "config-version");
        if (configVersion <= 0) {
            throw std::runtime_error(
                "Configuration value config-version must be greater than 0");
        }
        const Json* channels = nullptr;
        if (format.contains("channels")) {
            if (!format.at("channels").is_object()) {
                throw std::runtime_error(
                    "Configuration value format.channels must be an object");
            }
            channels = &format.at("channels");
        }

        std::string backendHost = requireString(backend, "host", "backend.host", true);
        const int64_t portValue = requireInteger(backend, "port", "backend.port");
        if (portValue < 1 || portValue > 65535) {
            throw std::runtime_error("backend.port must be between 1 and 65535");
        }
        const uint16_t backendPort = static_cast<uint16_t>(portValue);
        std::string username = requireString(backend, "username", "backend.username", true);
        std::string password = requireString(backend, "password", "backend.password");
        std::string serverVersion = requireString(
            backend, "server_version", "backend.server_version", true);
        const int64_t reconnectDelayValue = requireInteger(
            backend, "reconnect_delay", "backend.reconnect_delay");
        if (reconnectDelayValue <= 0
            || reconnectDelayValue > std::numeric_limits<int>::max()) {
            throw std::runtime_error(
                "backend.reconnect_delay must be between 1 and "
                + std::to_string(std::numeric_limits<int>::max()));
        }
        const int reconnectDelay = static_cast<int>(reconnectDelayValue);

        // AUTH-002 TLS: backend transport encryption. The tls block is optional
        // — the bundled default-config.json template does not ship one (the
        // file is ACL-locked against edits), so absent means the plaintext
        // default (zero regression for existing configs). When present, enable
        // defaults to false and the backend certificate is ALWAYS verified
        // against ca_cert_path (or the system CA store when empty); there is no
        // option to disable verification. The optional mTLS pair must be both
        // set or both empty.
        bool tlsEnabled = false;
        std::string tlsCaCertPath;
        std::string tlsClientCertPath;
        std::string tlsClientKeyPath;
        if (backend.contains("tls")) {
            const Json& tls = requireObject(backend, "tls", "backend.tls");
            tlsEnabled = requireBoolean(tls, "enable", "backend.tls.enable");
            tlsCaCertPath = requireString(tls, "ca_cert_path", "backend.tls.ca_cert_path");
            tlsClientCertPath = requireString(
                tls, "client_cert_path", "backend.tls.client_cert_path");
            tlsClientKeyPath = requireString(
                tls, "client_key_path", "backend.tls.client_key_path");
            const bool hasCert = !isBlank(tlsClientCertPath);
            const bool hasKey = !isBlank(tlsClientKeyPath);
            if (hasCert != hasKey) {
                throw std::runtime_error(
                    "Configuration values backend.tls.client_cert_path and "
                    "backend.tls.client_key_path must both be set or both be empty");
            }
        }

        const bool replaceVanilla = requireBoolean(
            chat, "replace_vanilla", "chat.replace_vanilla");
        std::string defaultChannel = requireString(
            chat, "default_channel", "chat.default_channel", true);
        std::string prefix = requireString(format, "prefix", "format.prefix");
        std::string errorFormat = requireString(format, "error", "format.error");
        std::string successFormat = requireString(format, "success", "format.success");
        std::string defaultFormat = requireString(format, "default", "format.default");
        const bool debug = requireBoolean(document, "debug", "debug");

        std::unordered_map<std::string, std::string> channelFormats;
        if (channels != nullptr) {
            for (const auto& [key, value] : channels->items()) {
                if (!value.is_string()) {
                    throw std::runtime_error(
                        "Configuration value format.channels." + key + " must be a string");
                }
                channelFormats.emplace(key, value.get<std::string>());
            }
        }

        std::filesystem::path backupPath;
        if (created) {
            writeAtomically(mConfigPath, templateContent);
        } else if (updated) {
            const std::string rendered = document.dump(4) + "\n";
            parseObject(rendered, "Generated config.json");
            backupPath = mConfigPath.string() + ".bak";
            std::filesystem::copy_file(
                mConfigPath, backupPath,
                std::filesystem::copy_options::overwrite_existing);
            writeAtomically(mConfigPath, rendered);
        }

        mBackendHost = std::move(backendHost);
        mBackendPort = backendPort;
        mUsername = std::move(username);
        mPassword = std::move(password);
        mServerVersion = std::move(serverVersion);
        mReconnectDelay = reconnectDelay;
        mTlsEnabled = tlsEnabled;
        mTlsCaCertPath = std::move(tlsCaCertPath);
        mTlsClientCertPath = std::move(tlsClientCertPath);
        mTlsClientKeyPath = std::move(tlsClientKeyPath);
        mReplaceVanilla = replaceVanilla;
        mDefaultChannel = std::move(defaultChannel);
        mPrefix = std::move(prefix);
        mErrorFormat = std::move(errorFormat);
        mSuccessFormat = std::move(successFormat);
        mDefaultFormat = std::move(defaultFormat);
        mChannelFormats = std::move(channelFormats);
        mDebug = debug;
        mWasCreated = created;
        mWasUpdated = updated;
        mBackupPath = std::move(backupPath);
        mLastError.clear();
        return true;
    } catch (const std::exception& exception) {
        mLastError = exception.what();
        return false;
    }
}

bool NovaChatConfig::reload() {
    return load();
}

std::string NovaChatConfig::getChannelFormat(const std::string& channelId) const {
    const auto iterator = mChannelFormats.find(channelId);
    return iterator != mChannelFormats.end() ? iterator->second : mDefaultFormat;
}

} // namespace novachat
