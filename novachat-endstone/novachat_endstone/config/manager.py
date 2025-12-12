"""
Configuration manager for NovaChat plugin.

This module handles loading, saving, and accessing plugin configuration
from YAML files.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import TYPE_CHECKING, Dict, Any, Optional

try:
    import yaml
except ImportError:
    yaml = None

if TYPE_CHECKING:
    from novachat_endstone.plugin import NovaChatPlugin


class ConfigManager:
    """Manager for plugin configuration."""
    
    DEFAULT_CONFIG = {
        "backend": {
            "host": "127.0.0.1",
            "port": 8888,
            "username": "EndstoneServer",
            "password": "change-me",
            "reconnect-delay": 5,
        },
        "chat": {
            "replace_vanilla": False,
            "default_channel": "local",
        },
        "format": {
            "channels": {
                "global": "§c[全服] §7{player}§f: {message}",
                "local": "§e[本地] §7{player}§f: {message}",
            },
            "default": "§7[{channel_name}] {player}§f: {message}",
        },
        "world-routing": {
            "enabled": True,
            "mappings": {
                "overworld": "local",
                "nether": "nether",
                "the_end": "end",
            },
        },
        "debug": False,
    }
    
    def __init__(self, plugin: "NovaChatPlugin"):
        """
        Initialize the configuration manager.
        
        Args:
            plugin: The parent plugin instance
        """
        self._plugin = plugin
        self._logger = logging.getLogger("NovaChat.Config")
        self._config: Dict[str, Any] = {}
        self._config_path: Optional[Path] = None
    
    def load(self) -> None:
        """Load configuration from file."""
        # Determine config path
        data_folder = Path(self._plugin.data_folder)
        self._config_path = data_folder / "config.yml"
        
        # Create default config if not exists
        if not self._config_path.exists():
            self._save_default_config()
        
        # Load config
        try:
            if yaml:
                with open(self._config_path, "r", encoding="utf-8") as f:
                    self._config = yaml.safe_load(f) or {}
            else:
                self._logger.warning("PyYAML not available, using default config")
                self._config = self.DEFAULT_CONFIG.copy()
        except Exception as e:
            self._logger.error(f"Failed to load config: {e}")
            self._config = self.DEFAULT_CONFIG.copy()
        
        # Merge with defaults for missing keys
        self._config = self._merge_defaults(self._config, self.DEFAULT_CONFIG)
        
        self._logger.info("Configuration loaded")
    
    def _save_default_config(self) -> None:
        """Save the default configuration file."""
        if not self._config_path:
            return
        
        # Ensure directory exists
        self._config_path.parent.mkdir(parents=True, exist_ok=True)
        
        try:
            if yaml:
                with open(self._config_path, "w", encoding="utf-8") as f:
                    yaml.dump(
                        self.DEFAULT_CONFIG,
                        f,
                        default_flow_style=False,
                        allow_unicode=True
                    )
                self._logger.info("Default configuration created")
            else:
                self._logger.warning("PyYAML not available, cannot save config")
        except Exception as e:
            self._logger.error(f"Failed to save default config: {e}")
    
    def _merge_defaults(
        self,
        config: Dict[str, Any],
        defaults: Dict[str, Any]
    ) -> Dict[str, Any]:
        """
        Merge configuration with defaults.
        
        Args:
            config: The loaded configuration
            defaults: The default configuration
            
        Returns:
            Merged configuration
        """
        result = defaults.copy()
        
        for key, value in config.items():
            if key in result and isinstance(result[key], dict) and isinstance(value, dict):
                result[key] = self._merge_defaults(value, result[key])
            else:
                result[key] = value
        
        return result
    
    # Backend settings
    
    @property
    def backend_host(self) -> str:
        """Get backend server host."""
        return self._config.get("backend", {}).get("host", "127.0.0.1")
    
    @property
    def backend_port(self) -> int:
        """Get backend server port."""
        return self._config.get("backend", {}).get("port", 8888)
    
    @property
    def backend_username(self) -> str:
        """Get backend username."""
        return self._config.get("backend", {}).get("username", "EndstoneServer")
    
    @property
    def backend_password(self) -> str:
        """Get backend password."""
        return self._config.get("backend", {}).get("password", "change-me")
    
    @property
    def reconnect_delay(self) -> int:
        """Get reconnect delay in seconds."""
        return self._config.get("backend", {}).get("reconnect-delay", 5)
    
    # Chat settings
    
    @property
    def replace_vanilla(self) -> bool:
        """Check if vanilla chat should be replaced."""
        return self._config.get("chat", {}).get("replace_vanilla", False)
    
    @property
    def default_channel(self) -> str:
        """Get default channel ID."""
        return self._config.get("chat", {}).get("default_channel", "local")
    
    # Format settings
    
    def get_channel_format(self, channel_id: str) -> str:
        """
        Get the format template for a channel.
        
        Args:
            channel_id: The channel ID
            
        Returns:
            The format template string
        """
        formats = self._config.get("format", {})
        channels = formats.get("channels", {})
        
        if channel_id in channels:
            return channels[channel_id]
        
        return formats.get("default", "§7[{channel_name}] {player}§f: {message}")
    
    # World routing settings
    
    @property
    def world_routing_enabled(self) -> bool:
        """Check if world-based routing is enabled."""
        return self._config.get("world-routing", {}).get("enabled", True)
    
    def get_world_channel(self, world_name: str) -> Optional[str]:
        """
        Get the channel for a world.
        
        Args:
            world_name: The world name
            
        Returns:
            The channel ID or None
        """
        mappings = self._config.get("world-routing", {}).get("mappings", {})
        return mappings.get(world_name)
    
    # Debug settings
    
    @property
    def debug(self) -> bool:
        """Check if debug mode is enabled."""
        return self._config.get("debug", False)
    
    @debug.setter
    def debug(self, value: bool) -> None:
        """Set debug mode."""
        self._config["debug"] = value
