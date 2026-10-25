"""
NovaChat Endstone Plugin - Main plugin class.

This module contains the main plugin class that handles initialization,
event registration, and lifecycle management.

Requirements: 10.1 - THE NovaChat-Endstone SHALL 使用 Python 3.10+ 编写
Requirements: 10.2 - THE NovaChat-Endstone SHALL 兼容 Endstone 最新 API
Requirements: 10.3 - WHEN 插件启用 THEN NovaChat-Endstone SHALL 建立与后端的 TCP 连接
Requirements: 10.4 - WHEN 玩家发送聊天消息 THEN NovaChat-Endstone SHALL 通过事件系统拦截消息

Endstone API notes (0.11.x, verified 2026-08-12):
  - The plugin extends endstone.plugin.Plugin (imported lazily inside
    ``NovaChatPlugin.__init__`` so the module imports without an endstone
    install, e.g. for pytest/protocol tests).
  - ``api_version`` MUST match the plugin.toml ``api`` field. Endstone's loader
    rejects plugins whose declared ``api_version`` is not a prefix of the
    running server's API line (e.g. "0.11" against endstone 0.11.8).
  - Event listeners are registered with ``self.register_events(self)`` inside
    ``on_enable``; handler methods use the ``@event_handler`` decorator
    (applied at class-definition time) and take the Endstone event type as
    their only argument. PlayerChatEvent, PlayerJoinEvent, and PlayerQuitEvent
    are all available in 0.11.x.
  - Commands are declared via the ``commands`` class attribute (same shape as
    plugin.toml's ``[plugin.commands]``) and dispatched through
    ``on_command(sender, command, args)``.
"""

# NOTE: deliberately NO `from __future__ import annotations` in this module.
#
# Endstone's register_events validates each handler with
#
#     sig = inspect.signature(func)
#     params = list(sig.parameters.values())
#     if (len(params) != 1
#         or not inspect.isclass(params[0].annotation)
#         or not issubclass(params[0].annotation, Event)):
#         "... invalid event handler signature ..."
#
# (endstone/plugin/__init__.py, lines 73-83). That reads the RAW annotation, not
# typing.get_type_hints(). With the future import every annotation becomes a
# STRING, so inspect.isclass("PlayerChatEvent") is False and all three handlers
# are rejected even though the source looks correctly annotated. The event
# handlers below therefore need their annotations evaluated eagerly, which is
# only possible without that import. Nothing else here needs it: the annotated
# names are all imported at module level, and the file uses typing.Optional
# rather than PEP 604 unions.

import asyncio
import concurrent.futures
import logging
import threading
from typing import TYPE_CHECKING, Optional, Any

from novachat_endstone.config.manager import ConfigManager
from novachat_endstone.network.client import NetworkClient
from novachat_endstone.chat.handler import ChatHandler
from novachat_endstone.command.commands import NovaChatCommand
from novachat_endstone.extension.extension_loader import ExtensionLoader

# Endstone's ``@event_handler`` decorator is applied at class-definition time
# (it stamps attributes onto the function object that ``register_events`` later
# scans for). It is NOT something that can be wired up lazily inside
# ``on_enable`` — by then the class is already finalized and the Endstone
# scanner would find no handlers. So we import it at module load.
#
# To keep this module importable in environments where the ``endstone`` package
# is absent (e.g. the pytest protocol suite, which never touches the event
# system), fall back to a no-op decorator + sentinel when the import fails.
try:
    from endstone.event import (
        event_handler,
        EventPriority,
        PlayerChatEvent,
        PlayerJoinEvent,
        PlayerQuitEvent,
    )
    from endstone.plugin import Plugin
    _ENDSTONE_EVENT_HANDLER_AVAILABLE = True
    _ENDSTONE_PLUGIN_BASE_AVAILABLE = True
except ImportError:  # pragma: no cover - exercised only without endstone
    def event_handler(func=None, *, priority=None, ignore_cancelled=False):
        """No-op fallback so the module imports without endstone installed."""
        def _wrap(f):
            return f
        if func is None:
            return _wrap
        return _wrap(func)

    class EventPriority:  # type: ignore[no-redef]
        LOWEST = 0
        LOW = 1
        NORMAL = 2
        HIGH = 3
        HIGHEST = 4
        MONITOR = 5

    # Event-type stand-ins. The handlers below annotate their parameter with
    # these, because endstone resolves the event type from that annotation (an
    # unannotated `event` parameter is rejected with "invalid event handler
    # signature"). Without endstone the annotations are inert but must still
    # resolve at import time.
    class PlayerChatEvent:  # type: ignore[no-redef]
        pass

    class PlayerJoinEvent:  # type: ignore[no-redef]
        pass

    class PlayerQuitEvent:  # type: ignore[no-redef]
        pass

    _ENDSTONE_EVENT_HANDLER_AVAILABLE = False
    _ENDSTONE_PLUGIN_BASE_AVAILABLE = False

    class Plugin:  # type: ignore[no-redef]
        """Import-time stand-in so the class body below is valid without endstone.

        Only ever used as a base class by the pytest suite, which never
        instantiates the plugin through a BDS host. The real endstone base
        replaces it whenever the package is installed.
        """

        def __init__(self) -> None:  # pragma: no cover - see docstring
            pass


if TYPE_CHECKING:
    pass


class NovaChatPlugin(Plugin):
    """
    Main NovaChat plugin class for Endstone.

    This class serves as the entry point for the NovaChat plugin,
    handling initialization, event registration, command registration,
    and lifecycle management.

    Validates: Requirements 10.1, 10.2, 10.3, 10.4

    Note:
        This class MUST subclass ``endstone.plugin.Plugin``. Endstone's loader
        instantiates the entry-point class itself and then checks

            plugin = cls()
            if not isinstance(plugin, Plugin):
                "... does not extend endstone.plugin.Plugin"

        so it does NOT subclass the class for us -- the earlier comment here
        claimed it did, and that is why the plugin failed to load with exactly
        that message even once the distribution name was correct.

        ``server``, ``logger``, ``data_folder`` and ``register_events`` all come
        from the native base. The plugin used to define its own Python
        ``server``/``data_folder`` properties; those shadowed the native ones
        and must NOT be reintroduced, or every ``self.server`` access sees the
        plugin's own ``None`` instead of the live BDS server.

        When endstone is not installed (the pytest protocol suite), ``Plugin``
        falls back to a plain stand-in defined above so this module still
        imports and the class body stays valid.
    """

    # Plugin metadata — kept in sync with plugin.toml.
    # api_version MUST match the ``api`` field in plugin.toml. Endstone's
    # plugin loader reads api_version off the loaded Plugin subclass and
    # rejects anything that does not match the running API line.
    api_version = "0.11"
    name = "NovaChat"
    version = "1.0.0"

    # Command metadata mirrored from plugin.toml. Endstone 0.11.x lets a
    # plugin declare its commands either in plugin.toml OR as a ``commands``
    # class attribute on the Plugin subclass; declaring them here makes the
    # plugin self-describing when loaded via the entry-point path (which does
    # not re-read plugin.toml).
    commands = {
        "novachat": {
            "description": "NovaChat main command",
            "usages": ["/novachat <subcommand> [args]"],
            "aliases": ["nc"],
            "permissions": ["novachat.use"],
        },
    }

    permissions = {
        "novachat.use": {
            "description": "Allows using NovaChat commands",
            "default": True,
        },
        "novachat.admin": {
            "description": "Allows using NovaChat admin commands",
            "default": "op",
        },
        "novachat.channel.join": {
            "description": "Allows joining channels",
            "default": True,
        },
        "novachat.channel.leave": {
            "description": "Allows leaving channels",
            "default": True,
        },
        "novachat.channel.create": {
            "description": "Allows creating private channels",
            "default": "op",
        },
        "novachat.mute": {
            "description": "Allows muting players",
            "default": "op",
        },
        "novachat.kick": {
            "description": "Allows kicking players from channels",
            "default": "op",
        },
        "novachat.ban": {
            "description": "Allows banning and unbanning players",
            "default": "op",
        },
        "novachat.announce": {
            "description": "Allows sending announcements",
            "default": "op",
        },
        "novachat.title": {
            "description": "Allows sending titles to channels",
            "default": "op",
        },
        "novachat.reload": {
            "description": "Allows reloading configuration",
            "default": "op",
        },
    }
    
    def __init__(self):
        """Initialize the plugin instance."""
        # The native base sets up the server/logger/plugin_loader bindings the
        # loader injects; skipping it leaves `self.server` raising
        # "Attempted a typeid of nullptr pointer!".
        super().__init__()
        self._config_manager: Optional[ConfigManager] = None
        self._network_client: Optional[NetworkClient] = None
        self._chat_handler: Optional[ChatHandler] = None
        self._command_handler: Optional[NovaChatCommand] = None
        self._extension_loader: Optional[ExtensionLoader] = None
        self._logger = logging.getLogger("NovaChat")

        # Background event loop owned by this plugin (see _start_backend_loop).
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._loop_thread: Optional[threading.Thread] = None
        # run_coroutine_threadsafe returns a concurrent.futures.Future.
        self._connect_task: Optional[concurrent.futures.Future] = None

        # Setup logging
        self._setup_logging()

    def _start_backend_loop(self) -> None:
        """Start the dedicated event loop that drives the backend connection.

        ``on_enable`` is a synchronous native callback with no running asyncio
        loop, so the plugin cannot use ``asyncio.create_task`` and must not
        depend on ``asyncio.get_event_loop()`` (which is deprecated and, on
        3.12, no longer creates a loop for a non-main thread). A daemon thread
        owns the loop and runs it forever; everything that needs a loop
        (NetworkClient.connect, its read/keepalive tasks, and later
        run_coroutine_threadsafe calls from BDS's main thread) uses this one.
        """
        if self._loop is not None and self._loop.is_running():
            return

        loop = asyncio.new_event_loop()
        self._loop = loop

        # Bind `loop` locally: a later _stop_backend_loop() clears self._loop,
        # and the thread must not then try to run None.
        def _run() -> None:
            asyncio.set_event_loop(loop)
            loop.run_forever()

        self._loop_thread = threading.Thread(
            target=_run, name="NovaChat-Backend-Loop", daemon=True
        )
        self._loop_thread.start()
        self._logger.info("Backend event loop started")

    def _stop_backend_loop(self) -> None:
        """Stop the dedicated loop and join its thread (best effort)."""
        loop, thread = self._loop, self._loop_thread
        self._loop = None
        self._loop_thread = None
        if loop is None:
            return
        try:
            loop.call_soon_threadsafe(loop.stop)
        except Exception as e:  # noqa: BLE001 - shutdown must never raise
            self._logger.debug(f"Could not stop backend loop cleanly: {e}")
        if thread is not None:
            thread.join(timeout=5.0)
        try:
            if not loop.is_closed():
                loop.close()
        except Exception as e:  # noqa: BLE001
            self._logger.debug(f"Could not close backend loop: {e}")
        self._logger.info("Backend event loop stopped")

    def _setup_logging(self) -> None:
        """Configure logging for the plugin."""
        handler = logging.StreamHandler()
        handler.setFormatter(
            logging.Formatter('[%(name)s] %(levelname)s: %(message)s')
        )
        
        logger = logging.getLogger("NovaChat")
        if not logger.handlers:
            logger.addHandler(handler)
        logger.setLevel(logging.INFO)

    # NOTE: no `server` / `data_folder` properties here on purpose. Both are
    # provided by the native endstone base class. Defining them as Python
    # properties shadows the native descriptors, so `self.server` would return
    # the plugin's own None instead of the live server, and every
    # `self._plugin.server.online_players` call in the handlers would break.

    def on_load(self) -> None:
        """
        Called when the plugin is loaded.
        
        This is called before on_enable and is used for early initialization.
        """
        self._logger.info("NovaChat plugin loading...")
    
    def on_enable(self) -> None:
        """
        Called when the plugin is enabled.
        
        This initializes all plugin components:
        - Configuration manager
        - Network client
        - Chat handler
        - Command handler
        - Event listeners
        
        Validates: Requirements 10.3
        """
        self._logger.info("NovaChat plugin enabling...")
        
        try:
            # Initialize configuration
            self._config_manager = ConfigManager(self)
            if not self._config_manager.load():
                raise RuntimeError(
                    "Configuration could not be loaded; config.yml was left unchanged"
                )
            self._logger.info("Configuration loaded")
            
            # Initialize network client
            self._network_client = NetworkClient(
                self,
                self._config_manager.backend_host,
                self._config_manager.backend_port,
                self._config_manager.backend_username,
                self._config_manager.backend_password,
                server_version=self._config_manager.server_version,
                reconnect_delay=self._config_manager.reconnect_delay,
                tls_enabled=self._config_manager.tls_enabled,
                tls_ca_cert_path=self._config_manager.tls_ca_cert_path,
                tls_client_cert_path=self._config_manager.tls_client_cert_path,
                tls_client_key_path=self._config_manager.tls_client_key_path,
            )
            self._logger.info("Network client initialized")
            
            # Initialize chat handler
            self._chat_handler = ChatHandler(
                self, 
                self._network_client, 
                self._config_manager
            )
            self._logger.info("Chat handler initialized")
            
            # Initialize command handler
            self._command_handler = NovaChatCommand(self)
            self._logger.info("Command handler initialized")
            
            # Register event listeners
            self._register_listeners()
            
            # Register commands
            self._register_commands()
            
            # Connect to the backend on a loop this plugin owns.
            #
            # `asyncio.create_task` needs a RUNNING loop in the calling thread,
            # and `on_enable` is a synchronous callback driven by native BDS
            # code -- there is no asyncio loop here at all, so create_task
            # raised "RuntimeError: no running event loop". That exception
            # propagated out of on_enable, endstone reported "Error occurred
            # when enabling novachat", and BDS then logged "Server stop
            # requested." in the same second and shut down. The plugin must
            # therefore supply its own loop rather than borrow the server's.
            #
            # A dedicated daemon thread owns the loop and runs it forever. That
            # also gives NetworkClient.connect() a running loop to capture in
            # self._loop (network/client.py), which the chat handler and the
            # command handler need for run_coroutine_threadsafe when they are
            # invoked from BDS's main thread.
            self._start_backend_loop()
            self._connect_task = asyncio.run_coroutine_threadsafe(
                self._connect_to_backend(), self._loop
            )

            # Load and enable extensions
            # Requirements: 10.2 - THE Endstone 扩展 SHALL 使用 Python 编写并放置在 extensions 目录
            self._load_extensions()
            
            self._logger.info("NovaChat plugin enabled successfully!")
            
        except Exception as e:
            self._logger.error(f"Failed to enable plugin: {e}")
            raise
    
    def on_disable(self) -> None:
        """
        Called when the plugin is disabled.
        
        This cleans up all plugin resources:
        - Disables extensions
        - Disconnects from backend
        - Cleans up handlers
        """
        self._logger.info("NovaChat plugin disabling...")
        
        try:
            # Disable all extensions first
            if self._extension_loader:
                self._extension_loader.disable_all_extensions()
                self._extension_loader = None
            
            # Disconnect from backend
            if self._network_client:
                # disconnect() schedules the socket close on the loop, so the
                # loop must still be running when we call it -- stop it after.
                self._network_client.disconnect()
                self._logger.info("Disconnected from backend")
            
            # Clean up handlers
            self._chat_handler = None
            self._command_handler = None

            # Stop the loop this plugin owns, last, so disconnect() above could
            # still schedule its close on a live loop.
            self._stop_backend_loop()
            self._network_client = None

            self._logger.info("NovaChat plugin disabled successfully!")
            
        except Exception as e:
            self._logger.error(f"Error during plugin disable: {e}")
    
    def _register_listeners(self) -> None:
        """
        Register event listeners with the Endstone server.

        Endstone 0.11.x exposes ``self.register_events(self)`` on the Plugin
        base class; it scans the plugin instance for methods decorated with
        ``@event_handler`` (applied at class-definition time on
        ``_on_player_chat`` / ``_on_player_join`` / ``_on_player_quit``) and
        binds them to the corresponding event types (PlayerChatEvent,
        PlayerJoinEvent, PlayerQuitEvent). When endstone is not installed
        (unit tests), ``register_events`` is absent and we simply log + skip.

        Validates: Requirements 10.4
        """
        try:
            if not self._chat_handler:
                self._logger.warning("Chat handler not ready; skipping listener registration")
                return

            register_events = getattr(self, "register_events", None)
            if register_events is None:
                # Endstone base class not wired (e.g. unit-test import); the
                # handler methods are still callable directly.
                self._logger.info("Event listeners registered (no-op; endstone.register_events unavailable)")
                return

            register_events(self)
            self._logger.info("Event listeners registered")
        except Exception as e:
            self._logger.error(f"Failed to register listeners: {e}")

    def _register_commands(self) -> None:
        """
        Register plugin commands with the Endstone server.

        Endstone 0.11.x dispatches commands declared via the ``commands``
        class attribute to ``on_command(sender, command, args)`` on the
        plugin instance. No explicit registration call is required when the
        plugin is loaded through the ``endstone`` entry point; we just
        verify the handler is wired and log the result.
        """
        try:
            if self._command_handler:
                self._logger.info("Commands registered")
            else:
                self._logger.warning("Command handler not ready; commands will not dispatch")
        except Exception as e:
            self._logger.error(f"Failed to register commands: {e}")

    # ------------------------------------------------------------------
    # Endstone 0.11.x event listeners.
    #
    # ``register_events(self)`` (called in _register_listeners) scans the
    # instance for methods decorated with ``@event_handler`` and binds them
    # to the matching Endstone event type. The decorator MUST be applied at
    # class-definition time — Endstone's scanner looks for the attributes it
    # stamps onto the function, so applying it lazily (e.g. inside on_enable)
    # results in zero handlers being registered. The ``event_handler`` import
    # above has a no-op fallback so the module still imports without an
    # endstone install (pytest).
    #
    # Priority: HIGH so NovaChat sees chat before lower-priority plugins,
    # letting us cancel/redirect it. ``ignore_cancelled=False`` keeps the
    # default behavior of skipping already-cancelled events.
    #
    # The ``event`` parameter MUST carry a type annotation. Endstone resolves
    # which event a handler subscribes to from that annotation, so the previous
    # unannotated ``def _on_player_chat(self, event)`` was rejected at register
    # time (run 40149942743):
    #
    #   Plugin NovaChat attempted to register an invalid event handler
    #   signature: _on_player_chat: (event)
    #
    # all three handlers were dropped, and endstone then failed the enable.
    # ------------------------------------------------------------------

    @event_handler(priority=EventPriority.HIGH)
    def _on_player_chat(self, event: PlayerChatEvent):
        """Endstone PlayerChatEvent listener."""
        if self._chat_handler:
            self._chat_handler.on_player_chat(event)

    @event_handler(priority=EventPriority.HIGH)
    def _on_player_join(self, event: PlayerJoinEvent):
        """Endstone PlayerJoinEvent listener."""
        if self._chat_handler:
            try:
                self._chat_handler.on_player_join(event.player)
            except Exception as e:
                self._logger.error(f"Error handling player join: {e}")

    @event_handler(priority=EventPriority.HIGH)
    def _on_player_quit(self, event: PlayerQuitEvent):
        """Endstone PlayerQuitEvent listener."""
        if self._chat_handler:
            try:
                self._chat_handler.on_player_quit(event.player)
            except Exception as e:
                self._logger.error(f"Error handling player quit: {e}")

    def on_command(self, sender, command, args):
        """
        Endstone 0.11.x command dispatch entry point.

        Endstone calls this when a player or console executes a command
        declared in the ``commands`` class attribute. Returns True if the
        command was handled.
        """
        if self._command_handler:
            return self._command_handler.on_command(sender, command, command.name, args)
        return False

    async def _connect_to_backend(self) -> None:
        """
        Connect to the NovaLink backend server.
        
        This establishes the TCP connection to the backend and
        performs authentication.
        
        Validates: Requirements 10.3
        """
        if self._network_client:
            try:
                success = await self._network_client.connect()
                if success:
                    self._logger.info("Connected to NovaLink backend")
                else:
                    self._logger.warning("Failed to connect to NovaLink backend")
            except Exception as e:
                self._logger.error(f"Error connecting to backend: {e}")
    
    @property
    def config_manager(self) -> Optional[ConfigManager]:
        """Get the configuration manager."""
        return self._config_manager
    
    @property
    def network_client(self) -> Optional[NetworkClient]:
        """Get the network client."""
        return self._network_client
    
    @property
    def chat_handler(self) -> Optional[ChatHandler]:
        """Get the chat handler."""
        return self._chat_handler
    
    @property
    def command_handler(self) -> Optional[NovaChatCommand]:
        """Get the command handler."""
        return self._command_handler
    
    @property
    def extension_loader(self) -> Optional[ExtensionLoader]:
        """Get the extension loader."""
        return self._extension_loader
    
    def _load_extensions(self) -> None:
        """
        Loads and enables extensions from the extensions directory.
        
        Requirements: 10.2 - THE Endstone 扩展 SHALL 使用 Python 编写并放置在 extensions 目录
        Requirements: 10.4 - WHEN 扩展加载 THEN 各平台扩展加载器 SHALL 调用对应的初始化方法
        """
        import os
        # self.data_folder (native, from the endstone base class), not the old
        # self._data_folder -- that Python attribute no longer exists now that
        # the plugin extends Plugin instead of shadowing it.
        extensions_dir = os.path.join(self.data_folder, "extensions")
        
        self._extension_loader = ExtensionLoader(self)
        extensions = self._extension_loader.load_extensions(extensions_dir)
        
        if len(extensions) > 0:
            self._logger.info(f"Found {len(extensions)} extension(s)")
            self._extension_loader.enable_all_extensions()
    
    def reload_config(self) -> None:
        """
        Reload the plugin configuration.
        
        This reloads the configuration from disk and updates
        all components that depend on it.
        """
        if self._config_manager:
            if not self._config_manager.load():
                self._logger.warning(
                    "Configuration reload rejected; the previous runtime configuration remains active"
                )
                return
            self._logger.info("Configuration reloaded!")
            
            # Update logging level based on debug setting
            if self._config_manager.debug:
                logging.getLogger("NovaChat").setLevel(logging.DEBUG)
            else:
                logging.getLogger("NovaChat").setLevel(logging.INFO)
    
    def handle_player_chat(self, event: Any) -> None:
        """
        Handle a player chat event.
        
        This is called by the Endstone event system when a player
        sends a chat message.
        
        Args:
            event: The player chat event
            
        Validates: Requirements 10.4
        """
        if self._chat_handler:
            self._chat_handler.on_player_chat(event)
    
    def handle_player_join(self, event: Any) -> None:
        """
        Handle a player join event.
        
        Args:
            event: The player join event
        """
        if self._chat_handler:
            try:
                player = event.player
                self._chat_handler.on_player_join(player)
            except Exception as e:
                self._logger.error(f"Error handling player join: {e}")
    
    def handle_player_quit(self, event: Any) -> None:
        """
        Handle a player quit event.
        
        Args:
            event: The player quit event
        """
        if self._chat_handler:
            try:
                player = event.player
                self._chat_handler.on_player_quit(player)
            except Exception as e:
                self._logger.error(f"Error handling player quit: {e}")
    
    def handle_command(
        self,
        sender: Any,
        command: Any,
        label: str,
        args: list
    ) -> bool:
        """
        Handle a command execution.
        
        This is called by the Endstone command system when a player
        or console executes the /novachat or /nc command.
        
        Args:
            sender: The command sender
            command: The command object
            label: The command label used
            args: Command arguments
            
        Returns:
            True if command was handled
        """
        if self._command_handler:
            return self._command_handler.on_command(sender, command, label, args)
        return False
    
    def handle_tab_complete(
        self,
        sender: Any,
        command: Any,
        alias: str,
        args: list
    ) -> list:
        """
        Handle tab completion for commands.
        
        Args:
            sender: The command sender
            command: The command object
            alias: The command alias used
            args: Current arguments
            
        Returns:
            List of completions
        """
        if self._command_handler:
            result = self._command_handler.on_tab_complete(sender, command, alias, args)
            return result if result else []
        return []
