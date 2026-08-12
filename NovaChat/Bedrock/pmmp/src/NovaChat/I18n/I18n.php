<?php

declare(strict_types=1);

namespace NovaChat\I18n;

use Phar;

/**
 * I18n message lookup for NovaChat-PMMP.
 *
 * Translations live as external ``resources/lang/<locale>.json`` files (one
 * file per locale, keyed by filename stem). At construction the provider
 * scans the lang directory and loads every ``*.json`` file it finds, so
 * adding a new language is just dropping a new ``lang/<locale>.json`` into
 * the directory — no code change required.
 *
 * Keys and color codes (§c, &e) stay inside the values; only natural language
 * swaps between locales. Keys mirror the Java client-core message bundles
 * (messages_zh_CN.properties / messages_en_US.properties) for cross-platform
 * parity.
 *
 * Fallback chain: requested locale -> zh_CN (hard default) -> key itself,
 * matching the Java Utf8Control fallback behaviour.
 */
class I18n {

    public const DEFAULT_LOCALE = "zh_CN";

    /** @var array<string, array<string, string>> */
    private array $bundles;

    public function __construct() {
        $this->bundles = [];
        $this->loadLangDir($this->resolveLangDir());
    }

    /**
     * Resolve the absolute path to the lang/ directory.
     *
     * When the plugin is packaged as a phar, the resources are bundled inside
     * the phar archive (PocketMine copies ``resources/`` to plugin_data on
     * first run, but the originals stay accessible inside the phar too). We
     * prefer the phar-bundled resources when running from a phar, then fall
     * back to the on-disk ``resources/lang`` next to ``src/`` (dev / test
     * checkout).
     */
    private function resolveLangDir(): string {
        // Running from a packed phar: resources live under phar://.../resources/lang.
        $phar = Phar::running(false);
        if ($phar !== "") {
            return $phar . "/resources/lang";
        }
        // Dev / test: src/NovaChat/I18n/I18n.php -> dirname x3 = plugin root.
        return dirname(__DIR__, 3) . "/resources/lang";
    }

    /**
     * Scan the lang/ directory and load every <locale>.json file.
     *
     * The filename stem (e.g. ``zh_CN`` for ``zh_CN.json``) becomes the locale
     * key. Files that fail to parse are skipped silently so a single
     * malformed file never breaks the whole provider.
     */
    private function loadLangDir(string $dir): void {
        if (!is_dir($dir)) {
            return;
        }
        /** @var list<string>|false $files */
        $files = @scandir($dir);
        if ($files === false) {
            return;
        }
        foreach ($files as $file) {
            if (!str_ends_with($file, ".json")) {
                continue;
            }
            $locale = substr($file, 0, -strlen(".json"));
            $path = $dir . "/" . $file;
            $contents = @file_get_contents($path);
            if ($contents === false) {
                continue;
            }
            $data = json_decode($contents, true);
            if (!is_array($data)) {
                continue;
            }
            $bundle = [];
            foreach ($data as $key => $value) {
                if (is_string($key) && is_string($value)) {
                    $bundle[$key] = $value;
                }
            }
            if ($bundle !== []) {
                $this->bundles[$locale] = $bundle;
            }
        }
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
