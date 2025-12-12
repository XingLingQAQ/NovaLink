"""
Extension metadata module.

This module defines the ExtensionMeta class for storing extension metadata.

Requirements: 10.2 - THE Endstone 扩展 SHALL 使用 Python 编写并放置在 extensions 目录
"""

from dataclasses import dataclass, field
from typing import List, Dict, Any, Optional

from novachat_endstone.extension.extension_exception import ExtensionException


@dataclass
class ExtensionMeta:
    """
    Metadata for a NovaChat extension.
    This information is typically loaded from an extension.yml file.
    
    Example extension.yml:
    ```yaml
    id: my-extension
    name: My Custom Extension
    version: 1.0.0
    author: Developer
    description: A custom NovaChat extension
    main: my_extension
    dependencies:
      - other-extension
    ```
    """
    
    id: str
    name: str
    version: str
    main: str
    author: str = ""
    description: str = ""
    dependencies: List[str] = field(default_factory=list)
    
    def to_dict(self) -> Dict[str, Any]:
        """
        Converts the metadata to a dictionary for serialization.
        
        Returns:
            Dictionary representation of the metadata
        """
        return {
            'id': self.id,
            'name': self.name,
            'version': self.version,
            'author': self.author,
            'description': self.description,
            'main': self.main,
            'dependencies': self.dependencies,
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'ExtensionMeta':
        """
        Creates an ExtensionMeta from a dictionary (e.g., parsed YAML).
        
        Args:
            data: The data dictionary
            
        Returns:
            The created metadata
            
        Raises:
            ExtensionException: If required fields are missing
        """
        if 'id' not in data or not isinstance(data['id'], str):
            raise ExtensionException("Missing required field: id")
        if 'name' not in data or not isinstance(data['name'], str):
            raise ExtensionException("Missing required field: name")
        if 'version' not in data or not isinstance(data['version'], str):
            raise ExtensionException("Missing required field: version")
        if 'main' not in data or not isinstance(data['main'], str):
            raise ExtensionException("Missing required field: main")
        
        dependencies = []
        if 'dependencies' in data and isinstance(data['dependencies'], list):
            dependencies = [dep for dep in data['dependencies'] if isinstance(dep, str)]
        
        return cls(
            id=data['id'],
            name=data['name'],
            version=data['version'],
            main=data['main'],
            author=data.get('author', ''),
            description=data.get('description', ''),
            dependencies=dependencies,
        )
