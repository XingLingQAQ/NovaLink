<?php

declare(strict_types=1);

namespace NovaChat\Chat;

use pocketmine\player\Player;
use pocketmine\item\Item;
use pocketmine\item\enchantment\EnchantmentInstance;
use pocketmine\utils\TextFormat;

/**
 * Bedrock Edition item display renderer for PMMP.
 * 
 * Since Bedrock Edition doesn't support HoverEvent like Java Edition,
 * this renderer provides alternative approaches:
 * - Formatted text with item details inline
 * - Form-based item preview on click (future)
 * - Action bar/popup for quick item info
 * 
 * Requirements: 12.4 - Item display SHALL support Bedrock Edition alternatives.
 */
class ItemDisplayRenderer {

    /** Permission node for item display */
    public const PERMISSION_ITEM = "novachat.feature.item";
    
    /** Permission node for inventory display */
    public const PERMISSION_INVENTORY = "novachat.feature.inventory";
    
    /** Permission node for enderchest display */
    public const PERMISSION_ENDERCHEST = "novachat.feature.enderchest";

    /** Pattern for item tags */
    private const ITEM_PATTERN = '/\[(item|i)\]/i';

    /**
     * Renders an item display for Bedrock Edition.
     * Returns formatted text with item information.
     * 
     * @param Item|null $item The item to display
     * @param string $format The display format template
     * @return string The rendered display text
     */
    public static function renderItemDisplay(?Item $item, string $format): string {
        if ($item === null || $item->isNull()) {
            return self::renderEmptyItem($format);
        }

        $itemName = $item->hasCustomName() 
            ? $item->getCustomName() 
            : $item->getName();
        
        $result = str_replace("{item_name}", $itemName, $format);
        
        if ($item->getCount() > 1) {
            $result = str_replace("{amount}", (string)$item->getCount(), $result);
        } else {
            $result = str_replace(" x{amount}", "", $result);
            $result = str_replace("{amount}", "1", $result);
        }
        
        return MessageRenderer::render($result);
    }

    /**
     * Renders an empty item placeholder.
     * 
     * @param string $format The display format template
     * @return string The rendered empty item text
     */
    private static function renderEmptyItem(string $format): string {
        $result = str_replace("{item_name}", "§7§oEmpty", $format);
        $result = str_replace("{amount}", "0", $result);
        $result = str_replace(" x{amount}", "", $result);
        return MessageRenderer::render($result);
    }

    /**
     * Processes a message and replaces item tags with rendered item displays.
     * 
     * @param string $message The original message with [item] or [i] tags
     * @param Item|null $item The item to display
     * @param string $format The display format template
     * @return string The processed message
     */
    public static function processMessage(string $message, ?Item $item, string $format): string {
        if (!self::hasItemTag($message)) {
            return MessageRenderer::render($message);
        }

        $itemDisplay = self::renderItemDisplay($item, $format);
        return preg_replace(self::ITEM_PATTERN, $itemDisplay, $message);
    }

    /**
     * Checks if a message contains item display tags.
     * 
     * @param string $message The message to check
     * @return bool True if item tags are present
     */
    public static function hasItemTag(string $message): bool {
        return preg_match(self::ITEM_PATTERN, $message) === 1;
    }

    /**
     * Builds detailed item information text for Bedrock.
     * This is used for popup/form displays since hover isn't available.
     * 
     * @param Item $item The item to describe
     * @return string Detailed item information
     */
    public static function buildItemDetails(Item $item): string {
        $lines = [];
        
        // Item name with color
        if ($item->hasCustomName()) {
            $lines[] = TextFormat::AQUA . $item->getCustomName();
        } else {
            $lines[] = TextFormat::WHITE . $item->getName();
        }
        
        // Amount
        if ($item->getCount() > 1) {
            $lines[] = TextFormat::GRAY . "Amount: " . $item->getCount();
        }
        
        // Enchantments
        if ($item->hasEnchantments()) {
            foreach ($item->getEnchantments() as $enchantment) {
                $enchantName = $enchantment->getType()->getName();
                $level = self::toRomanNumeral($enchantment->getLevel());
                $lines[] = TextFormat::GRAY . $enchantName . " " . $level;
            }
        }
        
        // Lore
        $lore = $item->getLore();
        if (!empty($lore)) {
            foreach ($lore as $line) {
                $lines[] = TextFormat::DARK_PURPLE . TextFormat::ITALIC . $line;
            }
        }
        
        return implode("\n", $lines);
    }

    /**
     * Sends item details as a popup to a player.
     * This is the Bedrock alternative to hover events.
     * 
     * @param Player $player The player to send to
     * @param Item $item The item to display
     */
    public static function sendItemPopup(Player $player, Item $item): void {
        $details = self::buildItemDetails($item);
        $player->sendPopup($details);
    }

    /**
     * Sends item details as a tip to a player.
     * 
     * @param Player $player The player to send to
     * @param Item $item The item to display
     */
    public static function sendItemTip(Player $player, Item $item): void {
        $details = self::buildItemDetails($item);
        $player->sendTip($details);
    }

    /**
     * Sends item details as an action bar message.
     * 
     * @param Player $player The player to send to
     * @param Item $item The item to display
     */
    public static function sendItemActionBar(Player $player, Item $item): void {
        $name = $item->hasCustomName() ? $item->getCustomName() : $item->getName();
        $count = $item->getCount() > 1 ? " x" . $item->getCount() : "";
        $player->sendActionBarMessage(TextFormat::AQUA . $name . $count);
    }

    /**
     * Converts a number to Roman numeral.
     * 
     * @param int $number The number to convert
     * @return string Roman numeral string
     */
    private static function toRomanNumeral(int $number): string {
        if ($number <= 0 || $number > 10) {
            return (string)$number;
        }
        
        $numerals = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"];
        return $numerals[$number - 1];
    }

    /**
     * Checks if a player has permission to use item display.
     * 
     * @param Player $player The player to check
     * @return bool True if player has permission
     */
    public static function hasItemPermission(Player $player): bool {
        return $player->hasPermission(self::PERMISSION_ITEM);
    }

    /**
     * Checks if a player has permission to use inventory display.
     * 
     * @param Player $player The player to check
     * @return bool True if player has permission
     */
    public static function hasInventoryPermission(Player $player): bool {
        return $player->hasPermission(self::PERMISSION_INVENTORY);
    }

    /**
     * Checks if a player has permission to use enderchest display.
     * 
     * @param Player $player The player to check
     * @return bool True if player has permission
     */
    public static function hasEnderchestPermission(Player $player): bool {
        return $player->hasPermission(self::PERMISSION_ENDERCHEST);
    }

    /**
     * Processes a message with permission checking.
     * If player doesn't have permission, tags are treated as plain text.
     * 
     * @param Player $player The player sending the message
     * @param string $message The message to process
     * @param string $format The display format template
     * @return string The processed message
     */
    public static function processWithPermission(Player $player, string $message, string $format): string {
        if (!self::hasItemPermission($player)) {
            // No permission - treat tags as plain text
            return MessageRenderer::render($message);
        }

        if (!self::hasItemTag($message)) {
            return MessageRenderer::render($message);
        }

        $item = $player->getInventory()->getItemInHand();
        return self::processMessage($message, $item, $format);
    }
}
