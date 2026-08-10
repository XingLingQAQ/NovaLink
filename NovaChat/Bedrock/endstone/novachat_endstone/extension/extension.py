"""
Extension interface module.

This module defines the NovaChatExtension interface that all extensions must implement.

Requirements: 10.2 - THE Endstone 扩展 SHALL 使用 Python 编写并放置在 extensions 目录
"""

from abc import ABC, abstractmethod
from typing import TYPE_CHECKING, Optional

from novachat_endstone.extension.extension_meta import ExtensionMeta

if TYPE_CHECKING:
    from novachat_endstone.plugin import NovaChatPlugin


class NovaChatExtension(ABC):
    """
    Interface that all NovaChat extensions must implement.
    Extensions can add custom functionality to NovaChat without modifying core code.
    
    Lifecycle:
    1. Extension module is loaded from the extensions directory
    2. Extension metadata is parsed from extension.yml
    3. on_enable() is called when the extension is enabled
    4. on_disable() is called when the extension is disabled
    """
    
    @abstractmethod
    def on_enable(self) -> None:
        """
        Called when the extension is enabled.
        This is where the extension should initialize its resources,
        register event listeners, and set up commands.
        """
        pass
    
    @abstractmethod
    def on_disable(self) -> None:
        """
        Called when the extension is disabled.
        This is where the extension should clean up resources,
        unregister listeners, and save any pending data.
        """
        pass
    
    @abstractmethod
    def get_meta(self) -> ExtensionMeta:
        """
        Gets the extension metadata.
        
        Returns:
            The extension metadata containing id, name, version, etc.
        """
        pass
    
    @abstractmethod
    def set_meta(self, meta: ExtensionMeta) -> None:
        """
        Sets the extension metadata.
        Called by the extension loader after parsing extension.yml.
        
        Args:
            meta: The extension metadata
        """
        pass
    
    @abstractmethod
    def get_plugin(self) -> Optional['NovaChatPlugin']:
        """
        Gets the NovaChat plugin instance.
        
        Returns:
            The plugin instance, or None if not set
        """
        pass
    
    @abstractmethod
    def set_plugin(self, plugin: 'NovaChatPlugin') -> None:
        """
        Sets the NovaChat plugin instance.
        Called by the extension loader during initialization.
        
        Args:
            plugin: The plugin instance
        """
        pass
