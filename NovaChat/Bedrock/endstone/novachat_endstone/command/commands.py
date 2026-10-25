"""
NovaChat command implementation.

This module contains the command handler for all NovaChat commands
including help, join, leave, list, who, toggle, reload, debug, auth,
announce, and the moderation set (mute/kick/ban/unban/title). The
subcommand set is aligned with the Java server-side platforms, with i18n
message copy keyed on the same client-core bundle keys.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import re
import uuid
from typing import TYPE_CHECKING, List, Optional, Any

from novachat_endstone.protocol.packet import (
    AdminAction,
    AdminActionPacket,
    ChannelAction,
    ChannelActionPacket,
)

if TYPE_CHECKING:
    from novachat_endstone.plugin import NovaChatPlugin


# Console/RCON has no player UUID. The backend treats the all-zeros UUID as
# console authority (ConsoleSentinel bypasses moderation permission checks and
# is the identity the game console layer registers as a super admin), and every
# Java platform pairs it with extra["console"]="true".
CONSOLE_SENTINEL_UUID = "00000000-0000-0000-0000-000000000000"

# Java BanCommand.isDuration: value.matches("\\d+[smhdw]?"). A bare number is
# a duration (seconds), a "w" suffix routes to the duration slot even though
# parse_duration does not understand weeks.
_DURATION_RE = re.compile(r"\d+[smhdw]?")


def is_duration(value: str) -> bool:
    """Whether a ban argument looks like a duration rather than a channel id.

    Mirrors bukkit BanCommand.isDuration (``\\d+[smhdw]?``). Note "w" is
    accepted here but not by :func:`parse_duration`, exactly as in Java: the
    argument is routed to the duration slot and then rejected as invalid.
    Anything else (e.g. "10x") is treated as a channel id, so /nc ban puts it
    in the channel slot rather than complaining about the duration.
    """
    return _DURATION_RE.fullmatch(value or "") is not None


def parse_duration(value: str) -> int:
    """Parse a duration string (e.g. "1h", "30m", "1d") to seconds.

    Port of the Java AbstractSubCommand.parseDuration contract: the trailing
    character selects the unit, anything else falls back to parsing the whole
    string as seconds. Returns -1 when unparseable, which every caller treats
    as invalid (durations must also be > 0).

    Deliberate quirks kept from Java because callers rely on them: a bare "0"
    yields -1 (the numeric part is the empty string), and a negative value
    such as "-5m" parses to a negative number that the ``> 0`` gate rejects.
    Letters outside s/m/h/d (e.g. "10w") also yield -1 — /nc ban routes them
    to the duration slot via :func:`is_duration` and then rejects them.
    """
    if not value:
        return -1
    try:
        number = int(value[:-1])
    except ValueError:
        return -1
    unit = value[-1].lower()
    if unit == "s":
        return number
    if unit == "m":
        return number * 60
    if unit == "h":
        return number * 3600
    if unit == "d":
        return number * 86400
    # Pure seconds (the unit char was actually the last digit).
    try:
        return int(value)
    except ValueError:
        return -1


class NovaChatCommand:
    """
    Handler for NovaChat commands.

    Implements the NovaChat subcommands aligned with the Java server-side
    platforms:
    - help: Show command help
    - join: Join a channel
    - leave: Leave current channel
    - list: List available channels (from backend ConfigSync)
    - who: Query online members of a channel (WHO action)
    - toggle: Toggle chat mode
    - reload: Reload configuration (admin)
    - debug: Toggle debug mode (admin)
    - auth: Establish a backend super-admin session (player-only, hidden)
    - announce/title: AdminAction STATUS broadcasts (require that session)
    - mute/kick/ban/unban: ChannelAction moderation
    """

    # The core subcommands aligned with the Java platforms. ``auth`` and
    # ``announce`` (FEATURE-002) supersede the dead 0x0A announcement path:
    # announcements are sent as AdminAction STATUS type=ANNOUNCE, gated by the
    # backend super-admin session (NC-403 when absent).
    SUBCOMMANDS = [
        "help", "join", "leave", "list", "who", "toggle", "reload", "auth",
        "announce", "title", "mute", "kick", "ban", "unban",
    ]
    ADMIN_SUBCOMMANDS = ["reload", "debug"]

    # Local permission node checked by each moderation command. The nodes
    # match the Java platforms, and so does the caveat: this is only a coarse
    # client-side gate, and the backend re-checks (PermissionManager for
    # ChannelAction, super-admin session for STATUS).
    #
    # ``announce`` is deliberately absent: it predates this module and ships
    # without a local node, deferring entirely to the backend super-admin
    # session — a strictly stronger gate than novachat.announce. Adding the
    # local check here would change the behaviour of an existing command.
    MODERATION_PERMISSIONS = {
        "mute": "novachat.mute",
        "kick": "novachat.kick",
        "ban": "novachat.ban",
        "unban": "novachat.ban",
        "title": "novachat.title",
    }

    def __init__(self, plugin: "NovaChatPlugin"):
        """
        Initialize the command handler.

        Args:
            plugin: The parent plugin instance
        """
        self._plugin = plugin
        self._logger = logging.getLogger("NovaChat.Command")

    def on_command(
        self,
        sender: Any,
        command: Any,
        label: str,
        args: List[str]
    ) -> bool:
        """
        Handle NovaChat commands.

        Args:
            sender: The command sender (player or console)
            command: The command object
            label: The command label used (novachat or nc)
            args: Command arguments

        Returns:
            True if command was handled successfully
        """
        try:
            if not args:
                return self._cmd_help(sender)

            subcommand = args[0].lower()
            sub_args = args[1:]

            # Map subcommands to handlers
            handlers = {
                "help": lambda: self._cmd_help(sender),
                "join": lambda: self._cmd_join(sender, sub_args),
                "leave": lambda: self._cmd_leave(sender),
                "list": lambda: self._cmd_list(sender),
                "who": lambda: self._cmd_who(sender, sub_args),
                "toggle": lambda: self._cmd_toggle(sender),
                "reload": lambda: self._cmd_reload(sender),
                "debug": lambda: self._cmd_debug(sender),
                "auth": lambda: self._cmd_auth(sender, sub_args),
                "announce": lambda: self._cmd_announce(sender, sub_args),
                "title": lambda: self._cmd_title(sender, sub_args),
                "mute": lambda: self._cmd_mute(sender, sub_args),
                "kick": lambda: self._cmd_kick(sender, sub_args),
                "ban": lambda: self._cmd_ban(sender, sub_args),
                "unban": lambda: self._cmd_unban(sender, sub_args),
            }

            handler = handlers.get(subcommand)
            if handler:
                return handler()
            else:
                self._i18n_send(sender, "chat.command.unknown", subcommand)
                return True

        except Exception as e:
            self._logger.error(f"Error executing command: {e}")
            self._send_message(sender, "§c执行命令时发生错误。")
            return True
    
    def _send_message(self, sender: Any, message: str) -> None:
        """
        Send a message to a command sender.

        Args:
            sender: The command sender
            message: The message to send
        """
        try:
            sender.send_message(message)
        except Exception as e:
            self._logger.error(f"Failed to send message: {e}")

    def _get_locale(self, sender: Any) -> str:
        """Resolve the sender's locale (zh_CN default; en_US if player locale starts with en)."""
        chat_handler = self._plugin.chat_handler
        if chat_handler and self._is_player(sender):
            try:
                return chat_handler.get_player_locale(str(sender.unique_id))
            except Exception:
                pass
        return "zh_CN"

    def _i18n_send(self, sender: Any, key: str, *args) -> None:
        """Send an i18n-localized message to a command sender."""
        from novachat_endstone.i18n import I18n
        locale = self._get_locale(sender)
        i18n = I18n()
        self._send_message(sender, i18n.get(key, locale, *args))
    
    def _is_player(self, sender: Any) -> bool:
        """
        Check if the sender is a player.
        
        Args:
            sender: The command sender
            
        Returns:
            True if sender is a player
        """
        try:
            # Check if sender has player-specific attributes
            return hasattr(sender, 'unique_id') and hasattr(sender, 'name')
        except Exception:
            return False
    
    def _has_permission(self, sender: Any, permission: str) -> bool:
        """
        Check if sender has a permission.

        Args:
            sender: The command sender
            permission: The permission to check

        Returns:
            True if sender has the permission
        """
        try:
            return sender.has_permission(permission)
        except Exception:
            return False

    # ------------------------------------------------------------------
    # Moderation plumbing (mute / kick / ban / unban / title).
    #
    # Packet shapes and extra names are copied from the Java platforms
    # (bukkit/folia MuteCommand..UnbanCommand), which are the reference
    # implementation the backend was written against. Do not invent extras:
    # StarLink's ChannelActionHandler.resolveOperatorId reads operatorId and
    # resolveTargetId reads targetId then targetName, and handleMute/Ban read
    # duration in SECONDS.
    # ------------------------------------------------------------------

    @staticmethod
    def _sender_name(sender: Any) -> str:
        """Display name for operatorName; console falls back to "console"."""
        return getattr(sender, "name", None) or "console"

    def _operator_extras(self, sender: Any) -> dict:
        """Operator identity extras, matching the Java console/player split.

        Console/RCON carries the all-zeros sentinel + console=true; the
        backend's ConsoleSentinel short-circuits moderation permission checks
        for it.
        """
        if self._is_player(sender):
            return {"operatorId": str(sender.unique_id)}
        return {"operatorId": CONSOLE_SENTINEL_UUID, "console": "true"}

    def _resolve_target_id(self, target_name: str) -> Optional[str]:
        """Resolve a locally-online player name to a UUID string, else None.

        A None result does not abort the action: the packet still carries
        targetName so the backend can resolve the player on another server
        (ChannelActionHandler.resolveTargetId falls back to a network-wide
        name lookup), exactly as the Java commands do.
        """
        server = getattr(self._plugin, "server", None)
        if server is None:
            return None
        try:
            player = server.get_player(target_name)
            if player is not None:
                return str(player.unique_id)
        except Exception:
            pass
        try:
            wanted = target_name.lower()
            for player in server.online_players:
                if str(player.name).lower() == wanted:
                    return str(player.unique_id)
        except Exception:
            pass
        return None

    def _current_channel(self, sender: Any) -> str:
        """The sender's active channel, or "" when it cannot be determined.

        Only players have an active channel; console must name one explicitly.
        """
        if not self._is_player(sender):
            return ""
        chat_handler = self._plugin.chat_handler
        if chat_handler is None:
            return ""
        try:
            return chat_handler.get_player_channel(str(sender.unique_id)) or ""
        except Exception:
            return ""

    def _format_duration(self, seconds: int, locale: str) -> str:
        """Short localized duration, mirroring Java DurationFormatter."""
        from novachat_endstone.i18n import I18n

        i18n = I18n()
        if seconds <= 0:
            return i18n.get("notice.duration.unknown", locale)
        if seconds < 60:
            return i18n.get("duration.seconds", locale, seconds)
        if seconds < 3600:
            return i18n.get("duration.minutes", locale, seconds // 60)
        if seconds < 86400:
            return i18n.get("duration.hours", locale, seconds // 3600)
        return i18n.get("duration.days", locale, seconds // 86400)

    def _connected(self) -> bool:
        """Whether the backend connection is up and authenticated."""
        client = self._plugin.network_client
        return bool(client is not None and client.is_connected)

    def _send_channel_action(
        self,
        sender: Any,
        packet: ChannelActionPacket,
        locale: str,
        progress_key: str,
        *args,
    ) -> None:
        """Schedule a ChannelAction send and show a progress line.

        The response is correlated by request_id through the chat handler, so
        the eventual ChannelActionResponse (success or a coded failure) is
        delivered to the originating player. Console-originated sends register
        the sentinel and the handler logs the outcome instead.
        """
        loop = self._plugin.network_client.loop
        if loop is None:
            self._send_message(sender, "§c网络不可用，请稍后重试。")
            return

        if self._is_player(sender):
            player_uuid = str(sender.unique_id)
        else:
            player_uuid = CONSOLE_SENTINEL_UUID

        asyncio.run_coroutine_threadsafe(
            self._send_channel_action_async(packet, player_uuid),
            loop,
        )
        self._i18n_send(sender, progress_key, *args)

    async def _send_channel_action_async(
        self, packet: ChannelActionPacket, player_uuid: str
    ) -> None:
        """Send a ChannelAction packet and register its request_id as pending."""
        try:
            await self._plugin.network_client.send_packet(packet)
            request_id = str(getattr(packet, "request_id", ""))
            chat_handler = self._plugin.chat_handler
            if chat_handler:
                chat_handler.register_pending_channel_action(request_id, player_uuid)
        except Exception as e:
            self._logger.error(f"Failed to send channel action packet: {e}")
    
    def _cmd_help(self, sender: Any) -> bool:
        """
        Show help message (i18n).

        Args:
            sender: The command sender

        Returns:
            True
        """
        self._i18n_send(sender, "chat.command.help.title")
        self._i18n_send(sender, "chat.command.help.line_help")
        self._i18n_send(sender, "chat.command.help.line_join")
        self._i18n_send(sender, "chat.command.help.line_leave")
        self._i18n_send(sender, "chat.command.help.line_list")
        self._i18n_send(sender, "chat.command.help.line_who")
        self._i18n_send(sender, "chat.command.help.line_toggle")
        self._i18n_send(sender, "chat.command.help.line_announce")

        # Moderation lines are shown only to senders holding the matching
        # node, mirroring the Java HelpCommand (which iterates every
        # subcommand and filters on hasPermission).
        for subcommand in ("title", "mute", "kick", "ban", "unban"):
            if self._has_permission(sender, self.MODERATION_PERMISSIONS[subcommand]):
                self._i18n_send(sender, f"chat.command.help.line_{subcommand}")

        if self._has_permission(sender, "novachat.admin"):
            self._i18n_send(sender, "chat.command.help.line_reload")
            self._i18n_send(sender, "chat.command.help.line_debug")
            # auth is admin-only and hidden from non-admin help, mirroring
            # bukkit AuthCommand.isHidden(). The real authz gate is the
            # backend super-admin session, surfaced via NC-403.
            self._i18n_send(sender, "chat.command.help.line_auth")

        return True

    def _cmd_join(self, sender: Any, args: List[str]) -> bool:
        """
        Join a channel.

        Args:
            sender: The command sender
            args: Command arguments [channel_id, optional_password]

        Returns:
            True
        """
        if not self._is_player(sender):
            self._i18n_send(sender, "chat.command.player_only")
            return True

        if not args:
            self._i18n_send(sender, "chat.command.usage.join")
            return True

        channel_id = args[0]
        password = args[1] if len(args) > 1 else ""

        # Update player's channel
        chat_handler = self._plugin.chat_handler
        if chat_handler:
            # Use async join if connected to backend.
            #
            # run_coroutine_threadsafe on the network client's loop, NOT
            # asyncio.create_task: command handlers run on BDS's native command
            # thread, where no asyncio loop is running, so create_task raises
            # "RuntimeError: no running event loop" and the join silently never
            # happens. Same bug class as on_enable, fixed the same way.
            loop = self._plugin.network_client.loop if self._plugin.network_client else None
            if loop is not None and self._plugin.network_client.is_connected:
                asyncio.run_coroutine_threadsafe(
                    chat_handler.join_channel(str(sender.unique_id), channel_id, password),
                    loop,
                )
                self._i18n_send(sender, "chat.join.joining", channel_id)
            else:
                # Fallback to local channel switch
                chat_handler.set_player_channel(str(sender.unique_id), channel_id)
                self._i18n_send(sender, "chat.join.joined", channel_id)
        else:
            self._send_message(sender, "§c聊天系统未初始化。")

        return True

    def _cmd_leave(self, sender: Any) -> bool:
        """
        Leave current channel and return to default.

        Args:
            sender: The command sender

        Returns:
            True
        """
        if not self._is_player(sender):
            self._i18n_send(sender, "chat.command.player_only")
            return True

        chat_handler = self._plugin.chat_handler
        config_manager = self._plugin.config_manager

        if chat_handler and config_manager:
            # Get current channel before leaving
            current_channel = chat_handler.get_player_channel(str(sender.unique_id))
            default_channel = config_manager.default_channel

            if current_channel == default_channel:
                self._i18n_send(sender, "chat.action.already_default")
                return True

            # Use async leave if connected to backend. See _cmd_join for why this
            # is run_coroutine_threadsafe and not asyncio.create_task.
            loop = self._plugin.network_client.loop if self._plugin.network_client else None
            if loop is not None and self._plugin.network_client.is_connected:
                asyncio.run_coroutine_threadsafe(
                    chat_handler.leave_channel(str(sender.unique_id)),
                    loop,
                )
                self._i18n_send(sender, "chat.leave.leaving", current_channel)
            else:
                # Fallback to local channel switch
                chat_handler.set_player_channel(str(sender.unique_id), default_channel)
                self._i18n_send(sender, "chat.leave.left", current_channel, default_channel)
        else:
            self._send_message(sender, "§c聊天系统未初始化。")

        return True

    def _cmd_list(self, sender: Any) -> bool:
        """
        List available channels (from backend ConfigSync known channel registry).

        Args:
            sender: The command sender

        Returns:
            True
        """
        chat_handler = self._plugin.chat_handler
        if not chat_handler:
            return True

        known = chat_handler.get_known_channels()
        if not known:
            self._i18n_send(sender, "chat.list.empty")
            return True

        self._i18n_send(sender, "chat.command.list.title")
        for channel_id in known:
            self._send_message(sender, f"§7- §e{channel_id}")
        self._i18n_send(sender, "chat.command.list.tail")
        return True

    def _cmd_who(self, sender: Any, args: List[str]) -> bool:
        """
        Query the online members of a channel (WHO action).

        Args:
            sender: The command sender
            args: Command arguments [optional channel_id]

        Returns:
            True
        """
        if not self._is_player(sender):
            self._i18n_send(sender, "chat.command.player_only")
            return True

        channel_id = args[0] if args else ""
        if not channel_id:
            # Use current channel if available
            chat_handler = self._plugin.chat_handler
            if chat_handler:
                channel_id = chat_handler.get_player_channel(str(sender.unique_id))
            if not channel_id:
                self._i18n_send(sender, "chat.who.no_channel")
                return True

        if self._plugin.network_client and self._plugin.network_client.is_connected:
            chat_handler = self._plugin.chat_handler
            if chat_handler:
                # See _cmd_join: create_task would raise on the command thread.
                loop = self._plugin.network_client.loop
                if loop is not None:
                    asyncio.run_coroutine_threadsafe(
                        chat_handler.who_channel(str(sender.unique_id), channel_id),
                        loop,
                    )
                    self._i18n_send(sender, "chat.who.fetching", channel_id)
        else:
            self._i18n_send(sender, "chat.network.not_connected_retry")
        return True
    
    def _cmd_toggle(self, sender: Any) -> bool:
        """
        Toggle NovaChat for the player.

        Args:
            sender: The command sender

        Returns:
            True
        """
        if not self._is_player(sender):
            self._i18n_send(sender, "chat.command.player_only")
            return True

        chat_handler = self._plugin.chat_handler
        if chat_handler:
            enabled = chat_handler.toggle_chat(str(sender.unique_id))
            self._i18n_send(
                sender,
                "chat.command.toggle.switched",
                "on" if enabled else "off",
            )
        else:
            self._send_message(sender, "§c聊天系统未初始化。")

        return True

    def _cmd_reload(self, sender: Any) -> bool:
        """
        Reload configuration.

        Args:
            sender: The command sender

        Returns:
            True
        """
        if not self._has_permission(sender, "novachat.admin"):
            self._i18n_send(sender, "chat.command.no_permission_code")
            return True

        try:
            self._plugin.reload_config()
            self._i18n_send(sender, "chat.command.reload.success")
        except Exception as e:
            self._logger.error(f"Failed to reload config: {e}")
            self._send_message(sender, "§c重新加载配置失败。")

        return True
    
    def _cmd_debug(self, sender: Any) -> bool:
        """
        Toggle debug mode.

        Args:
            sender: The command sender

        Returns:
            True
        """
        if not self._has_permission(sender, "novachat.admin"):
            self._i18n_send(sender, "chat.command.no_permission_code")
            return True

        config_manager = self._plugin.config_manager
        if config_manager:
            config_manager.debug = not config_manager.debug
            if config_manager.debug:
                self._i18n_send(sender, "chat.debug.enabled")
            else:
                self._i18n_send(sender, "chat.debug.disabled")

            # Update logging level
            if config_manager.debug:
                logging.getLogger("NovaChat").setLevel(logging.DEBUG)
            else:
                logging.getLogger("NovaChat").setLevel(logging.INFO)
        else:
            self._send_message(sender, "§c配置系统未初始化。")

        return True

    def _cmd_auth(self, sender: Any, args: List[str]) -> bool:
        """
        Super-admin authentication (FEATURE-002).

        Sends an AdminAction AUTH packet carrying a SHA-256 lowercase-hex hash
        of the password plus a ``playerName`` extra. The backend is the single
        source of truth for super-admin session state; this command only kicks
        off the request. The outcome (success / NC-403 / other error) is
        surfaced asynchronously via AdminActionResponsePacket, correlated by
        request_id. A PROGRESS message is shown immediately; we never claim
        success before the backend replies.

        Player-only: the backend super-admin session is keyed on a player UUID,
        so console/RCON cannot authenticate.

        Args:
            sender: The command sender (must be a player)
            args: Command arguments [password]

        Returns:
            True
        """
        if not self._is_player(sender):
            self._i18n_send(sender, "chat.command.player_only")
            return True

        if not args:
            self._i18n_send(sender, "chat.command.usage.auth")
            return True

        if not self._plugin.network_client or not self._plugin.network_client.is_connected:
            self._i18n_send(sender, "chat.network.not_connected_retry")
            return True

        password = args[0]
        # SHA-256 lowercase hex, matching bukkit AuthCommand.hashPassword.
        password_hash = hashlib.sha256(password.encode("utf-8")).hexdigest()
        player_uuid = str(sender.unique_id)

        from novachat_endstone.protocol.packet import (
            AdminActionPacket,
            AdminAction,
        )
        packet = AdminActionPacket(
            action=AdminAction.AUTH,
            player_id=sender.unique_id,
            password_hash=password_hash,
            target="",
            extra={"playerName": sender.name},
        )

        loop = self._plugin.network_client.loop
        if loop is None:
            self._send_message(sender, "§c网络不可用，请稍后重试。")
            return True

        # Register the pending request so the async AdminActionResponse can be
        # routed back to this player by request_id. send_packet assigns the
        # request_id onto the packet object; we capture it afterwards.
        asyncio.run_coroutine_threadsafe(
            self._send_admin_action(packet, player_uuid),
            loop,
        )

        self._i18n_send(sender, "chat.auth.progress")
        return True

    async def _send_admin_action(self, packet: Any, player_uuid: str) -> None:
        """Send an AdminAction packet and register its request_id as pending.

        ``NetworkClient.send_packet`` assigns a fresh request_id onto the
        packet object (uuid.UUID) before writing it to the wire. We capture
        that id after send and register it against the originating player so
        the async AdminActionResponse can be routed back. Registering AFTER
        send is safe: the backend response arrives on a later loop tick.
        """
        try:
            await self._plugin.network_client.send_packet(packet)
            request_id = str(getattr(packet, "request_id", ""))
            chat_handler = self._plugin.chat_handler
            if chat_handler:
                chat_handler.register_pending_admin_action(request_id, player_uuid)
        except Exception as e:
            self._logger.error(f"Failed to send admin action packet: {e}")

    def _cmd_announce(self, sender: Any, args: List[str]) -> bool:
        """
        Broadcast an announcement to a channel (FEATURE-002).

        Sends an AdminAction STATUS packet with type=ANNOUNCE extras
        (operatorName, content). The backend gates STATUS/ANNOUNCE behind
        ``permissionManager.hasSuperAdminSession``; on NC-403 the async
        response handler surfaces the "run /nc auth" guidance. A PROGRESS
        message is shown immediately; we never claim success before the
        backend replies.

        Console/RCON may also announce using the all-zeros sentinel UUID so
        the backend can route the broadcast.

        Args:
            sender: The command sender (player or console)
            args: Command arguments [channel_id, content...]

        Returns:
            True
        """
        if len(args) < 2:
            self._i18n_send(sender, "chat.announce.usage")
            return True

        if not self._plugin.network_client or not self._plugin.network_client.is_connected:
            self._i18n_send(sender, "chat.network.not_connected_retry")
            return True

        channel_id = args[0]
        content = " ".join(args[1:])
        if not content:
            self._i18n_send(sender, "chat.announce.usage")
            return True

        # Player UUID, or the all-zeros console sentinel for console/RCON.
        if self._is_player(sender):
            player_id = sender.unique_id
            player_uuid = str(sender.unique_id)
        else:
            import uuid as _uuid
            player_id = _uuid.UUID("00000000-0000-0000-0000-000000000000")
            player_uuid = "00000000-0000-0000-0000-000000000000"

        from novachat_endstone.protocol.packet import (
            AdminActionPacket,
            AdminAction,
        )
        packet = AdminActionPacket(
            action=AdminAction.STATUS,
            player_id=player_id,
            password_hash="",
            target=channel_id,
            extra={
                "type": "ANNOUNCE",
                "operatorName": getattr(sender, "name", "console"),
                "content": content,
            },
        )

        loop = self._plugin.network_client.loop
        if loop is None:
            self._send_message(sender, "§c网络不可用，请稍后重试。")
            return True

        asyncio.run_coroutine_threadsafe(
            self._send_admin_action(packet, player_uuid),
            loop,
        )

        self._i18n_send(sender, "chat.announce.progress", channel_id)
        return True

    # ------------------------------------------------------------------
    # Moderation commands. Packet shapes and validation order mirror the
    # bukkit/folia implementations one-for-one; the backend is the authority
    # and re-checks every action.
    # ------------------------------------------------------------------

    def _cmd_title(self, sender: Any, args: List[str]) -> bool:
        """
        Send a title to every player in a channel.

        An AdminAction STATUS packet with type=TITLE (the TITLE subtype reuses
        STATUS for transport; the backend dispatches on the ``type`` extra).
        Like ANNOUNCE this is gated by the backend super-admin session and
        answers NC-403 until the sender has run /nc auth.

        Args:
            sender: The command sender (player or console)
            args: Command arguments [channel_id, title, subtitle...]

        Returns:
            True
        """
        if not self._has_permission(sender, self.MODERATION_PERMISSIONS["title"]):
            self._i18n_send(sender, "chat.command.no_permission")
            return True

        if len(args) < 2:
            self._i18n_send(sender, "chat.command.usage.title")
            self._i18n_send(sender, "chat.title.color_hint")
            return True

        if not self._connected():
            self._i18n_send(sender, "chat.network.not_connected_retry")
            return True

        channel_id = args[0]
        title = args[1]
        subtitle = " ".join(args[2:]) if len(args) > 2 else ""

        if self._is_player(sender):
            player_id = sender.unique_id
            player_uuid = str(sender.unique_id)
            extra = {
                "type": "TITLE",
                "operatorName": self._sender_name(sender),
                "title": title,
                "subtitle": subtitle,
            }
        else:
            player_id = uuid.UUID(CONSOLE_SENTINEL_UUID)
            player_uuid = CONSOLE_SENTINEL_UUID
            extra = {
                "type": "TITLE",
                "operatorName": self._sender_name(sender),
                "title": title,
                "subtitle": subtitle,
                "console": "true",
            }

        packet = AdminActionPacket(
            action=AdminAction.STATUS,
            player_id=player_id,
            password_hash="",
            target=channel_id,
            extra=extra,
        )

        loop = self._plugin.network_client.loop
        if loop is None:
            self._send_message(sender, "§c网络不可用，请稍后重试。")
            return True

        asyncio.run_coroutine_threadsafe(
            self._send_admin_action(packet, player_uuid),
            loop,
        )
        self._i18n_send(sender, "chat.title.progress", channel_id)
        return True

    def _cmd_mute(self, sender: Any, args: List[str]) -> bool:
        """
        Mute a player in a channel for a bounded duration.

        A MUTE ChannelAction. A channel is mandatory: an explicit third
        argument wins, otherwise the sender's active channel is used, and a
        console sender has no active channel so it must name one. The backend
        rejects a blank channel id too, so this is validation, not policy.

        Args:
            sender: The command sender (player or console)
            args: Command arguments [player, duration, optional channel_id]

        Returns:
            True
        """
        if not self._has_permission(sender, self.MODERATION_PERMISSIONS["mute"]):
            self._i18n_send(sender, "chat.command.no_permission")
            return True

        if len(args) < 2:
            self._i18n_send(sender, "chat.command.usage.mute")
            self._i18n_send(sender, "chat.mute.duration_hint")
            return True

        if not self._connected():
            self._i18n_send(sender, "chat.network.not_connected_retry")
            return True

        target_name = args[0]
        duration_seconds = parse_duration(args[1])
        if duration_seconds <= 0:
            self._i18n_send(sender, "chat.mute.invalid_duration", args[1])
            self._i18n_send(sender, "chat.mute.duration_hint")
            return True

        channel_id = args[2] if len(args) > 2 else self._current_channel(sender)
        if not channel_id:
            self._i18n_send(sender, "chat.command.specify_channel")
            return True

        extra = self._operator_extras(sender)
        extra["operatorName"] = self._sender_name(sender)
        target_id = self._resolve_target_id(target_name)
        if target_id:
            extra["targetId"] = target_id
        extra["targetName"] = target_name
        extra["duration"] = str(duration_seconds)

        packet = ChannelActionPacket(
            action=ChannelAction.MUTE,
            channel_id=channel_id,
            password="",
            extra=extra,
        )
        self._send_channel_action(
            sender,
            packet,
            self._get_locale(sender),
            "chat.mute.progress",
            target_name,
            channel_id,
            self._format_duration(duration_seconds, self._get_locale(sender)),
        )
        return True

    def _cmd_kick(self, sender: Any, args: List[str]) -> bool:
        """
        Kick a player out of a channel.

        A KICK ChannelAction. The channel is inferred from the sender's active
        channel when omitted (a kick is inherently per-channel); console must
        name one.

        Args:
            sender: The command sender (player or console)
            args: Command arguments [player, optional channel_id]

        Returns:
            True
        """
        if not self._has_permission(sender, self.MODERATION_PERMISSIONS["kick"]):
            self._i18n_send(sender, "chat.command.no_permission")
            return True

        if not args:
            self._i18n_send(sender, "chat.command.usage.kick")
            return True

        if not self._connected():
            self._i18n_send(sender, "chat.network.not_connected_retry")
            return True

        target_name = args[0]
        channel_id = args[1] if len(args) > 1 else self._current_channel(sender)
        if not channel_id:
            self._i18n_send(sender, "chat.command.specify_channel")
            return True

        extra = self._operator_extras(sender)
        extra["operatorName"] = self._sender_name(sender)
        target_id = self._resolve_target_id(target_name)
        if target_id:
            extra["targetId"] = target_id
        extra["targetName"] = target_name

        packet = ChannelActionPacket(
            action=ChannelAction.KICK,
            channel_id=channel_id,
            password="",
            extra=extra,
        )
        self._send_channel_action(
            sender, packet, self._get_locale(sender),
            "chat.kick.progress", target_name, channel_id,
        )
        return True

    def _cmd_ban(self, sender: Any, args: List[str]) -> bool:
        """
        Ban a player, optionally scoped to a channel and/or a duration.

        A BAN ChannelAction. An omitted channel is a NETWORK-WIDE ban: unlike
        kick/mute there is deliberately no fallback to the sender's active
        channel, because silently narrowing a global ban would be worse than
        making the operator name the channel. The duration and channel
        arguments may come in either order, decided by :func:`is_duration`.

        Args:
            sender: The command sender (player or console)
            args: Command arguments [player, duration|channel, channel|duration]

        Returns:
            True
        """
        if not self._has_permission(sender, self.MODERATION_PERMISSIONS["ban"]):
            self._i18n_send(sender, "chat.command.no_permission")
            return True

        if not args:
            self._i18n_send(sender, "chat.command.usage.ban")
            self._i18n_send(sender, "chat.ban.duration_hint")
            return True

        if not self._connected():
            self._i18n_send(sender, "chat.network.not_connected_retry")
            return True

        target_name = args[0]
        channel_id = ""
        duration = None
        if len(args) > 1:
            if is_duration(args[1]):
                duration = args[1]
                if len(args) > 2:
                    channel_id = args[2]
            else:
                channel_id = args[1]
                if len(args) > 2:
                    duration = args[2]

        duration_seconds = 0
        if duration is not None:
            duration_seconds = parse_duration(duration)
            if duration_seconds <= 0:
                self._i18n_send(sender, "chat.ban.invalid_duration", duration)
                self._i18n_send(sender, "chat.ban.duration_hint")
                return True

        extra = self._operator_extras(sender)
        extra["operatorName"] = self._sender_name(sender)
        target_id = self._resolve_target_id(target_name)
        if target_id:
            extra["targetId"] = target_id
        extra["targetName"] = target_name
        # Omitted duration = permanent, signalled by the extra being absent.
        if duration_seconds > 0:
            extra["duration"] = str(duration_seconds)

        packet = ChannelActionPacket(
            action=ChannelAction.BAN,
            channel_id=channel_id,
            password="",
            extra=extra,
        )
        self._send_channel_action(
            sender, packet, self._get_locale(sender),
            "chat.ban.progress", target_name, channel_id or "all channels",
        )
        return True

    def _cmd_unban(self, sender: Any, args: List[str]) -> bool:
        """
        Lift a ban, network-wide or for one channel.

        An UNBAN ChannelAction. The target is usually offline — that is the
        point of a ban — so a UUID is accepted directly and a bare name is
        still forwarded for the backend to resolve across servers. An omitted
        channel lifts the network-wide ban, matching /nc ban.

        Args:
            sender: The command sender (player or console)
            args: Command arguments [player|uuid, optional channel_id]

        Returns:
            True
        """
        if not self._has_permission(sender, self.MODERATION_PERMISSIONS["unban"]):
            self._i18n_send(sender, "chat.command.no_permission")
            return True

        if not args:
            self._i18n_send(sender, "chat.command.usage.unban")
            return True

        if not self._connected():
            self._i18n_send(sender, "chat.network.not_connected_retry")
            return True

        target_arg = args[0]
        channel_id = args[1] if len(args) > 1 else ""

        try:
            target_id = str(uuid.UUID(target_arg))
        except (ValueError, AttributeError, TypeError):
            target_id = self._resolve_target_id(target_arg)
            if target_id is None:
                # Still worth sending: the backend resolves names across all
                # connected servers. Only warn, matching Java UnbanCommand.
                self._i18n_send(sender, "chat.unban.offline_hint", target_arg)

        extra = self._operator_extras(sender)
        extra["operatorName"] = self._sender_name(sender)
        if target_id:
            extra["targetId"] = target_id
        extra["targetName"] = target_arg

        packet = ChannelActionPacket(
            action=ChannelAction.UNBAN,
            channel_id=channel_id,
            password="",
            extra=extra,
        )
        self._send_channel_action(
            sender, packet, self._get_locale(sender),
            "chat.unban.progress", target_arg, channel_id or "all channels",
        )
        return True

    def on_tab_complete(
        self,
        sender: Any,
        command: Any,
        alias: str,
        args: List[str]
    ) -> Optional[List[str]]:
        """
        Handle tab completion for NovaChat commands.

        Completes the subcommands at arg[0], channel names from the backend
        ConfigSync known channel registry where the next argument is a channel,
        and the fixed duration hints for mute/ban.

        Args:
            sender: The command sender
            command: The command object
            alias: The command alias used
            args: Current arguments

        Returns:
            List of completions or None
        """
        try:
            if len(args) == 1:
                # Complete subcommand
                subcommands = list(self.SUBCOMMANDS)
                if self._has_permission(sender, "novachat.admin"):
                    subcommands.extend(self.ADMIN_SUBCOMMANDS)

                prefix = args[0].lower()
                return [s for s in subcommands if s.startswith(prefix)]

            subcommand = args[0].lower()
            partial = args[-1]

            # Channel-first commands: <channel> is the next argument.
            if len(args) == 2 and subcommand in ("join", "who", "leave", "announce", "title"):
                return self._complete_channels(sender, partial)

            if len(args) == 2 and subcommand in ("mute", "kick", "ban", "unban"):
                return self._complete_players(sender, partial)

            # mute <player> <duration> [channel]
            if subcommand == "mute":
                if len(args) == 3:
                    return self._complete_durations(partial)
                if len(args) == 4:
                    return self._complete_channels(sender, partial)

            # kick/unban <player> <channel>
            if subcommand in ("kick", "unban") and len(args) == 3:
                return self._complete_channels(sender, partial)

            # ban <player> [duration|channel] [channel]: the second argument
            # is ambiguous, so offer both sets.
            if subcommand == "ban":
                if len(args) == 3:
                    return sorted(
                        set(self._complete_durations(partial))
                        | set(self._complete_channels(sender, partial))
                    )
                if len(args) == 4:
                    return self._complete_channels(sender, partial)

            return None

        except Exception as e:
            self._logger.error(f"Error in tab completion: {e}")
            return None

    def _complete_players(self, sender: Any, partial: str) -> List[str]:
        """Online player names for the target slot."""
        try:
            names = [str(p.name) for p in self._plugin.server.online_players]
        except Exception:
            return []
        prefix = partial.lower()
        return [n for n in names if n.lower().startswith(prefix)]

    @staticmethod
    def _complete_durations(partial: str) -> List[str]:
        """Duration hints for the mute/ban duration slot."""
        hints = ["30s", "10m", "1h", "1d"]
        return [h for h in hints if h.startswith(partial.lower())]

    def _complete_channels(self, sender: Any, partial: str) -> List[str]:
        """Channel-name completions from the ConfigSync registry + active channel."""
        chat_handler = self._plugin.chat_handler
        channels = list(chat_handler.get_known_channels()) if chat_handler else []
        if self._is_player(sender) and chat_handler:
            current = chat_handler.get_player_channel(str(sender.unique_id))
            if current and current not in channels:
                channels.append(current)
        prefix = partial.lower()
        return [c for c in channels if c.lower().startswith(prefix)]
