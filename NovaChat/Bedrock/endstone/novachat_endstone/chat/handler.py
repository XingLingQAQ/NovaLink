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
    ChannelAction,
    ChatMessagePacket,
    AnnouncementPacket,
    TitleMessagePacket,
    ChannelActionPacket,
    ChannelActionResponsePacket,
    ConfigSyncPacket,
    MentionPacket,
    ItemDisplayPacket,
)

if TYPE_CHECKING:
    from novachat_endstone.plugin import NovaChatPlugin
    from novachat_endstone.network.client import NetworkClient
    from novachat_endstone.config.manager import ConfigManager


# Map a bare language prefix (no region) to the bundled <lang>_<REGION>
# locale. Only zh and en have shipped bundles today; any other bare
# language falls back to the hard default (zh_CN) so the i18n provider
# always resolves to a real bundle.
_BARE_LANGUAGE_DEFAULTS: Dict[str, str] = {
    "zh": "zh_CN",
    "en": "en_US",
}


def _bare_language_default(lang: str) -> str:
    """Resolve a bare language prefix (e.g. ``"zh"``) to a full locale code."""
    if not lang:
        return "zh_CN"
    return _BARE_LANGUAGE_DEFAULTS.get(lang, "zh_CN")


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
        self._player_locales: Dict[str, str] = {}  # player_uuid -> locale (zh_CN/en_US)

        # Known channel registry (populated by ConfigSync from backend)
        self._known_channels: list = []  # list of channel ids known from backend

        # Pending channel action requests awaiting async response (request_id -> player_uuid)
        self._pending_actions: Dict[str, str] = {}

        # i18n message provider
        from novachat_endstone.i18n import I18n
        self._i18n = I18n()

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
        self._network_client.register_handler(
            PacketIds.CHANNEL_ACTION_RESPONSE,
            self._handle_channel_action_response
        )
        self._network_client.register_handler(
            PacketIds.CONFIG_SYNC,
            self._handle_config_sync
        )
        self._network_client.register_handler(
            PacketIds.MENTION,
            self._handle_mention
        )
        self._network_client.register_handler(
            PacketIds.ITEM_DISPLAY,
            self._handle_item_display
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

            # Schedule the backend send on the network client's background
            # event loop. on_player_chat runs on Endstone's main server thread
            # where no asyncio loop is running, so asyncio.create_task would
            # raise RuntimeError and silently drop the message. Using
            # run_coroutine_threadsafe submits the coroutine to the client's
            # loop from any thread.
            loop = self._network_client.loop
            if loop is not None:
                asyncio.run_coroutine_threadsafe(
                    self._send_chat_message(player, channel_id, message),
                    loop,
                )
            else:
                self._logger.warning(
                    "No event loop available on network client; "
                    "dropping chat message"
                )
            
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

    def _handle_channel_action_response(self, packet: ChannelActionResponsePacket) -> None:
        """
        Route a ChannelActionResponse to the requesting player.

        JOIN/LEAVE/WHO async responses are routed by request_id -> player.
        On failure, the error code is translated via i18n.
        """
        try:
            request_id = str(getattr(packet, "request_id", ""))
            player_uuid = self._pending_actions.pop(request_id, None)
            if not player_uuid:
                self._logger.debug(
                    f"ChannelActionResponse with no pending request: action={packet.action}"
                )
                return

            locale = self._player_locales.get(player_uuid, "zh_CN")
            if packet.success:
                # Optimistic state update already happened on send; just ack.
                msg = self._i18n.get("chat.action.success", locale)
            else:
                msg = self._i18n.error_message(packet.error_code, locale)

            self._send_to_player_by_uuid(player_uuid, f"§e{msg}")
        except Exception as e:
            self._logger.error(f"Error handling channel action response: {e}")

    def _handle_config_sync(self, packet: ConfigSyncPacket) -> None:
        """
        Receive backend channel config sync -> update local known channel registry.

        The configJson contains the channel list the backend wants this client to know.
        """
        try:
            import json
            data = json.loads(packet.config_json or "{}")
            channels = data.get("channels", [])
            self._known_channels = [
                (c.get("id", c) if isinstance(c, dict) else str(c))
                for c in channels
            ]
            self._logger.debug(
                f"ConfigSync: updated known channels ({len(self._known_channels)})"
            )
        except Exception as e:
            self._logger.error(f"Error handling config sync: {e}")

    def _handle_mention(self, packet: MentionPacket) -> None:
        """
        @mention highlight: show title + play sound to the mentioned player.
        """
        try:
            mentioned_uuid = str(packet.mentioned_id)
            locale = self._player_locales.get(mentioned_uuid, "zh_CN")
            subtitle = self._i18n.get(
                "chat.mention.subtitle", locale, packet.channel_id
            )
            player = self._find_player_by_uuid(mentioned_uuid)
            if player:
                try:
                    player.send_title("§b§l@", subtitle, 10, 40, 20)
                except Exception:
                    pass
                # Best-effort sound notification
                try:
                    player.play_sound("random.orb", 1.0, 1.0)
                except Exception:
                    pass
        except Exception as e:
            self._logger.error(f"Error handling mention: {e}")

    def _handle_item_display(self, packet: ItemDisplayPacket) -> None:
        """
        [item]/[i] tag display from another server -> render to channel recipients.
        Bedrock has no hover, so inline text is used.
        """
        try:
            recipients = self._get_channel_recipients(packet.channel_id)
            for player in recipients:
                try:
                    player.send_message(
                        f"§7{packet.sender_name} §f[§bitem§f]§7: {packet.item_json}"
                    )
                except Exception:
                    pass
        except Exception as e:
            self._logger.error(f"Error handling item display: {e}")

    def _send_to_player_by_uuid(self, player_uuid: str, message: str) -> None:
        """Send a message to a player by UUID (best-effort)."""
        player = self._find_player_by_uuid(player_uuid)
        if player:
            try:
                player.send_message(message)
            except Exception as e:
                self._logger.error(f"Failed to send message to {player_uuid}: {e}")

    def _find_player_by_uuid(self, player_uuid: str) -> Any:
        """Find an online player by UUID string."""
        try:
            for player in self._plugin.server.online_players:
                if str(player.unique_id) == player_uuid:
                    return player
        except Exception:
            pass
        return None
    
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

        Tracks the request_id so the async ChannelActionResponse can be routed back.

        Args:
            player_uuid: The player's UUID
            channel_id: The channel to join
            password: Optional channel password

        Returns:
            True if the request was sent successfully
        """
        try:
            packet = ChannelActionPacket(
                action=ChannelAction.JOIN,
                channel_id=channel_id,
                password=password,
                extra={"playerId": player_uuid}
            )
            await self._network_client.send_packet(packet)
            request_id = str(getattr(packet, "request_id", ""))
            self._pending_actions[request_id] = player_uuid

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
                action=ChannelAction.LEAVE,
                channel_id=current_channel,
                password="",
                extra={"playerId": player_uuid}
            )
            await self._network_client.send_packet(packet)
            request_id = str(getattr(packet, "request_id", ""))
            self._pending_actions[request_id] = player_uuid

            # Reset to default channel
            self._player_channels[player_uuid] = self._config_manager.default_channel
            return True

        except Exception as e:
            self._logger.error(f"Failed to leave channel: {e}")
            return False

    async def who_channel(self, player_uuid: str, channel_id: str = "") -> bool:
        """
        Request the online member list of a channel (WHO action).

        Args:
            player_uuid: The requesting player's UUID
            channel_id: The channel to query; empty = current channel

        Returns:
            True if the request was sent successfully
        """
        try:
            if not channel_id:
                channel_id = self._player_channels.get(
                    player_uuid, self._config_manager.default_channel
                )
            packet = ChannelActionPacket(
                action=ChannelAction.WHO,
                channel_id=channel_id,
                password="",
                extra={"playerId": player_uuid}
            )
            await self._network_client.send_packet(packet)
            request_id = str(getattr(packet, "request_id", ""))
            self._pending_actions[request_id] = player_uuid
            return True
        except Exception as e:
            self._logger.error(f"Failed to query who: {e}")
            return False

    def notify_kick_target(self, player_uuid: str, operator: str, channel_id: str) -> None:
        """
        Send a kick target-side notification (title + action bar) to a player.

        Args:
            player_uuid: The kicked player's UUID
            operator: The operator name (or fallback "admin")
            channel_id: The channel the player was kicked from
        """
        locale = self._player_locales.get(player_uuid, "zh_CN")
        title = self._i18n.get("chat.notice.kick_title", locale)
        subtitle = self._i18n.get(
            "chat.notice.kick_subtitle", locale, operator, channel_id
        )
        actionbar = self._i18n.get(
            "chat.notice.kick_actionbar", locale, operator, channel_id
        )
        player = self._find_player_by_uuid(player_uuid)
        if player:
            try:
                player.send_title(title, subtitle, 10, 70, 20)
            except Exception:
                pass
            try:
                player.send_tip(actionbar)
            except Exception:
                pass

    def notify_mute_target(
        self, player_uuid: str, channel_id: str, duration: str
    ) -> None:
        """
        Send a mute target-side notification (title + action bar) to a player.

        Args:
            player_uuid: The muted player's UUID
            channel_id: The channel the player was muted in
            duration: Human-readable duration string
        """
        locale = self._player_locales.get(player_uuid, "zh_CN")
        title = self._i18n.get("chat.notice.mute_title", locale)
        subtitle = self._i18n.get(
            "chat.notice.mute_subtitle", locale, channel_id, duration
        )
        actionbar = self._i18n.get(
            "chat.notice.mute_actionbar", locale, duration, channel_id
        )
        player = self._find_player_by_uuid(player_uuid)
        if player:
            try:
                player.send_title(title, subtitle, 10, 70, 20)
            except Exception:
                pass
            try:
                player.send_tip(actionbar)
            except Exception:
                pass
    
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

        # Capture the player's full client locale. Endstone's Player.locale
        # exposes the language code the Bedrock client sent in its login
        # packet (e.g. "zh_CN", "en_US", "ja_JP", "fr_FR"). We pass the
        # complete code through to the i18n provider, which already falls
        # back to zh_CN (I18n.DEFAULT_LOCALE) when the requested locale has
        # no bundle. This means ja_JP/fr_FR/... are honoured when a lang
        # file exists, and silently fall back to zh_CN otherwise -- so we
        # never need to whitelist or binary-detect locales here.
        #
        # Normalization: trim + lowercase the region suffix to tolerate
        # "ZH_cn"/"zh_cn"/"ZH_CN" variants from the client. If the code lacks
        # a region (e.g. "zh", "en"), map the two known bare language prefixes
        # to our bundled locales (zh -> zh_CN, en -> en_US); any other bare
        # language is left as-is and the i18n fallback handles it.
        locale = "zh_CN"
        try:
            client_locale = (getattr(player, "locale", None) or "").strip()
            if client_locale:
                locale = self._normalize_locale(str(client_locale))
        except Exception:
            pass
        self._player_locales[player_uuid] = locale

        self._logger.debug(f"Player {player.name} joined, set to default channel, locale={locale}")

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
        self._player_locales.pop(player_uuid, None)

        self._logger.debug(f"Player {player.name} quit, cleaned up state")

    def get_player_locale(self, player_uuid: str) -> str:
        """Get a player's locale (defaults to zh_CN when unknown)."""
        return self._player_locales.get(player_uuid, "zh_CN")

    def set_player_locale(self, player_uuid: str, locale: str) -> None:
        """Set a player's locale.

        Any locale code is accepted; the i18n provider falls back to zh_CN
        when no bundle exists for the requested locale, so we do not need a
        whitelist here. The locale is normalized first so callers can pass
        raw client strings without breaking later lookups.
        """
        normalized = self._normalize_locale(locale) if locale else "zh_CN"
        self._player_locales[player_uuid] = normalized

    @staticmethod
    def _normalize_locale(raw: str) -> str:
        """Normalize a client locale string into <lang>_<REGION> form.

        Tolerates "zh_cn"/"ZH_CN"/"zh-CN"/"zh" variants. Bare language
        prefixes for the two bundled locales (zh, en) are mapped to the
        full <lang>_<REGION> form; any other value is returned in the
        normalized <lang>_<REGION> shape (or as-is if it cannot be split)
        and left for the i18n provider to fall back to zh_CN.
        """
        text = (raw or "").strip()
        if not text:
            return "zh_CN"
        # Endstone/Bedrock uses "_" as the separator, but be lenient and
        # also accept "-" (Java/BungeeCord style) and "." (legacy Bukkit).
        cleaned = text.replace("-", "_").replace(".", "_")
        if "_" in cleaned:
            lang, _, region = cleaned.partition("_")
            lang = lang.lower()
            region = region.upper()
            if not lang:
                return "zh_CN"
            if not region:
                # Bare language prefix with a trailing separator (e.g. "zh_").
                return _bare_language_default(lang)
            return f"{lang}_{region}"
        # No region separator at all.
        return _bare_language_default(cleaned.lower())

    def get_known_channels(self) -> list:
        """Get the known channel list (from backend ConfigSync)."""
        return list(self._known_channels)
