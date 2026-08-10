package com.nova.chat.common.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Platform-agnostic representation of a Minecraft item.
 * This class holds all the data needed to display an item in chat
 * and can be serialized to/from JSON for cross-server transmission.
 * 
 * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
 * **Validates: Requirements 12.2**
 */
public class ItemData {

    /** The item type/material (e.g., "minecraft:diamond_sword") */
    private String type;
    
    /** The display name (may include color codes) */
    private String displayName;
    
    /** The item amount/count */
    private int amount;
    
    /** The item durability/damage value */
    private int damage;
    
    /** The lore lines */
    private List<String> lore;
    
    /** Enchantments as map of enchantment ID to level */
    private Map<String, Integer> enchantments;
    
    /** Custom model data (1.14+) */
    private Integer customModelData;
    
    /** Whether the item is unbreakable */
    private boolean unbreakable;
    
    /** Additional NBT data as JSON string (for complex items) */
    private String extraNbt;

    /**
     * Default constructor for deserialization.
     */
    public ItemData() {
        this.lore = new ArrayList<>();
        this.enchantments = new HashMap<>();
        this.amount = 1;
    }

    /**
     * Constructor with basic item information.
     */
    public ItemData(String type, int amount) {
        this();
        this.type = type;
        this.amount = amount;
    }

    /**
     * Full constructor for creating item data.
     */
    public ItemData(String type, String displayName, int amount, int damage,
                    List<String> lore, Map<String, Integer> enchantments,
                    Integer customModelData, boolean unbreakable, String extraNbt) {
        this.type = type;
        this.displayName = displayName;
        this.amount = amount;
        this.damage = damage;
        this.lore = lore != null ? new ArrayList<>(lore) : new ArrayList<>();
        this.enchantments = enchantments != null ? new HashMap<>(enchantments) : new HashMap<>();
        this.customModelData = customModelData;
        this.unbreakable = unbreakable;
        this.extraNbt = extraNbt;
    }

    // ==================== Getters and Setters ====================

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public int getDamage() {
        return damage;
    }

    public void setDamage(int damage) {
        this.damage = damage;
    }

    public List<String> getLore() {
        return lore;
    }

    public void setLore(List<String> lore) {
        this.lore = lore != null ? new ArrayList<>(lore) : new ArrayList<>();
    }

    /**
     * Appends a lore line, lazily initializing the lore list if it has been set to null.
     *
     * @param line the lore line to add (may include color codes)
     */
    public void addLore(String line) {
        if (this.lore == null) {
            this.lore = new ArrayList<>();
        }
        this.lore.add(line);
    }

    public Map<String, Integer> getEnchantments() {
        return enchantments;
    }

    public void setEnchantments(Map<String, Integer> enchantments) {
        this.enchantments = enchantments != null ? new HashMap<>(enchantments) : new HashMap<>();
    }

    /**
     * Adds an enchantment at the given level, lazily initializing the enchantment map if it has been set to null.
     *
     * @param enchantment the enchantment ID
     * @param level the enchantment level
     */
    public void addEnchantment(String enchantment, int level) {
        if (this.enchantments == null) {
            this.enchantments = new HashMap<>();
        }
        this.enchantments.put(enchantment, level);
    }

    public Integer getCustomModelData() {
        return customModelData;
    }

    public void setCustomModelData(Integer customModelData) {
        this.customModelData = customModelData;
    }

    public boolean isUnbreakable() {
        return unbreakable;
    }

    public void setUnbreakable(boolean unbreakable) {
        this.unbreakable = unbreakable;
    }

    public String getExtraNbt() {
        return extraNbt;
    }

    public void setExtraNbt(String extraNbt) {
        this.extraNbt = extraNbt;
    }

    // ==================== Utility Methods ====================

    /**
     * Checks if this item has a custom display name.
     */
    public boolean hasDisplayName() {
        return displayName != null && !displayName.isEmpty();
    }

    /**
     * Checks if this item has lore.
     */
    public boolean hasLore() {
        return lore != null && !lore.isEmpty();
    }

    /**
     * Checks if this item has enchantments.
     */
    public boolean hasEnchantments() {
        return enchantments != null && !enchantments.isEmpty();
    }

    /**
     * Checks if this item is empty/air.
     */
    public boolean isEmpty() {
        return type == null || type.isEmpty() || 
               type.equals("minecraft:air") || type.equals("AIR") ||
               amount <= 0;
    }

    /**
     * Gets the simple type name without namespace.
     * e.g., "minecraft:diamond_sword" -> "diamond_sword"
     */
    public String getSimpleType() {
        if (type == null) {
            return null;
        }
        int colonIndex = type.indexOf(':');
        return colonIndex >= 0 ? type.substring(colonIndex + 1) : type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ItemData itemData = (ItemData) o;
        return amount == itemData.amount &&
                damage == itemData.damage &&
                unbreakable == itemData.unbreakable &&
                Objects.equals(type, itemData.type) &&
                Objects.equals(displayName, itemData.displayName) &&
                Objects.equals(lore, itemData.lore) &&
                Objects.equals(enchantments, itemData.enchantments) &&
                Objects.equals(customModelData, itemData.customModelData) &&
                Objects.equals(extraNbt, itemData.extraNbt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, displayName, amount, damage, lore, 
                           enchantments, customModelData, unbreakable, extraNbt);
    }

    @Override
    public String toString() {
        return "ItemData{" +
                "type='" + type + '\'' +
                ", displayName='" + displayName + '\'' +
                ", amount=" + amount +
                ", damage=" + damage +
                ", lore=" + lore +
                ", enchantments=" + enchantments +
                ", customModelData=" + customModelData +
                ", unbreakable=" + unbreakable +
                '}';
    }
}
