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
    AdminAction,
    ChatMessagePacket,
    TitleMessagePacket,
    ChannelActionPacket,
    ChannelActionResponsePacket,
    AdminActionResponsePacket,
    ConfigSyncPacket,
    MentionPacket,
    ItemDisplayPacket,
    PrivateMessagePacket,
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


class _MainThreadMarshaler:
    """Posts callables onto Endstone's main server thread (VERIFY-004).

    Resolution happens lazily on first use and is then cached, so the
    scheduler lookup cost is paid once per handler lifetime. A callable is
    marshaled via ``plugin.server.scheduler.run_task(plugin, fn)`` — the
    platform's documented main-thread entry point (``Task.is_sync``:
    "run by server thread"). When any link in that chain is missing (unit
    tests, plugin stubs, server not yet wired) the callable runs inline on
    the caller's thread, preserving pre-fix behavior exactly.
    """

    def __init__(self, plugin: "NovaChatPlugin"):
        self._plugin = plugin
        self._scheduler: Any = None
        self._resolved = False

    def _resolve_scheduler(self) -> Any:
        """One-time best-effort resolution of the live server scheduler."""
        if self._resolved:
            return self._scheduler
        self._resolved = True
        try:
            server = getattr(self._plugin, "server", None)
            scheduler = getattr(server, "scheduler", None)
            run_task = getattr(scheduler, "run_task", None)
            if callable(run_task):
                # Guard against MagicMock-style test doubles whose
                # attributes exist but would not marshal to a real server
                # thread: only accept an object that declares itself as the
                # endstone Scheduler type when endstone is importable.
                try:
                    from endstone.scheduler import Scheduler
                    if not isinstance(scheduler, Scheduler):
                        scheduler = None
                except ImportError:
                    scheduler = None
                if scheduler is not None:
                    self._scheduler = scheduler
        except Exception:
            # Never let marshaler resolution break packet delivery.
            self._scheduler = None
        return self._scheduler

    def post(self, fn) -> None:
        """Run ``fn`` on the main thread when possible, else inline."""
        scheduler = None
        try:
            scheduler = self._resolve_scheduler()
        except Exception:
            scheduler = None

        if scheduler is not None:
            try:
                scheduler.run_task(self._plugin, fn)
                return
            except Exception as e:
                # Scheduling failed (e.g. server shutting down); fall back
                # to inline execution so delivery is attempted anyway.
                logging.getLogger("NovaChat.Chat").debug(
                    f"scheduler.run_task failed, running inline: {e}"
                )
        fn()


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

        # VERIFY-004: main-thread marshaling for native player API calls.
        # Inbound packet handlers run on the asyncio NETWORK-loop thread
        # (NetworkClient._read_loop -> _handle_packet -> handler(packet)),
        # but Endstone's BedrockScriptAPI surface (send_message/send_title/
        # send_tip/play_sound) follows Bukkit conventions where cross-thread
        # world/player access is at best undocumented. Scheduler.run_task is
        # the platform's documented primitive for marshaling onto the server
        # thread (Task.is_sync: "run by server thread"). When a real
        # scheduler is reachable we post through it; otherwise (unit tests,
        # plugin stubs without a server) we execute inline so behavior is
        # unchanged.
        self._main_thread_marshaler = _MainThreadMarshaler(plugin)

        # Register packet handlers
        self._register_packet_handlers()

    def _post_to_main_thread(self, fn) -> None:
        """Run ``fn`` on Endstone's main server thread (best-effort).

        VERIFY-004 seam: every native player-API invocation triggered by an
        inbound packet goes through here. With a live Endstone runtime this
        delegates to ``Scheduler.run_task``; without one it runs inline so
        unit-test behavior stays identical to pre-fix deliveries.
        """
        # Lazily self-heal for instances built via ``__new__`` + attribute
        # stamping (the established unit-test idiom) which bypass __init__.
        marshaler = getattr(self, "_main_thread_marshaler", None)
        if marshaler is None:
            marshaler = _MainThreadMarshaler(self._plugin)
            self._main_thread_marshaler = marshaler
        marshaler.post(fn)

    def _register_packet_handlers(self) -> None:
        """Register handlers for incoming packets from the backend."""
        self._network_client.register_handler(
            PacketIds.CHAT_MESSAGE,
            self._handle_chat_message
        )
        # FEATURE-002: the legacy ANNOUNCEMENT (0x0A) handler is gone. The
        # backend never sends 0x0A; announcements arrive as AdminAction STATUS
        # type=ANNOUNCE responses on ADMIN_ACTION_RESPONSE (0x0C), correlated
        # by request_id against _pending_actions.
        self._network_client.register_handler(
            PacketIds.ADMIN_ACTION_RESPONSE,
            self._handle_admin_action_response
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
        self._network_client.register_handler(
            PacketIds.PRIVATE_MESSAGE,
            self._handle_private_message
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

            # Send to recipients (VERIFY-004: marshaled to main thread)
            for player in recipients:
                def _deliver(player=player, message=formatted):
                    try:
                        player.send_message(message)
                    except Exception as e:
                        self._logger.error(
                            f"Failed to send message to {player.name}: {e}"
                        )

                self._post_to_main_thread(_deliver)

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
            except Exception as e:
                self._logger.debug(f"Fallback recipient list also failed: {e}")
                pass
        
        return recipients
    
    def _handle_admin_action_response(self, packet: AdminActionResponsePacket) -> None:
        """
        Route an AdminActionResponse back to the requesting player.

        FEATURE-002: the backend gates STATUS (ANNOUNCE/TITLE) behind
        ``permissionManager.hasSuperAdminSession`` and returns NC-403 when the
        sender lacks an active super-admin session. The client does NOT track
        super-admin session state locally (only the handshake-level
        ``NetworkClient.is_authenticated`` flag exists), so the "run /nc auth"
        guidance can only be surfaced here, on the async NC-403 response path
        — never pre-emptively at send time.

        Correlation is by request_id (written into the frame by
        ``NetworkClient.send_packet`` and stamped back onto the decoded
        response packet by ``NetworkClient._read_packet``), mirroring the
        bukkit ``pendingAdminRequests`` flow.
        """
        try:
            request_id = str(getattr(packet, "request_id", ""))
            player_uuid = self._pending_actions.pop(request_id, None)
            if not player_uuid:
                self._logger.debug(
                    f"AdminActionResponse with no pending request: action={packet.action}"
                )
                return

            locale = self._player_locales.get(player_uuid, "zh_CN")

            if packet.success:
                # Prefer the backend's message; fall back to a generic ack.
                msg = (
                    packet.message
                    if packet.message
                    else self._i18n.get("chat.action.success", locale)
                )
            elif (
                packet.action == AdminAction.STATUS
                and packet.error_code == "NC-403"
            ):
                # Super-admin-session-required path: surface the auth
                # guidance instead of the generic NC-403 "permission denied"
                # text. Mirrors bukkit's isSuperAdminRequired branch.
                msg = (
                    self._i18n.get("chat.error.super_admin_required", locale)
                    + " "
                    + self._i18n.get(
                        "chat.error.super_admin_required_suggestion", locale
                    )
                )
            else:
                msg = self._i18n.error_message(packet.error_code, locale)

            self._send_to_player_by_uuid(player_uuid, msg)
        except Exception as e:
            self._logger.error(f"Error handling admin action response: {e}")

    def register_pending_admin_action(
        self, request_id: str, player_uuid: str
    ) -> None:
        """
        Register a pending AdminAction request so the async response can be
        routed back to the originating player by request_id.

        Reuses the shared ``_pending_actions`` map (request_id -> player_uuid)
        that ChannelActionResponse already uses, since both response types
        carry a frame-level request_id and target a single player.
        """
        if request_id:
            self._pending_actions[request_id] = player_uuid

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

        VERIFY-004: the native call is marshaled onto the main thread when a
        scheduler is reachable; inline otherwise.

        Args:
            player: The target player
            packet: The title message packet
        """

        def _deliver():
            player.send_title(
                packet.title,
                packet.subtitle,
                packet.fade_in,
                packet.stay,
                packet.fade_out
            )

        try:
            self._post_to_main_thread(_deliver)
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

        Mirrors the Java ``ConfigSyncChannels.extract`` contract: the known
        channel set is the union of ``global_channels`` keys and the
        ``channels`` keys of the ``clients[]`` entry whose ``username`` matches
        this client's configured backend username. Only the KNOWN channel
        registry is touched; the active per-player channel
        (``self._player_channels``) is never overwritten.

        Parsing is best-effort: a bad payload or wrong type logs a warning and
        leaves the existing registry intact rather than raising.
        """
        import json

        username = ""
        try:
            username = self._config_manager.backend_username
        except Exception as e:  # config not loaded / missing key
            self._logger.warning(f"ConfigSync: could not read backend username: {e}")

        try:
            data = json.loads(packet.config_json or "{}")
            if not isinstance(data, dict):
                self._logger.warning(
                    f"ConfigSync: expected JSON object, got {type(data).__name__}"
                )
                return

            channels: set[str] = set()

            # Global channels: keys of the global_channels mapping.
            globals_obj = data.get("global_channels")
            if isinstance(globals_obj, dict):
                channels.update(str(k) for k in globals_obj.keys())
            elif globals_obj is not None:
                self._logger.warning(
                    f"ConfigSync: global_channels must be an object, got "
                    f"{type(globals_obj).__name__}"
                )

            # Per-client channels for this client only (null/blank username
            # -> globals only, matching the Java extractor).
            if username and username.strip():
                clients = data.get("clients")
                if isinstance(clients, list):
                    for entry in clients:
                        if not isinstance(entry, dict):
                            continue
                        entry_username = entry.get("username")
                        if not isinstance(entry_username, str):
                            continue
                        if entry_username != username:
                            continue
                        client_channels = entry.get("channels")
                        if isinstance(client_channels, dict):
                            channels.update(str(k) for k in client_channels.keys())
                        elif client_channels is not None:
                            self._logger.warning(
                                f"ConfigSync: client.channels must be an object, "
                                f"got {type(client_channels).__name__}"
                            )
                        break
                elif clients is not None:
                    self._logger.warning(
                        f"ConfigSync: clients must be an array, got "
                        f"{type(clients).__name__}"
                    )

            # Sorted for deterministic ordering (/nc list, tab completion).
            self._known_channels = sorted(channels)
            self._logger.debug(
                f"ConfigSync: updated known channels ({len(self._known_channels)})"
            )
        except json.JSONDecodeError as e:
            self._logger.warning(f"ConfigSync: malformed config JSON: {e}")
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
                # VERIFY-004: title + sound are native calls -> main thread.
                def _deliver_mention(player=player, subtitle=subtitle):
                    try:
                        player.send_title("§b§l@", subtitle, 10, 40, 20)
                    except Exception as e:
                        self._logger.debug(f"Failed to send title to player: {e}")
                    # Best-effort sound notification
                    try:
                        player.play_sound("random.orb", 1.0, 1.0)
                    except Exception as e:
                        self._logger.debug(f"Failed to play sound: {e}")

                self._post_to_main_thread(_deliver_mention)
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
                # VERIFY-004: native send_message -> main thread.
                def _deliver_item(player=player, item_json=packet.item_json,
                                  sender_name=packet.sender_name):
                    try:
                        player.send_message(
                            f"§7{sender_name} §f[§bitem§f]§7: {item_json}"
                        )
                    except Exception as e:
                        self._logger.debug(f"Failed to send message to player: {e}")

                self._post_to_main_thread(_deliver_item)
        except Exception as e:
            self._logger.error(f"Error handling item display: {e}")

    def _handle_private_message(self, packet: PrivateMessagePacket) -> None:
        """
        Receive-side rendering of an inbound private message (0x14).

        The backend delivers a completed PrivateMessagePacket to BOTH the
        sender's client (echo) and the target's client. When the local player
        matches sender_id we render the "sent" line; when it matches target_id
        (and target_id != sender_id) we render the "received" line. Private
        chat is per-player directed, so this never broadcasts to a channel.

        Endstone send_message emits the raw string (no & -> § conversion),
        matching the idiom used by _handle_chat_message / _handle_item_display.
        """
        try:
            nil_uuid = "00000000-0000-0000-0000-000000000000"
            sender_uuid = str(packet.sender_id)
            target_uuid = str(packet.target_id)

            # Echo line: this local player is the sender.
            if sender_uuid and sender_uuid != nil_uuid:
                player = self._find_player_by_uuid(sender_uuid)
                if player:
                    locale = self._player_locales.get(sender_uuid, "zh_CN")
                    message = self._i18n.get(
                        "chat.msg.sent", locale, packet.target_name, packet.content
                    )
                    # VERIFY-004: native send_message -> main thread.
                    def _deliver_echo(player=player, message=message):
                        try:
                            player.send_message(message)
                        except Exception as e:
                            self._logger.debug(f"Failed to send echo to player: {e}")

                    self._post_to_main_thread(_deliver_echo)

            # Received line: this local player is the (distinct) target.
            if (
                target_uuid
                and target_uuid != nil_uuid
                and target_uuid != sender_uuid
            ):
                player = self._find_player_by_uuid(target_uuid)
                if player:
                    locale = self._player_locales.get(target_uuid, "zh_CN")
                    message = self._i18n.get(
                        "chat.msg.received", locale, packet.sender_name, packet.content
                    )
                    # VERIFY-004: native send_message -> main thread.
                    def _deliver_received(player=player, message=message):
                        try:
                            player.send_message(message)
                        except Exception as e:
                            self._logger.debug(f"Failed to send private msg to player: {e}")

                    self._post_to_main_thread(_deliver_received)
        except Exception as e:
            self._logger.error(f"Error handling private message: {e}")

    def _send_to_player_by_uuid(self, player_uuid: str, message: str) -> None:
        """Send a message to a player by UUID (best-effort)."""
        player = self._find_player_by_uuid(player_uuid)
        if player:
            # VERIFY-004: native send_message -> main thread.
            def _deliver(player=player, message=message):
                try:
                    player.send_message(message)
                except Exception as e:
                    self._logger.error(f"Failed to send message to {player_uuid}: {e}")

            self._post_to_main_thread(_deliver)

    def _find_player_by_uuid(self, player_uuid: str) -> Any:
        """Find an online player by UUID string."""
        try:
            for player in self._plugin.server.online_players:
                if str(player.unique_id) == player_uuid:
                    return player
        except Exception as e:
            self._logger.debug(f"Player lookup failed: {e}")
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
            # VERIFY-004: title + action bar are native calls -> main thread.
            def _deliver_kick(player=player, title=title, subtitle=subtitle,
                              actionbar=actionbar):
                try:
                    player.send_title(title, subtitle, 10, 70, 20)
                except Exception as e:
                    self._logger.debug(f"Failed to send title to player: {e}")
                try:
                    player.send_tip(actionbar)
                except Exception as e:
                    self._logger.debug(f"Failed to send tip: {e}")

            self._post_to_main_thread(_deliver_kick)

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
            # VERIFY-004: title + action bar are native calls -> main thread.
            def _deliver_mute(player=player, title=title, subtitle=subtitle,
                              actionbar=actionbar):
                try:
                    player.send_title(title, subtitle, 10, 70, 20)
                except Exception as e:
                    self._logger.debug(f"Failed to send title to player: {e}")
                try:
                    player.send_tip(actionbar)
                except Exception as e:
                    self._logger.debug(f"Failed to send tip: {e}")

            self._post_to_main_thread(_deliver_mute)

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
        except Exception as e:
            self._logger.debug(f"Locale read failed: {e}")
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
