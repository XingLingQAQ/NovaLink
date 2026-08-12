"""
NovaChat command implementation.

This module contains the command handler for all NovaChat commands
including help, join, leave, list, who, toggle, reload, and debug.
The 7 subcommand set (help/join/leave/list/who/toggle/reload) is aligned
with the Java server-side platforms, with i18n message copy keyed on the
same client-core bundle keys.
"""

from __future__ import annotations

import asyncio
import logging
from typing import TYPE_CHECKING, List, Optional, Any

if TYPE_CHECKING:
    from novachat_endstone.plugin import NovaChatPlugin


class NovaChatCommand:
    """
    Handler for NovaChat commands.

    Implements the 7 standard NovaChat subcommands aligned with the Java
    server-side platforms:
    - help: Show command help
    - join: Join a channel
    - leave: Leave current channel
    - list: List available channels (from backend ConfigSync)
    - who: Query online members of a channel (WHO action)
    - toggle: Toggle chat mode
    - reload: Reload configuration (admin)
    - debug: Toggle debug mode (admin)
    """

    # The 7 core subcommands aligned with the Java platforms.
    SUBCOMMANDS = ["help", "join", "leave", "list", "who", "toggle", "reload"]
    ADMIN_SUBCOMMANDS = ["reload", "debug"]

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

        if self._has_permission(sender, "novachat.admin"):
            self._i18n_send(sender, "chat.command.help.line_reload")
            self._i18n_send(sender, "chat.command.help.line_debug")

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
            # Use async join if connected to backend
            if self._plugin.network_client and self._plugin.network_client.is_connected:
                asyncio.create_task(
                    chat_handler.join_channel(str(sender.unique_id), channel_id, password)
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

            # Use async leave if connected to backend
            if self._plugin.network_client and self._plugin.network_client.is_connected:
                asyncio.create_task(
                    chat_handler.leave_channel(str(sender.unique_id))
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
                asyncio.create_task(
                    chat_handler.who_channel(str(sender.unique_id), channel_id)
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

    def on_tab_complete(
        self,
        sender: Any,
        command: Any,
        alias: str,
        args: List[str]
    ) -> Optional[List[str]]:
        """
        Handle tab completion for NovaChat commands.

        Completes the 7 subcommands at arg[0], and channel names from the
        backend ConfigSync known channel registry at arg[1] for join/who/leave.

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

            elif len(args) == 2 and args[0].lower() in ("join", "who", "leave"):
                # Complete channel names from the known channel registry
                chat_handler = self._plugin.chat_handler
                channels = []
                if chat_handler:
                    channels = chat_handler.get_known_channels()
                # Also include the current channel as a convenience
                if self._is_player(sender) and chat_handler:
                    current = chat_handler.get_player_channel(str(sender.unique_id))
                    if current and current not in channels:
                        channels.append(current)
                prefix = args[1].lower()
                return [c for c in channels if c.lower().startswith(prefix)]

            return None

        except Exception as e:
            self._logger.error(f"Error in tab completion: {e}")
            return None
