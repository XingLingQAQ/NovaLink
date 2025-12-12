"""
NovaChat command implementation.

This module contains the command handler for all NovaChat commands
including help, join, leave, toggle, reload, and debug.

Requirements: 10.1 - THE NovaChat-Endstone SHALL 使用 Python 3.10+ 编写
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
    
    This class implements the command system for the Endstone plugin,
    providing all standard NovaChat commands:
    - help: Show command help
    - join: Join a channel
    - leave: Leave current channel
    - toggle: Toggle chat mode
    - reload: Reload configuration (admin)
    - debug: Toggle debug mode (admin)
    
    Validates: Requirements 10.1
    """
    
    # Available subcommands
    SUBCOMMANDS = ["help", "join", "leave", "toggle", "reload", "debug"]
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
        
        This is the main entry point for command execution, called by
        the Endstone command system when a player or console executes
        the /novachat or /nc command.
        
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
                "toggle": lambda: self._cmd_toggle(sender),
                "reload": lambda: self._cmd_reload(sender),
                "debug": lambda: self._cmd_debug(sender),
                "status": lambda: self._cmd_status(sender),
                "channel": lambda: self._cmd_channel(sender),
            }
            
            handler = handlers.get(subcommand)
            if handler:
                return handler()
            else:
                self._send_message(sender, "§c未知的子命令。使用 /nc help 查看帮助。")
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
        Show help message.
        
        Args:
            sender: The command sender
            
        Returns:
            True
        """
        self._send_message(sender, "§6=== NovaChat 帮助 ===")
        self._send_message(sender, "§e/nc help §7- 显示此帮助")
        self._send_message(sender, "§e/nc join <频道> §7- 加入频道")
        self._send_message(sender, "§e/nc leave §7- 离开当前频道")
        self._send_message(sender, "§e/nc toggle §7- 切换聊天模式")
        self._send_message(sender, "§e/nc status §7- 查看连接状态")
        self._send_message(sender, "§e/nc channel §7- 查看当前频道")
        
        if self._has_permission(sender, "novachat.admin"):
            self._send_message(sender, "§c/nc reload §7- 重新加载配置")
            self._send_message(sender, "§c/nc debug §7- 切换调试模式")
        
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
            self._send_message(sender, "§c此命令只能由玩家执行。")
            return True
        
        if not args:
            self._send_message(sender, "§c用法: /nc join <频道> [密码]")
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
            else:
                # Fallback to local channel switch
                chat_handler.set_player_channel(str(sender.unique_id), channel_id)
            
            self._send_message(sender, f"§a已加入频道: {channel_id}")
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
            self._send_message(sender, "§c此命令只能由玩家执行。")
            return True
        
        chat_handler = self._plugin.chat_handler
        config_manager = self._plugin.config_manager
        
        if chat_handler and config_manager:
            # Get current channel before leaving
            current_channel = chat_handler.get_player_channel(str(sender.unique_id))
            default_channel = config_manager.default_channel
            
            if current_channel == default_channel:
                self._send_message(sender, "§e你已经在默认频道中。")
                return True
            
            # Use async leave if connected to backend
            if self._plugin.network_client and self._plugin.network_client.is_connected:
                asyncio.create_task(
                    chat_handler.leave_channel(str(sender.unique_id))
                )
            else:
                # Fallback to local channel switch
                chat_handler.set_player_channel(str(sender.unique_id), default_channel)
            
            self._send_message(sender, f"§a已返回默认频道: {default_channel}")
        else:
            self._send_message(sender, "§c聊天系统未初始化。")
        
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
            self._send_message(sender, "§c此命令只能由玩家执行。")
            return True
        
        chat_handler = self._plugin.chat_handler
        if chat_handler:
            enabled = chat_handler.toggle_chat(str(sender.unique_id))
            status = "§a启用" if enabled else "§c禁用"
            self._send_message(sender, f"§eNovaChat 已{status}")
        else:
            self._send_message(sender, "§c聊天系统未初始化。")
        
        return True
    
    def _cmd_status(self, sender: Any) -> bool:
        """
        Show connection status.
        
        Args:
            sender: The command sender
            
        Returns:
            True
        """
        network_client = self._plugin.network_client
        
        if network_client:
            if network_client.is_connected:
                self._send_message(sender, "§a连接状态: 已连接")
            else:
                self._send_message(sender, "§c连接状态: 未连接")
        else:
            self._send_message(sender, "§c网络客户端未初始化。")
        
        return True
    
    def _cmd_channel(self, sender: Any) -> bool:
        """
        Show current channel.
        
        Args:
            sender: The command sender
            
        Returns:
            True
        """
        if not self._is_player(sender):
            self._send_message(sender, "§c此命令只能由玩家执行。")
            return True
        
        chat_handler = self._plugin.chat_handler
        if chat_handler:
            channel = chat_handler.get_player_channel(str(sender.unique_id))
            enabled = chat_handler.is_chat_enabled(str(sender.unique_id))
            status = "§a启用" if enabled else "§c禁用"
            self._send_message(sender, f"§e当前频道: §f{channel}")
            self._send_message(sender, f"§e聊天状态: {status}")
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
            self._send_message(sender, "§c你没有权限执行此命令。")
            return True
        
        try:
            self._plugin.reload_config()
            self._send_message(sender, "§a配置已重新加载。")
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
            self._send_message(sender, "§c你没有权限执行此命令。")
            return True
        
        config_manager = self._plugin.config_manager
        if config_manager:
            config_manager.debug = not config_manager.debug
            status = "§a启用" if config_manager.debug else "§c禁用"
            self._send_message(sender, f"§e调试模式已{status}")
            
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
                subcommands = ["help", "join", "leave", "toggle", "status", "channel"]
                if self._has_permission(sender, "novachat.admin"):
                    subcommands.extend(["reload", "debug"])
                
                prefix = args[0].lower()
                return [s for s in subcommands if s.startswith(prefix)]
            
            elif len(args) == 2 and args[0].lower() == "join":
                # Could provide channel suggestions here
                # For now, return empty list
                return []
            
            return None
            
        except Exception as e:
            self._logger.error(f"Error in tab completion: {e}")
            return None
