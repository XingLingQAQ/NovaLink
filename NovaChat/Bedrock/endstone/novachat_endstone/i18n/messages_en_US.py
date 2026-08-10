"""English (United States) messages for NovaChat-Endstone.

Mirrors client-core/src/main/resources/messages_en_US.properties.
Any key absent here falls back to zh_CN (the default bundle).
"""

EN_US = {
    # chat.* (PlayerMessages)
    "chat.join.joining": "Joining channel &e{0}&7...",
    "chat.join.joined": "Joined channel &e{0}",
    "chat.leave.leaving": "Leaving channel &e{0}&7...",
    "chat.leave.left": "Left channel &e{0}&7, switched to default channel: &e{1}",
    "chat.status.current_bar": "&7Current channel: &b{0} &7({1})",
    "chat.toggle.on": "Chat enabled",
    "chat.toggle.off": "Chat disabled",

    # chat.command.*
    "chat.command.help.title": "&6=== NovaChat Help ===",
    "chat.command.help.line_help": "&e/nc help &r- Show help",
    "chat.command.help.line_join": "&e/nc join <channel> [password] &r- Join a channel",
    "chat.command.help.line_leave": "&e/nc leave [channel] &r- Leave a channel",
    "chat.command.help.line_list": "&e/nc list &r- List available channels",
    "chat.command.help.line_who": "&e/nc who [channel] &r- View channel members online",
    "chat.command.help.line_toggle": "&e/nc toggle &r- Toggle chat mode",
    "chat.command.help.line_reload": "&e/nc reload &r- Reload config",
    "chat.command.help.line_debug": "&e/nc debug &r- Toggle debug mode",
    "chat.command.desc.help": "Show available commands",
    "chat.command.desc.join": "Join a channel",
    "chat.command.desc.leave": "Leave the current channel",
    "chat.command.desc.list": "List available channels",
    "chat.command.desc.who": "View channel members online",
    "chat.command.desc.toggle": "Toggle chat mode",
    "chat.command.desc.reload": "Reload configuration",
    "chat.command.desc.debug": "Toggle debug mode",
    "chat.command.list.title": "&6=== NovaChat Channels ===",
    "chat.command.list.tail": "&6===========================",
    "chat.command.player_only": "This command can only be run by a player",
    "chat.command.usage.join": "Usage: /nc join <channel> [password]",
    "chat.command.reload.success": "Configuration reloaded",
    "chat.command.toggle.switched": "Chat mode switched to: {0}",
    "chat.command.no_permission": "You do not have permission to do this",
    "chat.command.unknown": "Unknown command: {0}",
    "chat.command.unknown_hint": "Use &e/{0} help &7to see available commands",
    "chat.command.no_permission_code": "You do not have permission to run this command (NC-403)",
    "chat.command.specify_channel": "Please specify a channel id",

    # chat.network.*
    "chat.network.not_connected_retry": "Not connected to the chat server, please try again later",
    "chat.network.not_connected": "Not connected to the chat server",

    # chat.mention.*
    "chat.mention.subtitle": "&7Mentioned you in channel &b{0}",

    # chat.notice.* (kick/mute target-side title/action-bar)
    "chat.notice.kick_title": "&cYou have been kicked from the channel",
    "chat.notice.kick_subtitle": "&7Kicked from channel &b{1} &7by &e{0}",
    "chat.notice.kick_actionbar": "&cYou were kicked from channel {1} by {0}",
    "chat.notice.mute_title": "&cYou have been muted",
    "chat.notice.mute_subtitle": "&7Muted in channel &b{0} &7for &e{1}",
    "chat.notice.mute_actionbar": "&cYou were muted {0} (channel {1})",

    # chat.action.*
    "chat.action.success": "Action succeeded",
    "chat.action.failed": "Action failed",
    "chat.action.leave_simple": "Left channel &e{0}",

    # chat.list.*
    "chat.list.empty": "No known channels yet, waiting for the server to send the channel list",

    # chat.who.*
    "chat.who.unavailable": "Channel member lookup is unavailable (requires backend support)",
    "chat.who.fetching": "Fetching online members of channel &e{0}&7...",
    "chat.who.list_header": "&6Channel &e{0} &6online members &7({1}):",
    "chat.who.list_body": "&7{0}",
    "chat.who.list_empty": "&7No members online",
    "chat.who.no_channel": "You are not in any channel; please specify a channel id",
    "chat.who.specify_channel": "Please specify a channel id",

    # chat.welcome.*
    "chat.welcome.line": "&6Welcome!&rType &e/nc help &rto view chat channels, &e/nc list &rto list available channels",

    # chat.debug.*
    "chat.debug.enabled": "Debug mode &aenabled",
    "chat.debug.disabled": "Debug mode &cdisabled",
    "chat.debug.connected": "&7Connected: &e{0}",
    "chat.debug.authenticated": "&7Authenticated: &e{0}",

    # error.suggestion_prefix
    "error.suggestion_prefix": "Suggestion:",

    # error.NC-*
    "error.NC-400.message": "Bad request",
    "error.NC-400.suggestion": "Please check the command arguments",
    "error.NC-401.message": "Authentication failed",
    "error.NC-401.suggestion": "Please check your username and password",
    "error.NC-403.message": "Permission denied",
    "error.NC-403.suggestion": "You do not have permission to do this; contact an admin",
    "error.NC-404.message": "Not found",
    "error.NC-404.suggestion": "Please check the channel id or player name",
    "error.NC-409.message": "Conflict",
    "error.NC-409.suggestion": "This resource already exists or is in use",
    "error.NC-410.message": "Invite code expired",
    "error.NC-410.suggestion": "Contact the channel admin for a new invite code",
    "error.NC-411.message": "Invite code already used",
    "error.NC-411.suggestion": "Each invite code can only be used once",
    "error.NC-429.message": "Too many requests",
    "error.NC-429.suggestion": "Please try again later",
    "error.NC-430.message": "Invalid format",
    "error.NC-430.suggestion": "Please check your input format",
    "error.NC-431.message": "Channel full",
    "error.NC-431.suggestion": "This channel has reached its capacity",
    "error.NC-432.message": "Already joined",
    "error.NC-432.suggestion": "You are already in this channel",
    "error.NC-433.message": "Not in this channel",
    "error.NC-433.suggestion": "You need to join this channel first",
    "error.NC-434.message": "Wrong password",
    "error.NC-434.suggestion": "Please check the channel password",
    "error.NC-435.message": "World restricted",
    "error.NC-435.suggestion": "This channel is only available in specific worlds",
    "error.NC-436.message": "You have been muted",
    "error.NC-436.suggestion": "You cannot send messages while muted",
    "error.NC-437.message": "Cannot target yourself",
    "error.NC-437.suggestion": "Please choose another player",
    "error.NC-438.message": "Target player is offline",
    "error.NC-438.suggestion": "Please confirm the player is online and try again",
    "error.NC-439.message": "Invalid duration format",
    "error.NC-439.suggestion": "Please use a valid format, e.g. 1h, 30m, 1d",
    "error.NC-500.message": "Internal server error",
    "error.NC-500.suggestion": "Please contact an admin to check the server logs",
    "error.NC-501.message": "Not implemented",
    "error.NC-501.suggestion": "This feature is not yet available",
    "error.NC-502.message": "Bad gateway",
    "error.NC-502.suggestion": "Please check that the backend service is running",
    "error.NC-503.message": "Service unavailable",
    "error.NC-503.suggestion": "Not connected to the backend server; please try again later",
    "error.NC-504.message": "Gateway timeout",
    "error.NC-504.suggestion": "The backend response timed out; please try again later",
    "error.NC-510.message": "Database error",
    "error.NC-510.suggestion": "A data storage problem occurred; please contact an admin",
    "error.NC-511.message": "Config error",
    "error.NC-511.suggestion": "Please check that the config file is correct",

    # kick/mute target notice fallbacks
    "notice.operator.fallback": "An admin",
    "notice.duration.unknown": "a while",

    # duration.*
    "duration.seconds": "{0}s",
    "duration.minutes": "{0}m",
    "duration.hours": "{0}h",
    "duration.days": "{0}d",
}
