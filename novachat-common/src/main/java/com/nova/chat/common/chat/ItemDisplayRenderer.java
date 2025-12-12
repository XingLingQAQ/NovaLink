package com.nova.chat.common.chat;

/**
 * Interface for rendering item displays in chat messages.
 * 
 * Platform-specific implementations should provide the actual rendering logic
 * for displaying items in chat. Java Edition uses HoverEvent, while Bedrock
 * Edition uses alternative approaches like formatted text.
 * 
 * Requirements: 12.3, 12.4 - Item display SHALL include item name, enchantments,
 * Lore and complete information. SHALL support Java Edition HoverEvent and
 * Bedrock Edition alternatives.
 */
public interface ItemDisplayRenderer {

    /**
     * Renders an item display for a chat message.
     * 
     * @param itemData the item data to render
     * @param format the display format template (e.g., "&b[{item_name}]")
     * @return the rendered display component/string
     */
    Object renderItemDisplay(ItemData itemData, String format);

    /**
     * Processes a message and replaces item tags with rendered item displays.
     * 
     * @param message the original message with [item] or [i] tags
     * @param itemData the item data to display
     * @param format the display format template
     * @return the processed message with item displays
     */
    Object processMessage(String message, ItemData itemData, String format);

    /**
     * Checks if this renderer supports hover events (Java Edition feature).
     * 
     * @return true if hover events are supported
     */
    boolean supportsHoverEvent();

    /**
     * Gets the platform type this renderer is for.
     * 
     * @return the platform type identifier
     */
    String getPlatformType();

    /**
     * Creates a simple text representation of the item for platforms
     * that don't support rich text features.
     * 
     * @param itemData the item data
     * @param format the display format template
     * @return plain text representation
     */
    default String renderPlainText(ItemData itemData, String format) {
        if (itemData == null || itemData.isEmpty()) {
            return format.replace("{item_name}", "Empty");
        }
        
        String itemName = itemData.hasDisplayName() 
            ? itemData.getDisplayName() 
            : formatTypeName(itemData.getSimpleType());
        
        String result = format.replace("{item_name}", itemName);
        
        // Add amount if more than 1
        if (itemData.getAmount() > 1) {
            result = result.replace("{amount}", String.valueOf(itemData.getAmount()));
        } else {
            result = result.replace(" x{amount}", "").replace("{amount}", "1");
        }
        
        return result;
    }

    /**
     * Formats a type name to be more readable.
     * e.g., "diamond_sword" -> "Diamond Sword"
     * 
     * @param typeName the raw type name
     * @return formatted type name
     */
    default String formatTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "Unknown";
        }
        
        StringBuilder result = new StringBuilder();
        String[] parts = typeName.split("_");
        
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                result.append(" ");
            }
            String part = parts[i];
            if (!part.isEmpty()) {
                result.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    result.append(part.substring(1).toLowerCase());
                }
            }
        }
        
        return result.toString();
    }

    /**
     * Builds a tooltip/hover text for the item.
     * 
     * @param itemData the item data
     * @return the tooltip text with all item information
     */
    default String buildTooltipText(ItemData itemData) {
        if (itemData == null || itemData.isEmpty()) {
            return "Empty";
        }
        
        StringBuilder tooltip = new StringBuilder();
        
        // Item name (with color if custom)
        if (itemData.hasDisplayName()) {
            tooltip.append(itemData.getDisplayName());
        } else {
            tooltip.append(formatTypeName(itemData.getSimpleType()));
        }
        
        // Enchantments
        if (itemData.hasEnchantments()) {
            for (var entry : itemData.getEnchantments().entrySet()) {
                tooltip.append("\n§7").append(formatEnchantmentName(entry.getKey()))
                       .append(" ").append(toRomanNumeral(entry.getValue()));
            }
        }
        
        // Lore
        if (itemData.hasLore()) {
            for (String line : itemData.getLore()) {
                tooltip.append("\n§5§o").append(line);
            }
        }
        
        // Unbreakable
        if (itemData.isUnbreakable()) {
            tooltip.append("\n§9Unbreakable");
        }
        
        return tooltip.toString();
    }

    /**
     * Formats an enchantment name to be more readable.
     * e.g., "minecraft:sharpness" -> "Sharpness"
     * 
     * @param enchantment the enchantment ID
     * @return formatted enchantment name
     */
    default String formatEnchantmentName(String enchantment) {
        if (enchantment == null) {
            return "Unknown";
        }
        
        // Remove namespace
        int colonIndex = enchantment.indexOf(':');
        String name = colonIndex >= 0 ? enchantment.substring(colonIndex + 1) : enchantment;
        
        return formatTypeName(name);
    }

    /**
     * Converts a number to Roman numeral (for enchantment levels).
     * 
     * @param number the number to convert
     * @return Roman numeral string
     */
    default String toRomanNumeral(int number) {
        if (number <= 0 || number > 10) {
            return String.valueOf(number);
        }
        
        String[] numerals = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return numerals[number - 1];
    }
}
