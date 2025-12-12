"""
Base extension module.

This module provides a base class for NovaChat extensions with common functionality.

Requirements: 10.2 - THE Endstone 扩展 SHALL 使用 Python 编写并放置在 extensions 目录
"""

import logging
from typing import TYPE_CHECKING, Optional

from novachat_endstone.extension.extension import NovaChatExtension
from novachat_endstone.extension.extension_meta import ExtensionMeta
from novachat_endstone.extension.extension_exception import ExtensionException

if TYPE_CHECKING:
    from novachat_endstone.plugin import NovaChatPlugin


class BaseExtension(NovaChatExtension):
    """
    Base class for NovaChat extensions.
    Provides default implementations for common extension functionality.
    
    Extensions can extend this class instead of implementing NovaChatExtension directly.
    """
    
    def __init__(self):
        """Initialize the base extension."""
        self._meta: Optional[ExtensionMeta] = None
        self._plugin: Optional['NovaChatPlugin'] = None
        self._logger: Optional[logging.Logger] = None
    
    def get_meta(self) -> ExtensionMeta:
        """
        Gets the extension metadata.
        
        Returns:
            The extension metadata
            
        Raises:
            ExtensionException: If metadata is not set
        """
        if self._meta is None:
            raise ExtensionException("Extension metadata not set")
        return self._meta
    
    def set_meta(self, meta: ExtensionMeta) -> None:
        """
        Sets the extension metadata.
        
        Args:
            meta: The extension metadata
        """
        self._meta = meta
        self._logger = logging.getLogger(f"NovaChat.Extension.{meta.id}")
    
    def get_plugin(self) -> Optional['NovaChatPlugin']:
        """
        Gets the NovaChat plugin instance.
        
        Returns:
            The plugin instance, or None if not set
        """
        return self._plugin
    
    def set_plugin(self, plugin: 'NovaChatPlugin') -> None:
        """
        Sets the NovaChat plugin instance.
        
        Args:
            plugin: The plugin instance
        """
        self._plugin = plugin
    
    @property
    def logger(self) -> logging.Logger:
        """
        Gets the extension's logger.
        
        Returns:
            The logger instance
        """
        if self._logger is None:
            self._logger = logging.getLogger("NovaChat.Extension")
        return self._logger
    
    def info(self, message: str) -> None:
        """
        Logs an info message.
        
        Args:
            message: The message to log
        """
        self.logger.info(message)
    
    def warning(self, message: str) -> None:
        """
        Logs a warning message.
        
        Args:
            message: The message to log
        """
        self.logger.warning(message)
    
    def error(self, message: str) -> None:
        """
        Logs an error message.
        
        Args:
            message: The message to log
        """
        self.logger.error(message)
    
    def debug(self, message: str) -> None:
        """
        Logs a debug message.
        
        Args:
            message: The message to log
        """
        self.logger.debug(message)
