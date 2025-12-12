"""
Chat handler for intercepting and processing chat messages.

This module handles chat message interception, formatting,
and communication with the NovaLink backend.

Requirements: 10.4 - WHEN 玩家发送聊天消息 THEN NovaChat-Endstone SHALL 通过事件系统拦截消息
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from typing import TYPE_CHECKING, Dict, Optional, Any

from novachat_endstone.protocol.packet import (
    PacketIds,
    ChatMessagePacket,
    AnnouncementPacket,
    TitleMessagePacket,
    ChannelActionPacket,
)

if TYPE_CHECKING:
    from novachat_endstone.plugin import NovaChatPlugin
    from novachat_endstone.network.client import NetworkClient
    from novachat_endstone.config.manager import ConfigManager


class ChatHandler:
    """
    Handler for chat message interception and processing.
    
    This class implements the chat interceptor for Endstone, handling:
    - Player chat event interception
    - Message formatting and rendering
    - Communication with the NovaLink backend
    - Player channel state management
    
    Validates: Requirements 10.4
    """
    
    def __init__(
        self,
        plugin: "NovaChatPlugin",
        network_client: "NetworkClient",
        config_manager: "ConfigManager"
    ):
        """
        Initialize the chat handler.
        
        Args:
            plugin: The parent plugin instance
            network_client: The network client for backend communication
            config_manager: The configuration manager
        """
        self._plugin = plugin
        self._network_client = network_client
        self._config_manager = config_manager
        self._logger = logging.getLogger("NovaChat.Chat")
        
        # Player state tracking
        self._player_channels: Dict[str, str] = {}  # player_uuid -> channel_id
        self._chat_enabled: Dict[str, bool] = {}  # player_uuid -> enabled
        
        # Register packet handlers
        self._register_packet_handlers()
    
    def _register_packet_handlers(self) -> None:
        """Register handlers for incoming packets from the backend."""
        self._network_client.register_handler(
            PacketIds.CHAT_MESSAGE,
            self._handle_chat_message
        )
        self._network_client.register_handler(
            PacketIds.ANNOUNCEMENT,
            self._handle_announcement
        )
        self._network_client.register_handler(
            PacketIds.TITLE,
            self._handle_title_message
        )
    
    def on_player_chat(self, event: Any) -> None:
        """
        Handle player chat events.
        
        This method is called by the Endstone event system when a player
        sends a chat message. It intercepts the message and forwards it
        to the NovaLink backend.
        
        Args:
            event: The player chat event from Endstone
            
        Validates: Requirements 10.4
        """
        try:
            player = event.player
            message = event.message
            
            # Check if NovaChat is enabled for this player
            player_uuid = str(player.unique_id)
            if not self._chat_enabled.get(player_uuid, True):
                self._logger.debug(f"NovaChat disabled for player {player.name}")
                return
            
            # Check if connected to backend
            if not self._network_client.is_connected:
                self._logger.debug("Not connected to backend, using vanilla chat")
                return
            
            # Get player's current channel
            channel_id = self._player_channels.get(
                player_uuid,
                self._config_manager.default_channel
            )
            
            # Cancel vanilla chat if configured to replace it
            if self._config_manager.replace_vanilla:
                event.cancelled = True
            
            # Send message to backend asynchronously
            asyncio.create_task(self._send_chat_message(player, channel_id, message))
            
            self._logger.debug(
                f"Intercepted chat from {player.name} in channel {channel_id}: {message}"
            )
            
        except Exception as e:
            self._logger.error(f"Error handling player chat: {e}")
    
    async def _send_chat_message(
        self,
        player: Any,
        channel_id: str,
        message: str
    ) -> None:
        """
        Send a chat message to the backend.
        
        Args:
            player: The player sending the message
            channel_id: The target channel ID
            message: The message content
        """
        try:
            packet = ChatMessagePacket(
                sender_id=player.unique_id,
                sender_name=player.name,
                client_id=self._config_manager.backend_username,
                channel_id=channel_id,
                content=message
            )
            
            await self._network_client.send_packet(packet)
            self._logger.debug(f"Sent chat message to backend: {message}")
            
        except Exception as e:
            self._logger.error(f"Failed to send chat message: {e}")
    
    def _handle_chat_message(self, packet: ChatMessagePacket) -> None:
        """
        Handle incoming chat messages from the backend.
        
        Args:
            packet: The chat message packet
        """
        try:
            # Format the message
            formatted = self._format_message(
                packet.channel_id,
                packet.sender_name,
                packet.content
            )
            
            # Get players who should receive this message
            recipients = self._get_channel_recipients(packet.channel_id)
            
            # Send to recipients
            for player in recipients:
                try:
                    player.send_message(formatted)
                except Exception as e:
                    self._logger.error(f"Failed to send message to {player.name}: {e}")
                    
        except Exception as e:
            self._logger.error(f"Error handling chat message: {e}")
    
    def _get_channel_recipients(self, channel_id: str) -> list:
        """
        Get players who should receive messages from a channel.
        
        Args:
            channel_id: The channel ID
            
        Returns:
            List of players to receive the message
        """
        recipients = []
        
        try:
            for player in self._plugin.server.online_players:
                player_uuid = str(player.unique_id)
                player_channel = self._player_channels.get(
                    player_uuid,
                    self._config_manager.default_channel
                )
                
                # Include player if they're in the same channel or it's a global channel
                if player_channel == channel_id or channel_id == "global":
                    recipients.append(player)
                    
        except Exception as e:
            self._logger.error(f"Error getting channel recipients: {e}")
            # Fallback to all online players
            try:
                recipients = list(self._plugin.server.online_players)
            except Exception:
                pass
        
        return recipients
    
    def _handle_announcement(self, packet: AnnouncementPacket) -> None:
        """
        Handle incoming announcements from the backend.
        
        Args:
            packet: The announcement packet
        """
        try:
            # Format announcement with type-specific styling
            announcement_styles = {
                0: "§6[公告] §f",  # Normal
                1: "§c[紧急] §f",  # Urgent
                2: "§a[系统] §f",  # System
            }
            prefix = announcement_styles.get(packet.announcement_type, "§6[公告] §f")
            formatted = f"{prefix}{packet.message}"
            
            # Broadcast announcement to all players
            for player in self._plugin.server.online_players:
                try:
                    player.send_message(formatted)
                except Exception as e:
                    self._logger.error(f"Failed to send announcement to {player.name}: {e}")
                    
        except Exception as e:
            self._logger.error(f"Error handling announcement: {e}")
    
    def _handle_title_message(self, packet: TitleMessagePacket) -> None:
        """
        Handle incoming title messages from the backend.
        
        Args:
            packet: The title message packet
        """
        try:
            channel_id = (packet.channel_id or "").strip()
            if not channel_id or channel_id.lower() == "global":
                recipients = list(self._plugin.server.online_players)
            else:
                recipients = self._get_channel_recipients(channel_id)

            for player in recipients:
                self._send_title_to_player(player, packet)
                    
        except Exception as e:
            self._logger.error(f"Error handling title message: {e}")
    
    def _send_title_to_player(self, player: Any, packet: TitleMessagePacket) -> None:
        """
        Send a title to a specific player.
        
        Args:
            player: The target player
            packet: The title message packet
        """
        try:
            player.send_title(
                packet.title,
                packet.subtitle,
                packet.fade_in,
                packet.stay,
                packet.fade_out
            )
        except Exception as e:
            self._logger.error(f"Failed to send title to {player.name}: {e}")
    
    def _format_message(
        self,
        channel_id: str,
        player_name: str,
        message: str
    ) -> str:
        """
        Format a chat message using configured templates.
        
        Args:
            channel_id: The channel ID
            player_name: The sender's name
            message: The message content
            
        Returns:
            The formatted message string
        """
        # Get format template
        template = self._config_manager.get_channel_format(channel_id)
        
        # Replace placeholders
        formatted = template.replace("{player}", player_name)
        formatted = formatted.replace("{message}", message)
        formatted = formatted.replace("{channel_name}", channel_id)
        
        return formatted
    
    def set_player_channel(self, player_uuid: str, channel_id: str) -> None:
        """
        Set a player's current channel.
        
        Args:
            player_uuid: The player's UUID
            channel_id: The channel ID
        """
        self._player_channels[player_uuid] = channel_id
        self._logger.debug(f"Set player {player_uuid} channel to {channel_id}")
    
    def get_player_channel(self, player_uuid: str) -> str:
        """
        Get a player's current channel.
        
        Args:
            player_uuid: The player's UUID
            
        Returns:
            The channel ID
        """
        return self._player_channels.get(
            player_uuid,
            self._config_manager.default_channel
        )
    
    def toggle_chat(self, player_uuid: str) -> bool:
        """
        Toggle NovaChat for a player.
        
        Args:
            player_uuid: The player's UUID
            
        Returns:
            The new enabled state
        """
        current = self._chat_enabled.get(player_uuid, True)
        self._chat_enabled[player_uuid] = not current
        self._logger.debug(f"Toggled chat for {player_uuid}: {not current}")
        return not current
    
    def is_chat_enabled(self, player_uuid: str) -> bool:
        """
        Check if NovaChat is enabled for a player.
        
        Args:
            player_uuid: The player's UUID
            
        Returns:
            True if enabled
        """
        return self._chat_enabled.get(player_uuid, True)
    
    async def join_channel(self, player_uuid: str, channel_id: str, password: str = "") -> bool:
        """
        Request to join a channel via the backend.
        
        Args:
            player_uuid: The player's UUID
            channel_id: The channel to join
            password: Optional channel password
            
        Returns:
            True if the request was sent successfully
        """
        try:
            packet = ChannelActionPacket(
                action=ChannelActionPacket.ACTION_JOIN,
                channel_id=channel_id,
                password=password,
                extra={"playerId": player_uuid}
            )
            await self._network_client.send_packet(packet)
            
            # Optimistically update local state
            self._player_channels[player_uuid] = channel_id
            return True
            
        except Exception as e:
            self._logger.error(f"Failed to join channel: {e}")
            return False
    
    async def leave_channel(self, player_uuid: str) -> bool:
        """
        Request to leave the current channel.
        
        Args:
            player_uuid: The player's UUID
            
        Returns:
            True if the request was sent successfully
        """
        try:
            current_channel = self._player_channels.get(player_uuid)
            if not current_channel:
                return True
            
            packet = ChannelActionPacket(
                action=ChannelActionPacket.ACTION_LEAVE,
                channel_id=current_channel,
                password="",
                extra={"playerId": player_uuid}
            )
            await self._network_client.send_packet(packet)
            
            # Reset to default channel
            self._player_channels[player_uuid] = self._config_manager.default_channel
            return True
            
        except Exception as e:
            self._logger.error(f"Failed to leave channel: {e}")
            return False
    
    def on_player_join(self, player: Any) -> None:
        """
        Handle player join event.
        
        Args:
            player: The player who joined
        """
        player_uuid = str(player.unique_id)
        
        # Set default channel
        self._player_channels[player_uuid] = self._config_manager.default_channel
        self._chat_enabled[player_uuid] = True
        
        self._logger.debug(f"Player {player.name} joined, set to default channel")
    
    def on_player_quit(self, player: Any) -> None:
        """
        Handle player quit event.
        
        Args:
            player: The player who quit
        """
        player_uuid = str(player.unique_id)
        
        # Clean up player state
        self._player_channels.pop(player_uuid, None)
        self._chat_enabled.pop(player_uuid, None)
        
        self._logger.debug(f"Player {player.name} quit, cleaned up state")
