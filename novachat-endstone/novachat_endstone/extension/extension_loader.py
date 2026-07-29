"""
Extension loader module.

This module provides the ExtensionLoader class for loading and managing extensions.

Requirements: 10.2 - THE Endstone 扩展 SHALL 使用 Python 编写并放置在 extensions 目录
Requirements: 10.4 - WHEN 扩展加载 THEN 各平台扩展加载器 SHALL 调用对应的初始化方法
"""

import importlib.util
import logging
import os
import sys
from pathlib import Path
from typing import TYPE_CHECKING, Dict, List, Optional

import yaml

from novachat_endstone.extension.extension import NovaChatExtension
from novachat_endstone.extension.extension_meta import ExtensionMeta
from novachat_endstone.extension.extension_exception import ExtensionException

if TYPE_CHECKING:
    from novachat_endstone.plugin import NovaChatPlugin


class ExtensionLoader:
    """
    Loads and manages NovaChat extensions for Endstone.
    
    This loader scans the extensions directory for Python extension modules,
    parses extension.yml metadata, and creates extension instances.
    
    Extensions that fail to load are logged but do not prevent
    other extensions from loading (isolation property).
    """
    
    EXTENSION_YML = "extension.yml"
    
    def __init__(self, plugin: 'NovaChatPlugin'):
        """
        Creates a new ExtensionLoader.
        
        Args:
            plugin: The NovaChat plugin instance
        """
        self._plugin = plugin
        self._logger = logging.getLogger("NovaChat.ExtensionLoader")
        self._loaded_extensions: Dict[str, NovaChatExtension] = {}
        self._enabled_extensions: Dict[str, bool] = {}
    
    def load_extensions(self, extensions_dir: str) -> List[NovaChatExtension]:
        """
        Loads all extensions from the extensions directory.
        
        This method will:
        1. Scan the directory for subdirectories containing extension.yml
        2. Parse extension.yml from each directory
        3. Load the main Python module
        4. Create extension instances
        
        Extensions that fail to load will be logged but will not prevent
        other extensions from loading (isolation property).
        
        Args:
            extensions_dir: The path to the extensions directory
            
        Returns:
            List of successfully loaded extensions
        """
        extensions: List[NovaChatExtension] = []
        extensions_path = Path(extensions_dir)
        
        # Create extensions directory if it doesn't exist
        if not extensions_path.exists():
            try:
                extensions_path.mkdir(parents=True, exist_ok=True)
            except OSError as e:
                self._logger.warning(f"Failed to create extensions directory: {e}")
            return extensions
        
        # Scan for extension directories
        try:
            for item in extensions_path.iterdir():
                if not item.is_dir():
                    continue
                
                try:
                    extension = self._load_extension(item)
                    if extension is not None:
                        extensions.append(extension)
                        self._loaded_extensions[extension.get_meta().id] = extension
                        self._logger.info(
                            f"Loaded extension: {extension.get_meta().name} "
                            f"v{extension.get_meta().version}"
                        )
                except ExtensionException as e:
                    # Log error but continue loading other extensions (isolation)
                    self._logger.warning(f"Failed to load extension from {item.name}: {e}")
                except Exception as e:
                    # Catch any other errors to ensure isolation
                    self._logger.warning(
                        f"Unexpected error loading extension from {item.name}: {e}"
                    )
        except OSError as e:
            self._logger.error(f"Failed to scan extensions directory: {e}")
        
        return extensions
    
    def _load_extension(self, ext_path: Path) -> Optional[NovaChatExtension]:
        """
        Loads a single extension from a directory.
        
        Args:
            ext_path: Path to the extension directory
            
        Returns:
            The loaded extension, or None if not an extension directory
            
        Raises:
            ExtensionException: If the extension cannot be loaded
        """
        meta_file = ext_path / self.EXTENSION_YML
        
        # Check for extension.yml
        if not meta_file.exists():
            return None  # Not an extension directory
        
        # Parse metadata
        meta = self._load_meta(meta_file)
        
        # Check for duplicate ID
        if meta.id in self._loaded_extensions:
            raise ExtensionException(f"Duplicate extension ID: {meta.id}", meta.id)
        
        # Load the main module
        main_file = ext_path / f"{meta.main}.py"
        if not main_file.exists():
            raise ExtensionException(
                f"Main module file not found: {meta.main}.py",
                meta.id
            )
        
        # Add extension directory to sys.path temporarily
        ext_dir_str = str(ext_path)
        if ext_dir_str not in sys.path:
            sys.path.insert(0, ext_dir_str)
        
        try:
            # Load the module
            spec = importlib.util.spec_from_file_location(meta.main, main_file)
            if spec is None or spec.loader is None:
                raise ExtensionException(
                    f"Failed to load module spec for {meta.main}",
                    meta.id
                )
            
            module = importlib.util.module_from_spec(spec)
            sys.modules[meta.main] = module
            spec.loader.exec_module(module)
            
            # Find the extension class
            extension_class = None
            for attr_name in dir(module):
                attr = getattr(module, attr_name)
                if (isinstance(attr, type) and 
                    issubclass(attr, NovaChatExtension) and 
                    attr is not NovaChatExtension):
                    extension_class = attr
                    break
            
            if extension_class is None:
                raise ExtensionException(
                    f"No NovaChatExtension subclass found in {meta.main}",
                    meta.id
                )
            
            # Create instance
            extension = extension_class()
            extension.set_meta(meta)
            extension.set_plugin(self._plugin)
            
            return extension
            
        except ImportError as e:
            raise ExtensionException(
                f"Failed to import module {meta.main}: {e}",
                meta.id,
                e
            )
        finally:
            # Remove from sys.path
            if ext_dir_str in sys.path:
                sys.path.remove(ext_dir_str)
    
    def _load_meta(self, meta_file: Path) -> ExtensionMeta:
        """
        Loads extension metadata from extension.yml.
        
        Args:
            meta_file: Path to the extension.yml file
            
        Returns:
            The parsed metadata
            
        Raises:
            ExtensionException: If metadata cannot be loaded or parsed
        """
        try:
            with open(meta_file, 'r', encoding='utf-8') as f:
                data = yaml.safe_load(f)
            
            if not data:
                raise ExtensionException("Empty or invalid extension.yml")
            
            return ExtensionMeta.from_dict(data)
            
        except yaml.YAMLError as e:
            raise ExtensionException(f"Failed to parse extension.yml: {e}")
        except OSError as e:
            raise ExtensionException(f"Failed to read extension.yml: {e}")
    
    def enable_extension(self, extension: NovaChatExtension) -> None:
        """
        Enables a specific extension.
        
        Args:
            extension: The extension to enable
            
        Raises:
            ExtensionException: If the extension fails to enable
        """
        ext_id = extension.get_meta().id
        
        if self._enabled_extensions.get(ext_id, False):
            return  # Already enabled
        
        # Check dependencies
        for dep_id in extension.get_meta().dependencies:
            if not self._enabled_extensions.get(dep_id, False):
                raise ExtensionException(f"Missing dependency: {dep_id}", ext_id)
        
        try:
            extension.on_enable()
            self._enabled_extensions[ext_id] = True
            self._logger.info(f"Enabled extension: {extension.get_meta().name}")
        except Exception as e:
            raise ExtensionException(
                f"Failed to enable extension: {e}",
                ext_id,
                e
            )
    
    def disable_extension(self, extension: NovaChatExtension) -> None:
        """
        Disables a specific extension.
        
        Args:
            extension: The extension to disable
        """
        ext_id = extension.get_meta().id
        
        if not self._enabled_extensions.get(ext_id, False):
            return  # Not enabled
        
        try:
            extension.on_disable()
            self._logger.info(f"Disabled extension: {extension.get_meta().name}")
        except Exception as e:
            self._logger.warning(
                f"Error disabling extension {extension.get_meta().name}: {e}"
            )
        
        self._enabled_extensions[ext_id] = False
    
    def enable_all_extensions(self) -> None:
        """
        Enables all loaded extensions.
        Extensions are enabled in dependency order.
        """
        sorted_extensions = self._sort_by_dependencies()
        
        for extension in sorted_extensions:
            try:
                self.enable_extension(extension)
            except ExtensionException as e:
                self._logger.warning(f"Failed to enable extension: {e}")
    
    def disable_all_extensions(self) -> None:
        """
        Disables all enabled extensions.
        Extensions are disabled in reverse dependency order.
        """
        sorted_extensions = list(reversed(self._sort_by_dependencies()))
        
        for extension in sorted_extensions:
            self.disable_extension(extension)
    
    def _sort_by_dependencies(self) -> List[NovaChatExtension]:
        """
        Sorts extensions by dependencies (topological sort).
        
        Returns:
            Sorted list of extensions
        """
        sorted_list: List[NovaChatExtension] = []
        visited: Dict[str, bool] = {}
        
        for extension in self._loaded_extensions.values():
            self._visit_extension(extension, visited, sorted_list)
        
        return sorted_list
    
    def _visit_extension(
        self,
        extension: NovaChatExtension,
        visited: Dict[str, bool],
        sorted_list: List[NovaChatExtension]
    ) -> None:
        """
        Helper for topological sort.
        
        Args:
            extension: The extension to visit
            visited: Visited map
            sorted_list: Sorted list being built
        """
        ext_id = extension.get_meta().id
        
        if ext_id in visited:
            return
        
        visited[ext_id] = True
        
        # Visit dependencies first
        for dep_id in extension.get_meta().dependencies:
            if dep_id in self._loaded_extensions:
                self._visit_extension(
                    self._loaded_extensions[dep_id],
                    visited,
                    sorted_list
                )
        
        sorted_list.append(extension)
    
    def get_loaded_extensions(self) -> List[NovaChatExtension]:
        """
        Gets all currently loaded extensions.
        
        Returns:
            List of loaded extensions
        """
        return list(self._loaded_extensions.values())
    
    def get_extension(self, ext_id: str) -> Optional[NovaChatExtension]:
        """
        Gets an extension by its ID.
        
        Args:
            ext_id: The extension ID
            
        Returns:
            The extension, or None if not found
        """
        return self._loaded_extensions.get(ext_id)
    
    def is_extension_enabled(self, ext_id: str) -> bool:
        """
        Checks if an extension is enabled.
        
        Args:
            ext_id: The extension ID
            
        Returns:
            True if enabled
        """
        return self._enabled_extensions.get(ext_id, False)
