package com.nova.chat.common.chat;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for ItemSerializer.
 * 
 * Tests the following property:
 * - Property 5: Item Serialization Round-Trip
 * 
 * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
 * **Validates: Requirements 12.2**
 */
class ItemSerializerPropertyTest {

    private final ItemSerializer serializer = new ItemSerializer();

    // ==================== Property 5: Item Serialization Round-Trip ====================

    /**
     * Property 5: Item Serialization Round-Trip
     * 
     * For any valid ItemData, serializing to JSON and deserializing back
     * should produce an equivalent item with all properties preserved.
     * 
     * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
     * **Validates: Requirements 12.2**
     */
    @Property(tries = 100)
    void itemSerializationRoundTrip(@ForAll("validItemData") ItemData original) {
        // Serialize to JSON
        String json = serializer.serialize(original);
        
        // Deserialize back
        ItemData deserialized = serializer.deserialize(json);
        
        // Should be equal to original
        assertThat(deserialized).isEqualTo(original);
        
        // All fields should match
        assertThat(deserialized.getType()).isEqualTo(original.getType());
        assertThat(deserialized.getDisplayName()).isEqualTo(original.getDisplayName());
        assertThat(deserialized.getAmount()).isEqualTo(original.getAmount());
        assertThat(deserialized.getDamage()).isEqualTo(original.getDamage());
        assertThat(deserialized.getLore()).isEqualTo(original.getLore());
        assertThat(deserialized.getEnchantments()).isEqualTo(original.getEnchantments());
        assertThat(deserialized.getCustomModelData()).isEqualTo(original.getCustomModelData());
        assertThat(deserialized.isUnbreakable()).isEqualTo(original.isUnbreakable());
        assertThat(deserialized.getExtraNbt()).isEqualTo(original.getExtraNbt());
    }

    /**
     * Property 5: Item Serialization Round-Trip - Double Round-Trip
     * 
     * Serializing and deserializing twice should produce the same result.
     * 
     * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
     * **Validates: Requirements 12.2**
     */
    @Property(tries = 100)
    void itemSerializationDoubleRoundTrip(@ForAll("validItemData") ItemData original) {
        // First round-trip
        String json1 = serializer.serialize(original);
        ItemData first = serializer.deserialize(json1);
        
        // Second round-trip
        String json2 = serializer.serialize(first);
        ItemData second = serializer.deserialize(json2);
        
        // Both should be equal
        assertThat(first).isEqualTo(second);
        assertThat(json1).isEqualTo(json2);
    }

    /**
     * Property 5: Item Serialization Round-Trip - Simple Items
     * 
     * Simple items with just type and amount should round-trip correctly.
     * 
     * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
     * **Validates: Requirements 12.2**
     */
    @Property(tries = 100)
    void simpleItemRoundTrip(
            @ForAll("itemTypes") String type,
            @ForAll @IntRange(min = 1, max = 64) int amount) {
        
        ItemData original = new ItemData(type, amount);
        
        String json = serializer.serialize(original);
        ItemData deserialized = serializer.deserialize(json);
        
        assertThat(deserialized.getType()).isEqualTo(type);
        assertThat(deserialized.getAmount()).isEqualTo(amount);
    }

    /**
     * Property 5: Item Serialization Round-Trip - Items with Enchantments
     * 
     * Items with enchantments should preserve all enchantment data.
     * 
     * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
     * **Validates: Requirements 12.2**
     */
    @Property(tries = 100)
    void enchantedItemRoundTrip(@ForAll("enchantedItems") ItemData original) {
        String json = serializer.serialize(original);
        ItemData deserialized = serializer.deserialize(json);
        
        assertThat(deserialized.getEnchantments())
            .containsExactlyInAnyOrderEntriesOf(original.getEnchantments());
    }

    /**
     * Property 5: Item Serialization Round-Trip - Items with Lore
     * 
     * Items with lore should preserve all lore lines in order.
     * 
     * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
     * **Validates: Requirements 12.2**
     */
    @Property(tries = 100)
    void loreItemRoundTrip(@ForAll("itemsWithLore") ItemData original) {
        String json = serializer.serialize(original);
        ItemData deserialized = serializer.deserialize(json);
        
        assertThat(deserialized.getLore())
            .containsExactlyElementsOf(original.getLore());
    }

    /**
     * Property 5: Item Serialization Round-Trip - Deep Copy
     * 
     * Deep copy should produce an independent copy with equal values.
     * 
     * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
     * **Validates: Requirements 12.2**
     */
    @Property(tries = 100)
    void deepCopyProducesEqualItem(@ForAll("validItemData") ItemData original) {
        ItemData copy = serializer.deepCopy(original);
        
        assertThat(copy).isEqualTo(original);
        assertThat(copy).isNotSameAs(original);
        
        // Modifying copy should not affect original
        if (copy.getLore() != null && !copy.getLore().isEmpty()) {
            copy.getLore().clear();
            assertThat(original.getLore()).isNotEmpty();
        }
    }

    /**
     * Property 5: Item Serialization Round-Trip - Equivalence Check
     * 
     * areEquivalent should return true for items that serialize to the same JSON.
     * 
     * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
     * **Validates: Requirements 12.2**
     */
    @Property(tries = 100)
    void equivalenceCheck(@ForAll("validItemData") ItemData original) {
        ItemData copy = serializer.deepCopy(original);
        
        assertThat(serializer.areEquivalent(original, copy)).isTrue();
        assertThat(serializer.areEquivalent(original, original)).isTrue();
    }

    /**
     * Property 5: Item Serialization Round-Trip - JSON Validity
     * 
     * Serialized JSON should be valid and parseable.
     * 
     * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
     * **Validates: Requirements 12.2**
     */
    @Property(tries = 100)
    void serializedJsonIsValid(@ForAll("validItemData") ItemData original) {
        String json = serializer.serialize(original);
        
        assertThat(json).isNotNull().isNotEmpty();
        assertThat(serializer.isValidJson(json)).isTrue();
    }

    /**
     * Property 5: Item Serialization Round-Trip - Invalid JSON Handling
     * 
     * Invalid JSON should be rejected gracefully.
     * 
     * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
     * **Validates: Requirements 12.2**
     */
    @Property(tries = 100)
    void invalidJsonHandling(@ForAll("invalidJson") String invalidJson) {
        assertThat(serializer.isValidJson(invalidJson)).isFalse();
        assertThat(serializer.deserializeSafe(invalidJson)).isNull();
    }

    /**
     * Property 5: Item Serialization Round-Trip - Null Safety
     * 
     * Serializer should handle null inputs appropriately.
     * 
     * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
     * **Validates: Requirements 12.2**
     */
    @Property(tries = 10)
    void nullSafety() {
        // serialize should throw on null
        assertThatThrownBy(() -> serializer.serialize(null))
            .isInstanceOf(NullPointerException.class);
        
        // deserialize should throw on null/empty
        assertThatThrownBy(() -> serializer.deserialize(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> serializer.deserialize(""))
            .isInstanceOf(IllegalArgumentException.class);
        
        // deserializeSafe should return null
        assertThat(serializer.deserializeSafe(null)).isNull();
        assertThat(serializer.deserializeSafe("")).isNull();
        
        // deepCopy should handle null
        assertThat(serializer.deepCopy(null)).isNull();
    }

    /**
     * Property 5: Item Serialization Round-Trip - Empty Item
     * 
     * Empty/air items should round-trip correctly.
     * 
     * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
     * **Validates: Requirements 12.2**
     */
    @Property(tries = 10)
    void emptyItemRoundTrip() {
        ItemData empty = ItemSerializer.createEmpty();
        
        String json = serializer.serialize(empty);
        ItemData deserialized = serializer.deserialize(json);
        
        assertThat(deserialized.isEmpty()).isTrue();
        assertThat(deserialized.getType()).isEqualTo("minecraft:air");
    }

    /**
     * Property 5: Item Serialization Round-Trip - Special Characters
     * 
     * Items with special characters in display name and lore should round-trip correctly.
     * 
     * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
     * **Validates: Requirements 12.2**
     */
    @Property(tries = 100)
    void specialCharactersRoundTrip(@ForAll("itemsWithSpecialChars") ItemData original) {
        String json = serializer.serialize(original);
        ItemData deserialized = serializer.deserialize(json);
        
        assertThat(deserialized.getDisplayName()).isEqualTo(original.getDisplayName());
        assertThat(deserialized.getLore()).isEqualTo(original.getLore());
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<ItemData> validItemData() {
        return Combinators.combine(
            itemTypes(),
            displayNames(),
            Arbitraries.integers().between(1, 64),
            Arbitraries.integers().between(0, 100),
            loreLists(),
            enchantmentMaps(),
            Arbitraries.integers().between(1, 1000000).injectNull(0.7),
            Arbitraries.of(true, false)
        ).as((type, displayName, amount, damage, lore, enchants, customModel, unbreakable) -> {
            ItemData item = new ItemData();
            item.setType(type);
            item.setDisplayName(displayName);
            item.setAmount(amount);
            item.setDamage(damage);
            item.setLore(lore);
            item.setEnchantments(enchants);
            item.setCustomModelData(customModel);
            item.setUnbreakable(unbreakable);
            return item;
        });
    }

    @Provide
    Arbitrary<String> itemTypes() {
        return Arbitraries.of(
            "minecraft:diamond_sword",
            "minecraft:iron_pickaxe",
            "minecraft:golden_apple",
            "minecraft:netherite_chestplate",
            "minecraft:enchanted_book",
            "minecraft:bow",
            "minecraft:crossbow",
            "minecraft:trident",
            "minecraft:elytra",
            "minecraft:totem_of_undying",
            "minecraft:diamond",
            "minecraft:emerald",
            "minecraft:stone",
            "minecraft:oak_planks"
        );
    }

    @Provide
    Arbitrary<String> displayNames() {
        return Arbitraries.oneOf(
            Arbitraries.just((String) null),
            Arbitraries.just(""),
            Arbitraries.just("Diamond Sword"),
            Arbitraries.just("§6Legendary Blade"),
            Arbitraries.just("§c§lEpic Weapon"),
            Arbitraries.just("&aGreen Name"),
            Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars(' ', '_', '-')
                .ofMinLength(1)
                .ofMaxLength(32)
        );
    }

    @Provide
    Arbitrary<List<String>> loreLists() {
        Arbitrary<String> loreLine = Arbitraries.oneOf(
            Arbitraries.just("§7A legendary weapon"),
            Arbitraries.just("§eCrafted by the ancients"),
            Arbitraries.just("§c+10 Attack Damage"),
            Arbitraries.just("§9+5 Speed"),
            Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withChars(' ', '§', '&')
                .ofMinLength(1)
                .ofMaxLength(50)
        );
        
        return Arbitraries.oneOf(
            Arbitraries.just(new ArrayList<String>()),
            loreLine.list().ofMinSize(1).ofMaxSize(5)
        );
    }

    @Provide
    Arbitrary<Map<String, Integer>> enchantmentMaps() {
        Arbitrary<String> enchantName = Arbitraries.of(
            "minecraft:sharpness",
            "minecraft:smite",
            "minecraft:bane_of_arthropods",
            "minecraft:knockback",
            "minecraft:fire_aspect",
            "minecraft:looting",
            "minecraft:sweeping",
            "minecraft:efficiency",
            "minecraft:silk_touch",
            "minecraft:fortune",
            "minecraft:protection",
            "minecraft:unbreaking",
            "minecraft:mending"
        );
        
        Arbitrary<Integer> level = Arbitraries.integers().between(1, 10);
        
        return Arbitraries.oneOf(
            Arbitraries.just(new HashMap<String, Integer>()),
            Arbitraries.maps(enchantName, level).ofMinSize(1).ofMaxSize(5)
        );
    }

    @Provide
    Arbitrary<ItemData> enchantedItems() {
        return Combinators.combine(
            itemTypes(),
            Arbitraries.maps(
                Arbitraries.of("minecraft:sharpness", "minecraft:unbreaking", "minecraft:mending"),
                Arbitraries.integers().between(1, 5)
            ).ofMinSize(1).ofMaxSize(3)
        ).as((type, enchants) -> {
            ItemData item = new ItemData(type, 1);
            item.setEnchantments(enchants);
            return item;
        });
    }

    @Provide
    Arbitrary<ItemData> itemsWithLore() {
        return Combinators.combine(
            itemTypes(),
            Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ')
                .ofMinLength(5)
                .ofMaxLength(30)
                .list()
                .ofMinSize(1)
                .ofMaxSize(5)
        ).as((type, lore) -> {
            ItemData item = new ItemData(type, 1);
            item.setLore(lore);
            return item;
        });
    }

    @Provide
    Arbitrary<ItemData> itemsWithSpecialChars() {
        Arbitrary<String> specialName = Arbitraries.of(
            "§6§lLegendary §r§7Sword",
            "&c&lRed &r&aGreen",
            "Name with \"quotes\"",
            "Name with 'apostrophes'",
            "Unicode: 你好世界",
            "Emoji: ⚔️🛡️",
            "Special: <>&",
            "Newline\\nTest"
        );
        
        return Combinators.combine(
            itemTypes(),
            specialName
        ).as((type, name) -> {
            ItemData item = new ItemData(type, 1);
            item.setDisplayName(name);
            List<String> lore = new ArrayList<>();
            lore.add("§7" + name);
            item.setLore(lore);
            return item;
        });
    }

    @Provide
    Arbitrary<String> invalidJson() {
        return Arbitraries.of(
            null,
            "",
            "   ",
            "not json",
            "{invalid}",
            "{\"type\":}",
            "[1,2,3]",
            "12345",
            "true",
            "null",
            "{\"type\": \"test\", \"amount\": \"not a number\"}",
            "{{nested}}"
        );
    }
}
