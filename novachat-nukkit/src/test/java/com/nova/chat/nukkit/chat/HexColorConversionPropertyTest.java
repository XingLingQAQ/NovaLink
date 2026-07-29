package com.nova.chat.nukkit.chat;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.assertj.core.api.Assertions;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Property-based tests for hex color conversion logic used in MessageFormatter.
 * Tests the algorithm that converts hex colors to nearest Bedrock color codes.
 */
class HexColorConversionPropertyTest {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    // Bedrock color palette (approximate RGB values)
    private static final int[][] BEDROCK_COLORS = {
        {0, 0, 0},       // 0 - Black
        {0, 0, 170},     // 1 - Dark Blue
        {0, 170, 0},     // 2 - Dark Green
        {0, 170, 170},   // 3 - Dark Aqua
        {170, 0, 0},     // 4 - Dark Red
        {170, 0, 170},   // 5 - Dark Purple
        {255, 170, 0},   // 6 - Gold
        {170, 170, 170}, // 7 - Gray
        {85, 85, 85},    // 8 - Dark Gray
        {85, 85, 255},   // 9 - Blue
        {85, 255, 85},   // a - Green
        {85, 255, 255},  // b - Aqua
        {255, 85, 85},   // c - Red
        {255, 85, 255},  // d - Light Purple
        {255, 255, 85},  // e - Yellow
        {255, 255, 255}  // f - White
    };

    private static final String[] COLOR_CODES = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"};

    /**
     * Converts hex to nearest Bedrock color (same algorithm as MessageFormatter).
     */
    private String hexToNearestBedrockColor(String hex) {
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);

        int minDistance = Integer.MAX_VALUE;
        String nearestCode = "f";

        for (int i = 0; i < BEDROCK_COLORS.length; i++) {
            int dr = r - BEDROCK_COLORS[i][0];
            int dg = g - BEDROCK_COLORS[i][1];
            int db = b - BEDROCK_COLORS[i][2];
            int distance = dr * dr + dg * dg + db * db;

            if (distance < minDistance) {
                minDistance = distance;
                nearestCode = COLOR_CODES[i];
            }
        }

        return "§" + nearestCode;
    }

    @Property
    void blackHexShouldMapToBlack() {
        String result = hexToNearestBedrockColor("000000");
        Assertions.assertThat(result).isEqualTo("§0");
    }

    @Property
    void whiteHexShouldMapToWhite() {
        String result = hexToNearestBedrockColor("FFFFFF");
        Assertions.assertThat(result).isEqualTo("§f");
    }

    @Property
    void pureRedShouldMapToRed() {
        String result = hexToNearestBedrockColor("FF0000");
        // Should map to either §c (red) or §4 (dark red)
        Assertions.assertThat(result).isIn("§c", "§4");
    }

    @Property
    void pureGreenShouldMapToGreen() {
        String result = hexToNearestBedrockColor("00FF00");
        // Should map to either §a (green) or §2 (dark green)
        Assertions.assertThat(result).isIn("§a", "§2");
    }

    @Property
    void pureBlueShouldMapToBlue() {
        String result = hexToNearestBedrockColor("0000FF");
        // Should map to either §9 (blue) or §1 (dark blue)
        Assertions.assertThat(result).isIn("§9", "§1");
    }

    @Property
    void conversionShouldAlwaysReturnValidColorCode(
            @ForAll @IntRange(min = 0, max = 255) int r,
            @ForAll @IntRange(min = 0, max = 255) int g,
            @ForAll @IntRange(min = 0, max = 255) int b) {
        String hex = String.format("%02X%02X%02X", r, g, b);
        String result = hexToNearestBedrockColor(hex);
        
        Assertions.assertThat(result).startsWith("§");
        Assertions.assertThat(result).hasSize(2);
        Assertions.assertThat(COLOR_CODES).contains(result.substring(1));
    }

    @Property
    void conversionShouldBeDeterministic(
            @ForAll @IntRange(min = 0, max = 255) int r,
            @ForAll @IntRange(min = 0, max = 255) int g,
            @ForAll @IntRange(min = 0, max = 255) int b) {
        String hex = String.format("%02X%02X%02X", r, g, b);
        String result1 = hexToNearestBedrockColor(hex);
        String result2 = hexToNearestBedrockColor(hex);
        
        Assertions.assertThat(result1).isEqualTo(result2);
    }

    @Property
    void hexPatternShouldMatchValidHexColors() {
        String text = "Hello &#FF5500 World &#00AAFF!";
        Matcher matcher = HEX_PATTERN.matcher(text);
        
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        
        Assertions.assertThat(count).isEqualTo(2);
    }

    @Property
    void hexPatternShouldNotMatchInvalidFormats() {
        String text = "Hello #FF5500 World &FF5500!";
        Matcher matcher = HEX_PATTERN.matcher(text);
        
        Assertions.assertThat(matcher.find()).isFalse();
    }

    @Property
    void grayScaleShouldMapToGrayColors(
            @ForAll @IntRange(min = 0, max = 255) int gray) {
        String hex = String.format("%02X%02X%02X", gray, gray, gray);
        String result = hexToNearestBedrockColor(hex);
        
        // Gray scale should map to one of: §0 (black), §8 (dark gray), §7 (gray), §f (white)
        Assertions.assertThat(result).isIn("§0", "§7", "§8", "§f");
    }
}
