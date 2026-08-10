<?php

declare(strict_types=1);

namespace NovaChat\Chat;

use pocketmine\player\Player;
use pocketmine\utils\TextFormat;

/**
 * Message renderer for formatting and displaying messages.
 * 
 * Requirements:
 * - 8.7: WHEN 收到后端消息 THEN NovaChat-PMMP SHALL 使用 TextFormat 类渲染颜色代码
 * 
 * This class handles:
 * - Color code conversion (& to §)
 * - Hex color approximation for Bedrock
 * - Message formatting with placeholders
 * - Title and action bar rendering
 */
class MessageRenderer {
    
    /**
     * Standard Minecraft color codes.
     */
    private const COLOR_CODES = [
        '0' => TextFormat::BLACK,
        '1' => TextFormat::DARK_BLUE,
        '2' => TextFormat::DARK_GREEN,
        '3' => TextFormat::DARK_AQUA,
        '4' => TextFormat::DARK_RED,
        '5' => TextFormat::DARK_PURPLE,
        '6' => TextFormat::GOLD,
        '7' => TextFormat::GRAY,
        '8' => TextFormat::DARK_GRAY,
        '9' => TextFormat::BLUE,
        'a' => TextFormat::GREEN,
        'b' => TextFormat::AQUA,
        'c' => TextFormat::RED,
        'd' => TextFormat::LIGHT_PURPLE,
        'e' => TextFormat::YELLOW,
        'f' => TextFormat::WHITE,
        'k' => TextFormat::OBFUSCATED,
        'l' => TextFormat::BOLD,
        'm' => TextFormat::STRIKETHROUGH,
        'n' => TextFormat::UNDERLINE,
        'o' => TextFormat::ITALIC,
        'r' => TextFormat::RESET,
    ];
    
    /**
     * Renders a message with color codes.
     * 
     * Converts both & and § color codes to proper formatting.
     * 
     * @param string $message The message to render
     * @return string The rendered message
     */
    public static function render(string $message): string {
        // Use TextFormat::colorize which handles & to § conversion
        return TextFormat::colorize($message);
    }
    
    /**
     * Renders a message and strips all color codes.
     * 
     * @param string $message The message to clean
     * @return string The message without color codes
     */
    public static function stripColors(string $message): string {
        return TextFormat::clean($message);
    }
    
    /**
     * Formats a chat message with placeholders.
     * 
     * @param string $format The format string
     * @param array<string, string> $placeholders Key-value pairs for replacement
     * @return string The formatted message
     */
    public static function format(string $format, array $placeholders): string {
        $message = $format;
        
        foreach ($placeholders as $key => $value) {
            $message = str_replace("{" . $key . "}", $value, $message);
        }
        
        return self::render($message);
    }
    
    /**
     * Sends a formatted message to a player.
     * 
     * @param Player $player The player to send to
     * @param string $message The message to send
     */
    public static function sendMessage(Player $player, string $message): void {
        $player->sendMessage(self::render($message));
    }
    
    /**
     * Sends a title to a player.
     * 
     * @param Player $player The player to send to
     * @param string $title The title text
     * @param string $subtitle The subtitle text
     * @param int $fadeIn Fade in time in ticks
     * @param int $stay Stay time in ticks
     * @param int $fadeOut Fade out time in ticks
     */
    public static function sendTitle(
        Player $player,
        string $title,
        string $subtitle = "",
        int $fadeIn = 10,
        int $stay = 70,
        int $fadeOut = 20
    ): void {
        $player->sendTitle(
            self::render($title),
            self::render($subtitle),
            $fadeIn,
            $stay,
            $fadeOut
        );
    }
    
    /**
     * Sends an action bar message to a player.
     * 
     * @param Player $player The player to send to
     * @param string $message The message to display
     */
    public static function sendActionBar(Player $player, string $message): void {
        $player->sendActionBarMessage(self::render($message));
    }
    
    /**
     * Sends a popup message to a player.
     * 
     * @param Player $player The player to send to
     * @param string $message The message to display
     */
    public static function sendPopup(Player $player, string $message): void {
        $player->sendPopup(self::render($message));
    }
    
    /**
     * Sends a tip message to a player.
     * 
     * @param Player $player The player to send to
     * @param string $message The message to display
     */
    public static function sendTip(Player $player, string $message): void {
        $player->sendTip(self::render($message));
    }
    
    /**
     * Broadcasts a message to all online players.
     * 
     * @param \pocketmine\Server $server The server instance
     * @param string $message The message to broadcast
     */
    public static function broadcast(\pocketmine\Server $server, string $message): void {
        $rendered = self::render($message);
        foreach ($server->getOnlinePlayers() as $player) {
            $player->sendMessage($rendered);
        }
    }
    
    /**
     * Broadcasts a title to all online players.
     * 
     * @param \pocketmine\Server $server The server instance
     * @param string $title The title text
     * @param string $subtitle The subtitle text
     * @param int $fadeIn Fade in time in ticks
     * @param int $stay Stay time in ticks
     * @param int $fadeOut Fade out time in ticks
     */
    public static function broadcastTitle(
        \pocketmine\Server $server,
        string $title,
        string $subtitle = "",
        int $fadeIn = 10,
        int $stay = 70,
        int $fadeOut = 20
    ): void {
        $renderedTitle = self::render($title);
        $renderedSubtitle = self::render($subtitle);
        
        foreach ($server->getOnlinePlayers() as $player) {
            $player->sendTitle($renderedTitle, $renderedSubtitle, $fadeIn, $stay, $fadeOut);
        }
    }
    
    /**
     * Broadcasts an action bar message to all online players.
     * 
     * @param \pocketmine\Server $server The server instance
     * @param string $message The message to display
     */
    public static function broadcastActionBar(\pocketmine\Server $server, string $message): void {
        $rendered = self::render($message);
        foreach ($server->getOnlinePlayers() as $player) {
            $player->sendActionBarMessage($rendered);
        }
    }
    
    /**
     * Converts hex color codes to the nearest Bedrock color.
     * 
     * Bedrock Edition doesn't support hex colors, so we approximate
     * to the nearest standard color code.
     * 
     * @param string $message The message with potential hex colors
     * @return string The message with hex colors converted
     */
    public static function convertHexColors(string $message): string {
        // Match hex color patterns like &#RRGGBB or &x&R&R&G&G&B&B
        $message = preg_replace_callback(
            '/&#([0-9A-Fa-f]{6})/',
            function ($matches) {
                return self::hexToNearestColor($matches[1]);
            },
            $message
        );
        
        // Also handle the &x&R&R&G&G&B&B format
        $message = preg_replace_callback(
            '/&x(&[0-9A-Fa-f]){6}/',
            function ($matches) {
                $hex = str_replace(['&x', '&'], '', $matches[0]);
                return self::hexToNearestColor($hex);
            },
            $message
        );
        
        return $message;
    }
    
    /**
     * Converts a hex color to the nearest Minecraft color code.
     * 
     * @param string $hex The hex color (without #)
     * @return string The nearest color code
     */
    private static function hexToNearestColor(string $hex): string {
        $r = hexdec(substr($hex, 0, 2));
        $g = hexdec(substr($hex, 2, 2));
        $b = hexdec(substr($hex, 4, 2));
        
        // Standard Minecraft colors with their RGB values
        $colors = [
            '0' => [0, 0, 0],       // Black
            '1' => [0, 0, 170],     // Dark Blue
            '2' => [0, 170, 0],     // Dark Green
            '3' => [0, 170, 170],   // Dark Aqua
            '4' => [170, 0, 0],     // Dark Red
            '5' => [170, 0, 170],   // Dark Purple
            '6' => [255, 170, 0],   // Gold
            '7' => [170, 170, 170], // Gray
            '8' => [85, 85, 85],    // Dark Gray
            '9' => [85, 85, 255],   // Blue
            'a' => [85, 255, 85],   // Green
            'b' => [85, 255, 255],  // Aqua
            'c' => [255, 85, 85],   // Red
            'd' => [255, 85, 255],  // Light Purple
            'e' => [255, 255, 85],  // Yellow
            'f' => [255, 255, 255], // White
        ];
        
        $nearestCode = 'f';
        $nearestDistance = PHP_INT_MAX;
        
        foreach ($colors as $code => $rgb) {
            $distance = pow($r - $rgb[0], 2) + pow($g - $rgb[1], 2) + pow($b - $rgb[2], 2);
            if ($distance < $nearestDistance) {
                $nearestDistance = $distance;
                $nearestCode = $code;
            }
        }
        
        return "§" . $nearestCode;
    }
}
