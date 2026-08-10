<?php

declare(strict_types=1);

namespace NovaChat\I18n;

/**
 * I18n message lookup for NovaChat-PMMP.
 *
 * Mirrors the Java client-core message bundles
 * (messages_zh_CN.properties / messages_en_US.properties). Keys and color
 * codes (§c, &e) stay inside the values; only natural language swaps
 * between locales.
 *
 * Fallback chain: requested locale -> zh_CN (hard default) -> key itself,
 * matching the Java Utf8Control fallback behaviour.
 */
class I18n {

    public const DEFAULT_LOCALE = "zh_CN";

    /** @var array<string, array<string, string>> */
    private array $bundles;

    public function __construct() {
        $this->bundles = [
            "zh_CN" => MessagesZhCN::MESSAGES,
            "en_US" => MessagesEnUS::MESSAGES,
        ];
    }

    /**
     * Look up a localized message and substitute {0}, {1}, ... placeholders.
     *
     * @param string $key the message key
     * @param string $locale the locale code (zh_CN / en_US)
     * @param array<int, string> $args positional placeholder values
     * @return string the formatted message, or the key itself if missing
     */
    public function get(string $key, string $locale, array $args = []): string {
        $template = $this->lookup($key, $locale);
        return $this->format($template, $args);
    }

    /**
     * Build a human-readable error message from an NC-* error code.
     */
    public function errorMessage(string $errorCode, string $locale): string {
        $message = $this->get("error.{$errorCode}.message", $locale, [$errorCode]);
        $suggestion = $this->get("error.{$errorCode}.suggestion", $locale);
        $prefix = $this->get("error.suggestion_prefix", $locale);
        return "§c{$message} §7{$prefix} {$suggestion}";
    }

    private function lookup(string $key, string $locale): string {
        $bundle = $this->bundles[$locale] ?? null;
        if ($bundle !== null && isset($bundle[$key])) {
            return $bundle[$key];
        }
        // Fallback to default locale.
        $default = $this->bundles[self::DEFAULT_LOCALE] ?? null;
        if ($default !== null && isset($default[$key])) {
            return $default[$key];
        }
        return $key;
    }

    /**
     * @param array<int, string> $args
     */
    private function format(string $template, array $args): string {
        $result = $template;
        foreach ($args as $i => $value) {
            $result = str_replace("{" . $i . "}", (string)$value, $result);
        }
        return $result;
    }
}
