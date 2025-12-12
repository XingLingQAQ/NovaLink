package com.nova.chat.nukkit.chat;

import cn.nukkit.Player;
import cn.nukkit.item.Item;
import cn.nukkit.item.enchantment.Enchantment;
import cn.nukkit.utils.TextFormat;
import com.nova.chat.common.chat.ItemData;
import com.nova.chat.common.chat.ItemDisplayParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bedrock Edition item display renderer for Nukkit.
 * 
 * Since Bedrock Edition doesn't support HoverEvent like Java Edition,
 * this renderer provides alternative approaches:
 * - Formatted text with item details inline
 * - Form-based item preview on click (future)
 * - Popup/tip for quick item info
 * 
 * Requirements: 12.4 - Item display SHALL support Bedrock Edition alternatives.
 */
public class ItemDisplayRenderer {

    /** Permission node for item display */
    public static final String PERMISSION_ITEM = "novachat.feature.item";
    
    /** Permission node for inventory display */
    public static final String PERMISSION_INVENTORY = "novachat.feature.inventory";
    
    /** Permission node for enderchest display */
    public static final String PERMISSION_ENDERCHEST = "novachat.feature.enderchest";

    private static final String PLATFORM_TYPE = "nukkit";
    private static final Pattern ITEM_PATTERN = Pattern.compile(
        "\\[(item|i)\\]",
        Pattern.CASE_INSENSITIVE
    );

    private final ItemDisplayParser parser;

    /**
     * Creates a new ItemDisplayRenderer.
     */
    public ItemDisplayRenderer() {
        this.parser = new ItemDisplayParser();
    }

    /**
     * Renders an item display for Bedrock Edition.
     * Returns formatted text with item information.
     * 
     * @param item the item to display
     * @param format the display format template
     * @return the rendered display text
     */
    public String renderItemDisplay(Item item, String format) {
        if (item == null || item.isNull()) {
            return renderEmptyItem(format);
        }

        String itemName = item.hasCustomName() 
            ? item.getCustomName() 
            : item.getName();
        
        String result = format.replace("{item_name}", itemName);
        
        if (item.getCount() > 1) {
            result = result.replace("{amount}", String.valueOf(item.getCount()));
        } else {
            result = result.replace(" x{amount}", "").replace("{amount}", "1");
        }
        
        return translateColors(result);
    }

    /**
     * Renders an item display from ItemData (cross-platform format).
     * 
     * @param itemData the item data to display
     * @param format the display format template
     * @return the rendered display text
     */
    public String renderItemDisplay(ItemData itemData, String format) {
        if (itemData == null || itemData.isEmpty()) {
            return renderEmptyItem(format);
        }

        String itemName = itemData.hasDisplayName() 
            ? itemData.getDisplayName() 
            : formatTypeName(itemData.getSimpleType());
        
        String result = format.replace("{item_name}", itemName);
        
        if (itemData.getAmount() > 1) {
            result = result.replace("{amount}", String.valueOf(itemData.getAmount()));
        } else {
            result = result.replace(" x{amount}", "").replace("{amount}", "1");
        }
        
        return translateColors(result);
    }

    /**
     * Renders an empty item placeholder.
     * 
     * @param format the display format template
     * @return the rendered empty item text
     */
    private String renderEmptyItem(String format) {
        String result = format.replace("{item_name}", TextFormat.GRAY + "" + TextFormat.ITALIC + "Empty");
        result = result.replace("{amount}", "0").replace(" x{amount}", "");
        return translateColors(result);
    }

    /**
     * Processes a message and replaces item tags with rendered item displays.
     * 
     * @param message the original message with [item] or [i] tags
     * @param item the item to display
     * @param format the display format template
     * @return the processed message
     */
    public String processMessage(String message, Item item, String format) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        if (!parser.hasItemTag(message)) {
            return translateColors(message);
        }

        String itemDisplay = renderItemDisplay(item, format);
        return ITEM_PATTERN.matcher(message).replaceAll(itemDisplay);
    }

    /**
     * Processes a message with ItemData (cross-platform format).
     * 
     * @param message the original message with [item] or [i] tags
     * @param itemData the item data to display
     * @param format the display format template
     * @return the processed message
     */
    public String processMessage(String message, ItemData itemData, String format) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        if (!parser.hasItemTag(message)) {
            return translateColors(message);
        }

        String itemDisplay = renderItemDisplay(itemData, format);
        return ITEM_PATTERN.matcher(message).replaceAll(itemDisplay);
    }

    /**
     * Builds detailed item information text for Bedrock.
     * Used for popup/form displays since hover isn't available.
     * 
     * @param item the item to describe
     * @return detailed item information
     */
    public String buildItemDetails(Item item) {
        if (item == null || item.isNull()) {
            return TextFormat.GRAY + "" + TextFormat.ITALIC + "Empty";
        }

        List<String> lines = new ArrayList<>();
        
        // Item name with color
        if (item.hasCustomName()) {
            lines.add(TextFormat.AQUA + item.getCustomName());
        } else {
            lines.add(TextFormat.WHITE + item.getName());
        }
        
        // Amount
        if (item.getCount() > 1) {
            lines.add(TextFormat.GRAY + "Amount: " + item.getCount());
        }
        
        // Enchantments
        Enchantment[] enchantments = item.getEnchantments();
        if (enchantments != null && enchantments.length > 0) {
            for (Enchantment enchantment : enchantments) {
                String enchantName = enchantment.getName();
                String level = toRomanNumeral(enchantment.getLevel());
                lines.add(TextFormat.GRAY + enchantName + " " + level);
            }
        }
        
        // Lore
        String[] lore = item.getLore();
        if (lore != null && lore.length > 0) {
            for (String line : lore) {
                lines.add(TextFormat.DARK_PURPLE + "" + TextFormat.ITALIC + line);
            }
        }
        
        return String.join("\n", lines);
    }

    /**
     * Builds detailed item information from ItemData.
     * 
     * @param itemData the item data to describe
     * @return detailed item information
     */
    public String buildItemDetails(ItemData itemData) {
        if (itemData == null || itemData.isEmpty()) {
            return TextFormat.GRAY + "" + TextFormat.ITALIC + "Empty";
        }

        List<String> lines = new ArrayList<>();
        
        // Item name with color
        if (itemData.hasDisplayName()) {
            lines.add(TextFormat.AQUA + itemData.getDisplayName());
        } else {
            lines.add(TextFormat.WHITE + formatTypeName(itemData.getSimpleType()));
        }
        
        // Amount
        if (itemData.getAmount() > 1) {
            lines.add(TextFormat.GRAY + "Amount: " + itemData.getAmount());
        }
        
        // Enchantments
        if (itemData.hasEnchantments()) {
            for (Map.Entry<String, Integer> entry : itemData.getEnchantments().entrySet()) {
                String enchantName = formatEnchantmentName(entry.getKey());
                String level = toRomanNumeral(entry.getValue());
                lines.add(TextFormat.GRAY + enchantName + " " + level);
            }
        }
        
        // Lore
        if (itemData.hasLore()) {
            for (String line : itemData.getLore()) {
                lines.add(TextFormat.DARK_PURPLE + "" + TextFormat.ITALIC + line);
            }
        }
        
        // Unbreakable
        if (itemData.isUnbreakable()) {
            lines.add(TextFormat.BLUE + "Unbreakable");
        }
        
        return String.join("\n", lines);
    }

    /**
     * Sends item details as a popup to a player.
     * This is the Bedrock alternative to hover events.
     * 
     * @param player the player to send to
     * @param item the item to display
     */
    public void sendItemPopup(Player player, Item item) {
        String details = buildItemDetails(item);
        player.sendPopup(details);
    }

    /**
     * Sends item details as a tip to a player.
     * 
     * @param player the player to send to
     * @param item the item to display
     */
    public void sendItemTip(Player player, Item item) {
        String details = buildItemDetails(item);
        player.sendTip(details);
    }

    /**
     * Sends item details as an action bar message.
     * 
     * @param player the player to send to
     * @param item the item to display
     */
    public void sendItemActionBar(Player player, Item item) {
        String name = item.hasCustomName() ? item.getCustomName() : item.getName();
        String count = item.getCount() > 1 ? " x" + item.getCount() : "";
        player.sendActionBar(TextFormat.AQUA + name + count);
    }

    /**
     * Checks if a player has permission to use item display.
     * 
     * @param player the player to check
     * @return true if player has permission
     */
    public boolean hasItemPermission(Player player) {
        return player.hasPermission(PERMISSION_ITEM);
    }

    /**
     * Checks if a player has permission to use inventory display.
     * 
     * @param player the player to check
     * @return true if player has permission
     */
    public boolean hasInventoryPermission(Player player) {
        return player.hasPermission(PERMISSION_INVENTORY);
    }

    /**
     * Checks if a player has permission to use enderchest display.
     * 
     * @param player the player to check
     * @return true if player has permission
     */
    public boolean hasEnderchestPermission(Player player) {
        return player.hasPermission(PERMISSION_ENDERCHEST);
    }

    /**
     * Processes a message with permission checking.
     * If player doesn't have permission, tags are treated as plain text.
     * 
     * @param player the player sending the message
     * @param message the message to process
     * @param format the display format template
     * @return the processed message
     */
    public String processWithPermission(Player player, String message, String format) {
        if (!hasItemPermission(player)) {
            // No permission - treat tags as plain text
            return translateColors(message);
        }

        if (!parser.hasItemTag(message)) {
            return translateColors(message);
        }

        Item item = player.getInventory().getItemInHand();
        return processMessage(message, item, format);
    }

    /**
     * Formats a type name to be more readable.
     * e.g., "diamond_sword" -> "Diamond Sword"
     * 
     * @param typeName the raw type name
     * @return formatted type name
     */
    private String formatTypeName(String typeName) {
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
     * Formats an enchantment name to be more readable.
     * 
     * @param enchantment the enchantment ID
     * @return formatted enchantment name
     */
    private String formatEnchantmentName(String enchantment) {
        if (enchantment == null) {
            return "Unknown";
        }
        
        // Remove namespace
        int colonIndex = enchantment.indexOf(':');
        String name = colonIndex >= 0 ? enchantment.substring(colonIndex + 1) : enchantment;
        
        return formatTypeName(name);
    }

    /**
     * Converts a number to Roman numeral.
     * 
     * @param number the number to convert
     * @return Roman numeral string
     */
    private String toRomanNumeral(int number) {
        if (number <= 0 || number > 10) {
            return String.valueOf(number);
        }
        
        String[] numerals = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return numerals[number - 1];
    }

    /**
     * Translates color codes in a string.
     * 
     * @param text the text to translate
     * @return translated text
     */
    private String translateColors(String text) {
        if (text == null) {
            return "";
        }
        return TextFormat.colorize(text);
    }

    /**
     * Checks if this renderer supports hover events.
     * 
     * @return false for Bedrock Edition
     */
    public boolean supportsHoverEvent() {
        return false;
    }

    /**
     * Gets the platform type this renderer is for.
     * 
     * @return the platform type identifier
     */
    public String getPlatformType() {
        return PLATFORM_TYPE;
    }
}
