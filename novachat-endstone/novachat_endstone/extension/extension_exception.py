"""
Extension exception module.

This module defines the exception class for extension-related errors.

Requirements: 10.2 - THE Endstone 扩展 SHALL 使用 Python 编写并放置在 extensions 目录
"""

from typing import Optional


class ExtensionException(Exception):
    """
    Exception thrown when an extension fails to load or encounters an error.
    """
    
    def __init__(
        self, 
        message: str, 
        extension_id: Optional[str] = None,
        cause: Optional[Exception] = None
    ):
        """
        Creates a new ExtensionException.
        
        Args:
            message: The error message
            extension_id: The ID of the extension that caused the error
            cause: The underlying exception that caused this error
        """
        self.extension_id = extension_id
        self.cause = cause
        
        full_message = f"Extension '{extension_id}': {message}" if extension_id else message
        super().__init__(full_message)
