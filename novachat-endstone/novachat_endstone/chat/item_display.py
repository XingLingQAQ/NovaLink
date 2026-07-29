"""
Bedrock Edition item display renderer for Endstone.

Since Bedrock Edition doesn't support HoverEvent like Java Edition,
this renderer provides alternative approaches:
- Formatted text with item details inline
- Form-based item preview on click (future)
- Action bar/popup for quick item info

Requirements: 12.4 - Item display SHALL support Bedrock Edition alternatives.
"""

import re
from typing import Optional, List, Dict, Any
from dataclasses import dataclass


# Permission nodes
PERMISSION_ITEM = "novachat.feature.item"
PERMISSION_INVENTORY = "novachat.feature.inventory"
PERMISSION_ENDERCHEST = "novachat.feature.enderchest"

# Pattern for item tags
ITEM_PATTERN = re.compile(r'\[(item|i)\]', re.IGNORECASE)


@dataclass
class ItemData:
    """Platform-agnostic representation of a Minecraft item."""
    
    type: str = "minecraft:air"
    display_name: Optional[str] = None
    amount: int = 1
    damage: int = 0
    lore: Optional[List[str]] = None
    enchantments: Optional[Dict[str, int]] = None
    custom_model_data: Optional[int] = None
    unbreakable: bool = False
    extra_nbt: Optional[str] = None
    
    def is_empty(self) -> bool:
        """Check if this item is empty/air."""
        return (
            self.type is None or 
            self.type == "" or 
            self.type == "minecraft:air" or 
            self.type == "AIR" or
            self.amount <= 0
        )
    
    def has_display_name(self) -> bool:
        """Check if this item has a custom display name."""
        return self.display_name is not None and self.display_name != ""
    
    def has_lore(self) -> bool:
        """Check if this item has lore."""
        return self.lore is not None and len(self.lore) > 0
    
    def has_enchantments(self) -> bool:
        """Check if this item has enchantments."""
        return self.enchantments is not None and len(self.enchantments) > 0
    
    def get_simple_type(self) -> str:
        """Get the simple type name without namespace."""
        if self.type is None:
            return "Unknown"
        colon_index = self.type.find(':')
        return self.type[colon_index + 1:] if colon_index >= 0 else self.type


class ItemDisplayRenderer:
    """
    Bedrock Edition item display renderer for Endstone.
    
    Provides text-based item display since Bedrock doesn't support hover events.
    """
    
    PLATFORM_TYPE = "endstone"
    
    @staticmethod
    def render_item_display(item_data: Optional[ItemData], format_template: str) -> str:
        """
        Renders an item display for Bedrock Edition.
        
        Args:
            item_data: The item data to display
            format_template: The display format template (e.g., "&b[{item_name}]")
            
        Returns:
            The rendered display text
        """
        if item_data is None or item_data.is_empty():
            return ItemDisplayRenderer._render_empty_item(format_template)
        
        item_name = (
            item_data.display_name 
            if item_data.has_display_name() 
            else ItemDisplayRenderer._format_type_name(item_data.get_simple_type())
        )
        
        result = format_template.replace("{item_name}", item_name)
        
        if item_data.amount > 1:
            result = result.replace("{amount}", str(item_data.amount))
        else:
            result = result.replace(" x{amount}", "").replace("{amount}", "1")
        
        return ItemDisplayRenderer._translate_colors(result)
    
    @staticmethod
    def _render_empty_item(format_template: str) -> str:
        """Render an empty item placeholder."""
        result = format_template.replace("{item_name}", "§7§oEmpty")
        result = result.replace("{amount}", "0").replace(" x{amount}", "")
        return ItemDisplayRenderer._translate_colors(result)
    
    @staticmethod
    def process_message(message: str, item_data: Optional[ItemData], format_template: str) -> str:
        """
        Process a message and replace item tags with rendered item displays.
        
        Args:
            message: The original message with [item] or [i] tags
            item_data: The item data to display
            format_template: The display format template
            
        Returns:
            The processed message
        """
        if not ItemDisplayRenderer.has_item_tag(message):
            return ItemDisplayRenderer._translate_colors(message)
        
        item_display = ItemDisplayRenderer.render_item_display(item_data, format_template)
        return ITEM_PATTERN.sub(item_display, message)
    
    @staticmethod
    def has_item_tag(message: str) -> bool:
        """Check if a message contains item display tags."""
        if message is None or message == "":
            return False
        return ITEM_PATTERN.search(message) is not None
    
    @staticmethod
    def build_item_details(item_data: ItemData) -> str:
        """
        Build detailed item information text for Bedrock.
        Used for popup/form displays since hover isn't available.
        
        Args:
            item_data: The item to describe
            
        Returns:
            Detailed item information string
        """
        lines = []
        
        # Item name with color
        if item_data.has_display_name():
            lines.append(f"§b{item_data.display_name}")
        else:
            lines.append(f"§f{ItemDisplayRenderer._format_type_name(item_data.get_simple_type())}")
        
        # Amount
        if item_data.amount > 1:
            lines.append(f"§7Amount: {item_data.amount}")
        
        # Enchantments
        if item_data.has_enchantments():
            for enchant, level in item_data.enchantments.items():
                enchant_name = ItemDisplayRenderer._format_enchantment_name(enchant)
                roman = ItemDisplayRenderer._to_roman_numeral(level)
                lines.append(f"§7{enchant_name} {roman}")
        
        # Lore
        if item_data.has_lore():
            for line in item_data.lore:
                lines.append(f"§5§o{line}")
        
        # Unbreakable
        if item_data.unbreakable:
            lines.append("§9Unbreakable")
        
        return "\n".join(lines)
    
    @staticmethod
    def _format_type_name(type_name: str) -> str:
        """Format a type name to be more readable."""
        if type_name is None or type_name == "":
            return "Unknown"
        
        parts = type_name.split("_")
        formatted_parts = [part.capitalize() for part in parts if part]
        return " ".join(formatted_parts)
    
    @staticmethod
    def _format_enchantment_name(enchantment: str) -> str:
        """Format an enchantment name to be more readable."""
        if enchantment is None:
            return "Unknown"
        
        # Remove namespace
        colon_index = enchantment.find(':')
        name = enchantment[colon_index + 1:] if colon_index >= 0 else enchantment
        
        return ItemDisplayRenderer._format_type_name(name)
    
    @staticmethod
    def _to_roman_numeral(number: int) -> str:
        """Convert a number to Roman numeral."""
        if number <= 0 or number > 10:
            return str(number)
        
        numerals = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"]
        return numerals[number - 1]
    
    @staticmethod
    def _translate_colors(text: str) -> str:
        """Translate color codes in a string."""
        if text is None:
            return ""
        
        # Convert & to § for color codes
        result = text
        for code in "0123456789abcdefklmnor":
            result = result.replace(f"&{code}", f"§{code}")
            result = result.replace(f"&{code.upper()}", f"§{code}")
        
        return result
    
    @staticmethod
    def supports_hover_event() -> bool:
        """Check if this renderer supports hover events."""
        return False
    
    @staticmethod
    def get_platform_type() -> str:
        """Get the platform type this renderer is for."""
        return ItemDisplayRenderer.PLATFORM_TYPE


class ItemDisplayPermissionChecker:
    """
    Permission checker for item display functionality.
    
    Requirements: 12.5 - When a player doesn't have display permission,
    the system SHALL treat display tags as plain text.
    """
    
    def __init__(self, permission_provider):
        """
        Create a new permission checker.
        
        Args:
            permission_provider: Callable that takes (player_id, permission) and returns bool
        """
        self._permission_provider = permission_provider
    
    def can_display_item(self, player_id: str) -> bool:
        """Check if a player can use item display."""
        return self._permission_provider(player_id, PERMISSION_ITEM)
    
    def can_display_inventory(self, player_id: str) -> bool:
        """Check if a player can use inventory display."""
        return self._permission_provider(player_id, PERMISSION_INVENTORY)
    
    def can_display_enderchest(self, player_id: str) -> bool:
        """Check if a player can use enderchest display."""
        return self._permission_provider(player_id, PERMISSION_ENDERCHEST)
    
    def should_process_item_display(self, player_id: str, message: str) -> bool:
        """Check if item display should be processed for this player and message."""
        if not self.can_display_item(player_id):
            return False
        return ItemDisplayRenderer.has_item_tag(message)
    
    def process_with_permission(
        self, 
        player_id: str, 
        message: str, 
        item_data: Optional[ItemData],
        format_template: str
    ) -> str:
        """
        Process a message with permission checking.
        
        If player doesn't have permission, tags are treated as plain text.
        
        Args:
            player_id: The player identifier
            message: The message to process
            item_data: The item data to display
            format_template: The display format template
            
        Returns:
            The processed message
        """
        if not self.can_display_item(player_id):
            # No permission - treat tags as plain text
            return ItemDisplayRenderer._translate_colors(message)
        
        return ItemDisplayRenderer.process_message(message, item_data, format_template)
