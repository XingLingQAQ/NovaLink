#include "I18n.h"

#include <algorithm>

namespace novachat::i18n {

I18n& I18n::getInstance() {
    static I18n instance;
    return instance;
}

I18n::I18n() {
    // ===== zh_CN (mirrors client-core messages_zh_CN.properties) =====
    std::unordered_map<std::string, std::string> zhCN = {
        // chat.*
        {"chat.join.joining", "正在加入频道 &e{0}&7..."},
        {"chat.join.joined", "已加入频道 &e{0}"},
        {"chat.leave.leaving", "正在离开频道 &e{0}&7..."},
        {"chat.leave.left", "已离开频道 &e{0}&7，已切换到默认频道: &e{1}"},
        {"chat.status.current_bar", "&7当前频道：&b{0} &7（{1}）"},
        {"chat.toggle.on", "聊天已开启"},
        {"chat.toggle.off", "聊天已关闭"},

        // chat.command.*
        {"chat.command.help.title", "&6=== NovaChat 帮助 ==="},
        {"chat.command.help.line_help", "&e/nc help &r- 显示帮助信息"},
        {"chat.command.help.line_join", "&e/nc join <频道> [密码] &r- 加入频道"},
        {"chat.command.help.line_leave", "&e/nc leave [频道] &r- 离开频道"},
        {"chat.command.help.line_list", "&e/nc list &r- 列出可用频道"},
        {"chat.command.help.line_who", "&e/nc who [频道] &r- 查看频道在线成员"},
        {"chat.command.help.line_toggle", "&e/nc toggle &r- 切换聊天模式"},
        {"chat.command.help.line_reload", "&e/nc reload &r- 重载配置"},
        {"chat.command.help.line_debug", "&e/nc debug &r- 切换调试模式"},
        {"chat.command.desc.help", "显示可用命令列表"},
        {"chat.command.desc.join", "加入一个频道"},
        {"chat.command.desc.leave", "离开当前频道"},
        {"chat.command.desc.list", "列出可用频道"},
        {"chat.command.desc.who", "查看频道在线成员"},
        {"chat.command.desc.toggle", "切换聊天模式"},
        {"chat.command.desc.reload", "重新加载配置"},
        {"chat.command.desc.debug", "切换调试模式"},
        {"chat.command.list.title", "&6=== NovaChat 频道列表 ==="},
        {"chat.command.list.tail", "&6==========================="},
        {"chat.command.player_only", "此命令只能由玩家执行"},
        {"chat.command.usage.join", "用法: /nc join <频道> [密码]"},
        {"chat.command.reload.success", "配置已重载"},
        {"chat.command.toggle.switched", "聊天模式已切换为: {0}"},
        {"chat.command.no_permission", "您没有执行此操作的权限"},
        {"chat.command.unknown", "未知命令: {0}"},
        {"chat.command.unknown_hint", "使用 &e/{0} help &7查看可用命令"},
        {"chat.command.no_permission_code", "你没有权限执行此命令 (NC-403)"},
        {"chat.command.specify_channel", "请指定频道ID"},

        // chat.network.*
        {"chat.network.not_connected_retry", "未连接到聊天服务器，请稍后再试"},
        {"chat.network.not_connected", "未连接到聊天服务器"},

        // chat.mention.*
        {"chat.mention.subtitle", "&7在频道 &b{0} &7提到了你"},

        // chat.notice.* (kick/mute target-side title/action-bar)
        {"chat.notice.kick_title", "&c你已被踢出频道"},
        {"chat.notice.kick_subtitle", "&7被 &e{0} &7踢出频道 &b{1}"},
        {"chat.notice.kick_actionbar", "&c你已被 {0} 踢出频道 {1}"},
        {"chat.notice.mute_title", "&c你已被禁言"},
        {"chat.notice.mute_subtitle", "&7在频道 &b{0} &7持续 &e{1}"},
        {"chat.notice.mute_actionbar", "&c你已被禁言 {0}（频道 {1}）"},

        // chat.action.*
        {"chat.action.success", "操作成功"},
        {"chat.action.failed", "操作失败"},
        {"chat.action.leave_simple", "已离开频道 &e{0}"},

        // chat.list.*
        {"chat.list.empty", "暂无已知频道，请等待服务器下发频道列表"},

        // chat.who.*
        {"chat.who.unavailable", "频道成员查询暂不可用（需后端支持）"},
        {"chat.who.fetching", "正在获取频道 &e{0} &7的在线成员..."},
        {"chat.who.list_header", "&6频道 &e{0} &6在线成员 &7（{1} 人）："},
        {"chat.who.list_body", "&7{0}"},
        {"chat.who.list_empty", "&7暂无在线成员"},
        {"chat.who.no_channel", "你当前不在任何频道中，请指定频道ID"},
        {"chat.who.specify_channel", "请指定频道ID"},

        // chat.welcome.*
        {"chat.welcome.line", "&6欢迎！&r输入 &e/nc help &r查看聊天频道，&e/nc list &r列出可用频道"},

        // chat.debug.*
        {"chat.debug.enabled", "调试模式已 &a启用"},
        {"chat.debug.disabled", "调试模式已 &c禁用"},
        {"chat.debug.connected", "&7已连接: &e{0}"},
        {"chat.debug.authenticated", "&7已认证: &e{0}"},

        // error.*
        {"error.suggestion_prefix", "提示:"},
        {"error.NC-400.message", "请求参数错误"},
        {"error.NC-400.suggestion", "请检查命令参数是否正确"},
        {"error.NC-401.message", "认证失败"},
        {"error.NC-401.suggestion", "请检查用户名和密码是否正确"},
        {"error.NC-403.message", "权限不足"},
        {"error.NC-403.suggestion", "您没有执行此操作的权限，请联系管理员"},
        {"error.NC-404.message", "资源不存在"},
        {"error.NC-404.suggestion", "请检查频道ID或玩家名称是否正确"},
        {"error.NC-409.message", "资源冲突"},
        {"error.NC-409.suggestion", "该资源已存在或正在被使用"},
        {"error.NC-420.message", "协议版本不兼容"},
        {"error.NC-420.suggestion", "请升级 NovaChat 客户端至支持协议 v2 的版本"},
        {"error.NC-429.message", "请求过于频繁"},
        {"error.NC-429.suggestion", "请稍后再试"},
        {"error.NC-430.message", "格式错误"},
        {"error.NC-430.suggestion", "请检查输入格式是否正确"},
        {"error.NC-431.message", "频道已满"},
        {"error.NC-431.suggestion", "该频道已达到最大容量"},
        {"error.NC-432.message", "已加入该频道"},
        {"error.NC-432.suggestion", "您已经在该频道中"},
        {"error.NC-433.message", "不在该频道中"},
        {"error.NC-433.suggestion", "您需要先加入该频道"},
        {"error.NC-434.message", "密码错误"},
        {"error.NC-434.suggestion", "请检查频道密码是否正确"},
        {"error.NC-435.message", "世界限制"},
        {"error.NC-435.suggestion", "该频道仅在特定世界可用"},
        {"error.NC-436.message", "您已被禁言"},
        {"error.NC-436.suggestion", "禁言期间无法发送消息"},
        {"error.NC-500.message", "服务器内部错误"},
        {"error.NC-500.suggestion", "请联系管理员检查服务器日志"},
        {"error.NC-503.message", "服务不可用"},
        {"error.NC-503.suggestion", "未连接到后端服务器，请稍后再试"},

        // notice/duration fallbacks
        {"notice.operator.fallback", "管理员"},
        {"notice.duration.unknown", "一段时间"},
        {"duration.seconds", "{0}秒"},
        {"duration.minutes", "{0}分钟"},
        {"duration.hours", "{0}小时"},
        {"duration.days", "{0}天"},
    };

    // ===== en_US (mirrors client-core messages_en_US.properties) =====
    std::unordered_map<std::string, std::string> enUS = {
        {"chat.join.joining", "Joining channel &e{0}&7..."},
        {"chat.join.joined", "Joined channel &e{0}"},
        {"chat.leave.leaving", "Leaving channel &e{0}&7..."},
        {"chat.leave.left", "Left channel &e{0}&7, switched to default: &e{1}"},
        {"chat.status.current_bar", "&7Current channel: &b{0} &7({1})"},
        {"chat.toggle.on", "Chat enabled"},
        {"chat.toggle.off", "Chat disabled"},

        {"chat.command.help.title", "&6=== NovaChat Help ==="},
        {"chat.command.help.line_help", "&e/nc help &r- Show this help"},
        {"chat.command.help.line_join", "&e/nc join <channel> [password] &r- Join a channel"},
        {"chat.command.help.line_leave", "&e/nc leave [channel] &r- Leave a channel"},
        {"chat.command.help.line_list", "&e/nc list &r- List available channels"},
        {"chat.command.help.line_who", "&e/nc who [channel] &r- List online members"},
        {"chat.command.help.line_toggle", "&e/nc toggle &r- Toggle chat mode"},
        {"chat.command.help.line_reload", "&e/nc reload &r- Reload config"},
        {"chat.command.help.line_debug", "&e/nc debug &r- Toggle debug mode"},
        {"chat.command.desc.help", "Show available commands"},
        {"chat.command.desc.join", "Join a channel"},
        {"chat.command.desc.leave", "Leave the current channel"},
        {"chat.command.desc.list", "List available channels"},
        {"chat.command.desc.who", "List online members of a channel"},
        {"chat.command.desc.toggle", "Toggle chat mode"},
        {"chat.command.desc.reload", "Reload configuration"},
        {"chat.command.desc.debug", "Toggle debug mode"},
        {"chat.command.list.title", "&6=== NovaChat Channel List ==="},
        {"chat.command.list.tail", "&6==========================="},
        {"chat.command.player_only", "This command can only be run by a player"},
        {"chat.command.usage.join", "Usage: /nc join <channel> [password]"},
        {"chat.command.reload.success", "Configuration reloaded"},
        {"chat.command.toggle.switched", "Chat mode switched to: {0}"},
        {"chat.command.no_permission", "You do not have permission to do this"},
        {"chat.command.unknown", "Unknown command: {0}"},
        {"chat.command.unknown_hint", "Use &e/{0} help &7to see available commands"},
        {"chat.command.no_permission_code", "You do not have permission (NC-403)"},
        {"chat.command.specify_channel", "Please specify a channel id"},

        {"chat.network.not_connected_retry", "Not connected to chat server, please retry later"},
        {"chat.network.not_connected", "Not connected to chat server"},

        {"chat.mention.subtitle", "&7mentioned you in &b{0}"},

        {"chat.notice.kick_title", "&cYou have been kicked from the channel"},
        {"chat.notice.kick_subtitle", "&7Kicked by &e{0} &7from &b{1}"},
        {"chat.notice.kick_actionbar", "&cYou were kicked by {0} from {1}"},
        {"chat.notice.mute_title", "&cYou have been muted"},
        {"chat.notice.mute_subtitle", "&7In channel &b{0} &7for &e{1}"},
        {"chat.notice.mute_actionbar", "&cYou were muted for {0} (channel {1})"},

        {"chat.action.success", "Action succeeded"},
        {"chat.action.failed", "Action failed"},
        {"chat.action.leave_simple", "Left channel &e{0}"},

        {"chat.list.empty", "No known channels yet, please wait for the server to push the channel list"},

        {"chat.who.unavailable", "Channel member query is unavailable (requires backend support)"},
        {"chat.who.fetching", "Fetching online members for &e{0}&7..."},
        {"chat.who.list_header", "&6Channel &e{0} &6online members &7({1}):"},
        {"chat.who.list_body", "&7{0}"},
        {"chat.who.list_empty", "&7No online members"},
        {"chat.who.no_channel", "You are not in any channel, please specify a channel id"},
        {"chat.who.specify_channel", "Please specify a channel id"},

        {"chat.welcome.line", "&6Welcome!&r Type &e/nc help &rto see channels, &e/nc list &rto list channels"},

        {"chat.debug.enabled", "Debug mode &aenabled"},
        {"chat.debug.disabled", "Debug mode &cdisabled"},
        {"chat.debug.connected", "&7Connected: &e{0}"},
        {"chat.debug.authenticated", "&7Authenticated: &e{0}"},

        {"error.suggestion_prefix", "Hint:"},
        {"error.NC-400.message", "Bad request"},
        {"error.NC-400.suggestion", "Please check the command arguments"},
        {"error.NC-401.message", "Authentication failed"},
        {"error.NC-401.suggestion", "Please check the username and password"},
        {"error.NC-403.message", "Forbidden"},
        {"error.NC-403.suggestion", "You do not have permission, contact an admin"},
        {"error.NC-404.message", "Not found"},
        {"error.NC-404.suggestion", "Please check the channel id or player name"},
        {"error.NC-409.message", "Conflict"},
        {"error.NC-409.suggestion", "The resource already exists or is in use"},
        {"error.NC-420.message", "Protocol version mismatch"},
        {"error.NC-420.suggestion", "Please upgrade your NovaChat client to a protocol v2 build"},
        {"error.NC-429.message", "Too many requests"},
        {"error.NC-429.suggestion", "Please try again later"},
        {"error.NC-430.message", "Invalid format"},
        {"error.NC-430.suggestion", "Please check the input format"},
        {"error.NC-431.message", "Channel full"},
        {"error.NC-431.suggestion", "The channel has reached its capacity"},
        {"error.NC-432.message", "Already in channel"},
        {"error.NC-432.suggestion", "You are already in this channel"},
        {"error.NC-433.message", "Not in channel"},
        {"error.NC-433.suggestion", "You need to join the channel first"},
        {"error.NC-434.message", "Wrong password"},
        {"error.NC-434.suggestion", "Please check the channel password"},
        {"error.NC-435.message", "World restricted"},
        {"error.NC-435.suggestion", "This channel is only available in specific worlds"},
        {"error.NC-436.message", "You are muted"},
        {"error.NC-436.suggestion", "You cannot send messages while muted"},
        {"error.NC-500.message", "Internal server error"},
        {"error.NC-500.suggestion", "Please contact an admin to check the server logs"},
        {"error.NC-503.message", "Service unavailable"},
        {"error.NC-503.suggestion", "Not connected to the backend, please try again later"},

        {"notice.operator.fallback", "an operator"},
        {"notice.duration.unknown", "a while"},
        {"duration.seconds", "{0}s"},
        {"duration.minutes", "{0}m"},
        {"duration.hours", "{0}h"},
        {"duration.days", "{0}d"},
    };

    mBundles["zh_CN"] = std::move(zhCN);
    mBundles["en_US"] = std::move(enUS);
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
