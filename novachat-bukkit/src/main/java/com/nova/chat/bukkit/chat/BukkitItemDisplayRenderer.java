package com.nova.chat.bukkit.chat;

import com.nova.chat.common.chat.ItemData;
import com.nova.chat.common.chat.ItemDisplayParser;
import com.nova.chat.common.chat.ItemDisplayRenderer;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bukkit/Spigot implementation of ItemDisplayRenderer using HoverEvent.
 * 
 * This renderer creates clickable/hoverable item displays in chat using
 * the Spigot Chat Component API with HoverEvent for showing item tooltips.
 * 
 * Requirements: 12.3 - When other players hover over item display,
 * the system SHALL show complete item Tooltip.
 * Requirements: 12.4 - Item display SHALL support Java Edition HoverEvent.
 */
public class BukkitItemDisplayRenderer implements ItemDisplayRenderer {

    private static final String PLATFORM_TYPE = "bukkit";
    private static final Pattern ITEM_PATTERN = Pattern.compile(
        "\\[(item|i)\\]",
        Pattern.CASE_INSENSITIVE
    );

    private final ItemDisplayParser parser;

    /**
     * Creates a new BukkitItemDisplayRenderer.
     */
    public BukkitItemDisplayRenderer() {
        this.parser = new ItemDisplayParser();
    }

    @Override
    public Object renderItemDisplay(ItemData itemData, String format) {
        if (itemData == null || itemData.isEmpty()) {
            return createEmptyItemComponent(format);
        }

        // Build the display text
        String displayText = buildDisplayText(itemData, format);
        TextComponent component = new TextComponent(translateColors(displayText));

        // Build the hover tooltip
        BaseComponent[] tooltip = buildHoverTooltip(itemData);
        HoverEvent hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(tooltip));
        component.setHoverEvent(hoverEvent);

        return component;
    }

    @Override
    public Object processMessage(String message, ItemData itemData, String format) {
        if (message == null || message.isEmpty()) {
            return new TextComponent(message != null ? message : "");
        }

        if (!parser.hasItemTag(message)) {
            return new TextComponent(translateColors(message));
        }

        // Split message by item tags and rebuild with components
        List<BaseComponent> components = new ArrayList<>();
        List<ItemDisplayParser.ItemTagPosition> positions = parser.getItemTagPositions(message);
        
        int lastEnd = 0;
        for (ItemDisplayParser.ItemTagPosition pos : positions) {
            // Add text before the tag
            if (pos.getStart() > lastEnd) {
                String beforeText = message.substring(lastEnd, pos.getStart());
                components.add(new TextComponent(translateColors(beforeText)));
            }
            
            // Add the item display component
            BaseComponent itemComponent = (BaseComponent) renderItemDisplay(itemData, format);
            components.add(itemComponent);
            
            lastEnd = pos.getEnd();
        }
        
        // Add remaining text after last tag
        if (lastEnd < message.length()) {
            String afterText = message.substring(lastEnd);
            components.add(new TextComponent(translateColors(afterText)));
        }

        // Combine all components
        TextComponent result = new TextComponent();
        for (BaseComponent comp : components) {
            result.addExtra(comp);
        }
        
        return result;
    }

    @Override
    public boolean supportsHoverEvent() {
        return true;
    }

    @Override
    public String getPlatformType() {
        return PLATFORM_TYPE;
    }

    /**
     * Builds the display text for the item.
     * 
     * @param itemData the item data
     * @param format the format template
     * @return the formatted display text
     */
    private String buildDisplayText(ItemData itemData, String format) {
        String itemName = itemData.hasDisplayName() 
            ? itemData.getDisplayName() 
            : formatTypeName(itemData.getSimpleType());
        
        String result = format.replace("{item_name}", itemName);
        
        if (itemData.getAmount() > 1) {
            result = result.replace("{amount}", String.valueOf(itemData.getAmount()));
        } else {
            result = result.replace(" x{amount}", "").replace("{amount}", "1");
        }
        
        return result;
    }

    /**
     * Builds the hover tooltip components for the item.
     * 
     * @param itemData the item data
     * @return array of BaseComponent for the tooltip
     */
    private BaseComponent[] buildHoverTooltip(ItemData itemData) {
        ComponentBuilder builder = new ComponentBuilder();
        
        // Item name (with rarity color if applicable)
        if (itemData.hasDisplayName()) {
            builder.append(itemData.getDisplayName()).color(ChatColor.AQUA);
        } else {
            builder.append(formatTypeName(itemData.getSimpleType())).color(ChatColor.WHITE);
        }
        
        // Amount if more than 1
        if (itemData.getAmount() > 1) {
            builder.append(" x" + itemData.getAmount()).color(ChatColor.GRAY);
        }
        
        // Enchantments
        if (itemData.hasEnchantments()) {
            for (Map.Entry<String, Integer> entry : itemData.getEnchantments().entrySet()) {
                builder.append("\n").reset();
                builder.append(formatEnchantmentName(entry.getKey()) + " " + 
                              toRomanNumeral(entry.getValue())).color(ChatColor.GRAY);
            }
        }
        
        // Lore
        if (itemData.hasLore()) {
            for (String line : itemData.getLore()) {
                builder.append("\n").reset();
                builder.append(line).color(ChatColor.DARK_PURPLE).italic(true);
            }
        }
        
        // Unbreakable
        if (itemData.isUnbreakable()) {
            builder.append("\n").reset();
            builder.append("Unbreakable").color(ChatColor.BLUE);
        }
        
        // Durability if damaged
        if (itemData.getDamage() > 0) {
            builder.append("\n").reset();
            builder.append("Durability: " + itemData.getDamage()).color(ChatColor.GRAY);
        }
        
        return builder.create();
    }

    /**
     * Creates a component for an empty item slot.
     * 
     * @param format the format template
     * @return component representing empty item
     */
    private BaseComponent createEmptyItemComponent(String format) {
        String displayText = format.replace("{item_name}", "Empty")
                                   .replace("{amount}", "0")
                                   .replace(" x{amount}", "");
        TextComponent component = new TextComponent(translateColors(displayText));
        component.setColor(ChatColor.GRAY);
        component.setItalic(true);
        
        // Add hover text
        BaseComponent[] tooltip = new ComponentBuilder()
            .append("Empty Hand").color(ChatColor.GRAY).italic(true)
            .create();
        component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(tooltip)));
        
        return component;
    }

    /**
     * Translates color codes in a string.
     * Supports both legacy (&X) and hex (&#RRGGBB) formats.
     * 
     * @param text the text to translate
     * @return translated text
     */
    private String translateColors(String text) {
        if (text == null) {
            return "";
        }
        
        // Translate hex colors first (&#RRGGBB)
        text = translateHexColors(text);
        
        // Translate legacy colors (&X)
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * Translates hex color codes (&#RRGGBB) to Minecraft format.
     * 
     * @param text the text to translate
     * @return translated text
     */
    private String translateHexColors(String text) {
        java.util.regex.Matcher matcher = Pattern.compile("&#([A-Fa-f0-9]{6})").matcher(text);
        StringBuffer buffer = new StringBuffer();
        
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append("§").append(Character.toLowerCase(c));
            }
            matcher.appendReplacement(buffer, replacement.toString());
        }
        matcher.appendTail(buffer);
        
        return buffer.toString();
    }

    /**
     * Creates an item display component from ItemData with NBT-based hover.
     * This method attempts to use SHOW_ITEM hover action for more accurate display.
     * Falls back to SHOW_TEXT if NBT data is not available.
     * 
     * @param itemData the item data
     * @param format the format template
     * @return the item display component
     */
    public BaseComponent renderWithNbt(ItemData itemData, String format) {
        // If we have extra NBT data, try to use SHOW_ITEM
        if (itemData != null && itemData.getExtraNbt() != null && !itemData.getExtraNbt().isEmpty()) {
            try {
                String displayText = buildDisplayText(itemData, format);
                TextComponent component = new TextComponent(translateColors(displayText));
                
                // Create SHOW_ITEM hover event with NBT
                // Note: This requires the item to be in proper NBT format
                // For now, fall back to SHOW_TEXT which is more reliable
                BaseComponent[] tooltip = buildHoverTooltip(itemData);
                component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(tooltip)));
                
                return component;
            } catch (Exception e) {
                // Fall back to standard rendering
            }
        }
        
        return (BaseComponent) renderItemDisplay(itemData, format);
    }

    /**
     * Sends an item display message to a player.
     * 
     * @param player the player to send to
     * @param message the message with item tags
     * @param itemData the item data to display
     * @param format the display format
     */
    public void sendItemDisplayMessage(org.bukkit.entity.Player player, String message, 
                                       ItemData itemData, String format) {
        BaseComponent component = (BaseComponent) processMessage(message, itemData, format);
        player.spigot().sendMessage(component);
    }
}
