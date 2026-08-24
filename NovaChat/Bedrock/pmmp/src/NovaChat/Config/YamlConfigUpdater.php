<?php

declare(strict_types=1);

namespace NovaChat\Config;

use pocketmine\utils\Config;
use RuntimeException;
use Throwable;

final class ConfigUpdateResult {
    public function __construct(
        public readonly bool $created,
        public readonly bool $updated,
        public readonly ?string $backupPath
    ) {
    }
}

/**
 * Installs and updates config.yml without re-serializing operator content.
 *
 * PocketMine's YAML parser validates both documents. Missing entries are
 * copied from the bundled template as text blocks while existing values,
 * comments, formatting, unknown keys, and dynamic mappings are preserved.
 */
final class YamlConfigUpdater {

    /**
     * @param list<string> $dynamicMappings
     * @param null|callable(array<mixed>): void $validator
     */
    public static function update(
        string $configPath,
        string $templateContent,
        array $dynamicMappings,
        ?callable $validator = null
    ): ConfigUpdateResult {
        $template = self::parseYaml($templateContent, "bundled config.yml template");
        if ($validator !== null) {
            $validator($template);
        }
        $directory = dirname($configPath);
        if (!is_dir($directory) && !mkdir($directory, 0777, true) && !is_dir($directory)) {
            throw new RuntimeException("Failed to create configuration directory: " . $directory);
        }

        if (!is_file($configPath)) {
            self::writeAtomically($configPath, $templateContent);
            return new ConfigUpdateResult(true, false, null);
        }

        $original = file_get_contents($configPath);
        if ($original === false) {
            throw new RuntimeException("Failed to read existing config.yml");
        }
        $current = self::parseYaml($original, "existing config.yml");
        $operations = [];
        self::collectOperations(
            $current,
            $template,
            "",
            array_fill_keys($dynamicMappings, true),
            $operations
        );
        if ($operations === []) {
            if ($validator !== null) {
                $validator($current);
            }
            return new ConfigUpdateResult(false, false, null);
        }

        $updated = $original;
        foreach ($operations as $operation) {
            $updated = self::applyOperation(
                $updated,
                $templateContent,
                $operation["path"],
                array_fill_keys($dynamicMappings, true)
            );
        }
        $generated = self::parseYaml($updated, "generated config.yml");
        if ($validator !== null) {
            $validator($generated);
        }

        $backupPath = $configPath . ".bak";
        if (!copy($configPath, $backupPath)) {
            throw new RuntimeException("Failed to create configuration backup: " . $backupPath);
        }
        self::writeAtomically($configPath, $updated);
        return new ConfigUpdateResult(false, true, $backupPath);
    }

    /**
     * @param array<mixed> $current
     * @param array<mixed> $template
     * @param array<string, bool> $dynamicMappings
     * @param list<array{path: string}> $operations
     */
    private static function collectOperations(
        array $current,
        array $template,
        string $parentPath,
        array $dynamicMappings,
        array &$operations
    ): void {
        foreach ($template as $key => $templateValue) {
            $path = $parentPath === "" ? (string) $key : $parentPath . "." . $key;
            if (!array_key_exists($key, $current)) {
                // Dynamic mappings are owned by the operator/runtime. Their
                // absence means an empty mapping, not permission to restore
                // the template's example entries during an upgrade.
                if (!isset($dynamicMappings[$path])) {
                    $operations[] = ["path" => $path];
                }
                continue;
            }

            $currentValue = $current[$key];
            if (is_array($templateValue) && is_array($currentValue)
                && !isset($dynamicMappings[$path])) {
                self::collectOperations(
                    $currentValue,
                    $templateValue,
                    $path,
                    $dynamicMappings,
                    $operations
                );
            }
        }
    }

    private static function applyOperation(
        string $currentContent,
        string $templateContent,
        string $path,
        array $dynamicMappings
    ): string {
        [$currentLines, $lineEnding, $currentTrailingNewline] = self::splitLines($currentContent);
        [$templateLines] = self::splitLines($templateContent);
        $currentEntries = self::parseEntries($currentLines);
        $templateEntries = self::parseEntries($templateLines);
        if (!isset($templateEntries[$path])) {
            throw new RuntimeException("Template entry cannot be located: " . $path);
        }

        $templateEntry = $templateEntries[$path];
        $templateStart = $templateEntry["commentStart"];
        $blockStart = $templateStart;
        $blockEnd = $templateEntry["endLine"];
        $excludedRanges = [];
        foreach ($dynamicMappings as $dynamicPath => $_) {
            if ($dynamicPath === $path || !str_starts_with($dynamicPath, $path . ".")) {
                continue;
            }
            if (!isset($templateEntries[$dynamicPath])) {
                continue;
            }
            $dynamicEntry = $templateEntries[$dynamicPath];
            $excludedRanges[] = [
                max($blockStart, $dynamicEntry["commentStart"]),
                min($blockEnd, $dynamicEntry["endLine"]),
            ];
        }

        $block = array_slice(
            $templateLines,
            $blockStart,
            $blockEnd - $blockStart
        );
        if ($excludedRanges !== []) {
            $block = array_values(array_filter(
                $block,
                static function (string $line, int $offset) use ($blockStart, $excludedRanges): bool {
                    $lineNumber = $blockStart + $offset;
                    foreach ($excludedRanges as [$start, $end]) {
                        if ($lineNumber >= $start && $lineNumber < $end) {
                            return false;
                        }
                    }
                    return true;
                },
                ARRAY_FILTER_USE_BOTH
            ));
        }

        $separator = strrpos($path, ".");
        $parentPath = $separator === false ? "" : substr($path, 0, $separator);
        if ($parentPath === "") {
            $insertAt = count($currentLines);
        } else {
            if (!isset($currentEntries[$parentPath])) {
                throw new RuntimeException("Parent configuration entry cannot be located: " . $parentPath);
            }
            $insertAt = $currentEntries[$parentPath]["endLine"];
        }
        array_splice($currentLines, $insertAt, 0, $block);

        $result = implode($lineEnding, $currentLines);
        if ($currentTrailingNewline && !str_ends_with($result, $lineEnding)) {
            $result .= $lineEnding;
        }
        return $result;
    }

    /**
     * @param list<string> $lines
     * @return array<string, array{keyLine: int, commentStart: int, endLine: int, indent: int}>
     */
    private static function parseEntries(array $lines): array {
        $entries = [];
        $orderedPaths = [];
        $stack = [];

        foreach ($lines as $lineNumber => $line) {
            if (!preg_match('/^( *)([A-Za-z0-9_.-]+)\s*:/u', $line, $matches)) {
                continue;
            }
            $indent = strlen($matches[1]);
            $key = $matches[2];
            while ($stack !== [] && $stack[array_key_last($stack)]["indent"] >= $indent) {
                array_pop($stack);
            }
            $parentPath = $stack === [] ? "" : $stack[array_key_last($stack)]["path"];
            $path = $parentPath === "" ? $key : $parentPath . "." . $key;
            $parentKeyLine = $stack === [] ? -1 : $stack[array_key_last($stack)]["line"];
            $commentStart = $lineNumber;
            while ($commentStart > $parentKeyLine + 1) {
                $previous = $lines[$commentStart - 1];
                if (trim($previous) !== "" && !str_starts_with(ltrim($previous), "#")) {
                    break;
                }
                --$commentStart;
            }
            $entries[$path] = [
                "keyLine" => $lineNumber,
                "commentStart" => $commentStart,
                "endLine" => count($lines),
                "indent" => $indent,
            ];
            $orderedPaths[] = $path;
            $stack[] = ["indent" => $indent, "path" => $path, "line" => $lineNumber];
        }

        foreach ($orderedPaths as $index => $path) {
            $entry = $entries[$path];
            for ($next = $index + 1, $count = count($orderedPaths); $next < $count; ++$next) {
                $candidate = $entries[$orderedPaths[$next]];
                if ($candidate["indent"] <= $entry["indent"]) {
                    $entries[$path]["endLine"] = $candidate["commentStart"];
                    break;
                }
            }
        }
        return $entries;
    }

    /**
     * @return array{0: list<string>, 1: string, 2: bool}
     */
    private static function splitLines(string $content): array {
        $lineEnding = str_contains($content, "\r\n") ? "\r\n" : "\n";
        $trailingNewline = str_ends_with($content, "\n");
        $lines = preg_split('/\r\n|\n|\r/', $content);
        if ($lines === false) {
            throw new RuntimeException("Failed to split YAML document into lines");
        }
        if ($trailingNewline && $lines !== [] && end($lines) === "") {
            array_pop($lines);
        }
        return [$lines, $lineEnding, $trailingNewline];
    }

    /**
     * @return array<mixed>
     */
    private static function parseYaml(string $content, string $source): array {
        $tempPath = tempnam(sys_get_temp_dir(), "novachat-yaml-");
        if ($tempPath === false) {
            throw new RuntimeException("Failed to create temporary YAML file");
        }
        try {
            if (file_put_contents($tempPath, $content) === false) {
                throw new RuntimeException("Failed to write temporary YAML file");
            }
            $config = new Config($tempPath, Config::YAML);
            $data = $config->getAll();
            if (!is_array($data)) {
                throw new RuntimeException($source . " root must be a YAML mapping");
            }
            return $data;
        } catch (Throwable $throwable) {
            throw new RuntimeException($source . " is invalid YAML: " . $throwable->getMessage(), 0, $throwable);
        } finally {
            @unlink($tempPath);
        }
    }

    private static function writeAtomically(string $path, string $content): void {
        $directory = dirname($path);
        $tempPath = tempnam($directory, "." . basename($path) . ".");
        if ($tempPath === false) {
            throw new RuntimeException("Failed to create temporary configuration file");
        }
        if (realpath(dirname($tempPath)) !== realpath($directory)) {
            @unlink($tempPath);
            throw new RuntimeException(
                "Temporary configuration file was not created beside the live file"
            );
        }
        try {
            $handle = fopen($tempPath, "wb");
            if ($handle === false) {
                throw new RuntimeException("Failed to open temporary configuration file");
            }
            try {
                $length = strlen($content);
                $offset = 0;
                while ($offset < $length) {
                    $written = fwrite($handle, substr($content, $offset));
                    if ($written === false || $written === 0) {
                        throw new RuntimeException("Failed to write temporary configuration file");
                    }
                    $offset += $written;
                }
                if (!fflush($handle)) {
                    throw new RuntimeException("Failed to flush temporary configuration file");
                }
                if (function_exists("fsync") && !fsync($handle)) {
                    throw new RuntimeException("Failed to sync temporary configuration file");
                }
            } finally {
                fclose($handle);
            }
            if (!@rename($tempPath, $path)) {
                throw new RuntimeException("Failed to atomically replace configuration file");
            }
        } finally {
            @unlink($tempPath);
        }
    }
}
