<?php

declare(strict_types=1);

namespace NovaChat\Tests\I18n;

use PHPUnit\Framework\TestCase;

/**
 * VERIFY-007 PMMP — Error-code i18n parity regression test.
 *
 * Asserts that the PMMP i18n bundles (en_US.json + zh_CN.json) contain
 * localized keys for every canonical NC error code that the Java
 * `ErrorCode` enum defines. The canonical set (26 codes) is pinned at the
 * top of this class so a drift in either direction — a code added to Java
 * but missing from a PHP bundle, or a code removed from Java but left in a
 * PHP bundle — is caught on the next test run.
 *
 * For each canonical code, both locales MUST define:
 *   - error.<code>.message      (human-readable error text)
 *   - error.<code>.suggestion   (remediation hint)
 *
 * The shared `error.suggestion_prefix` key ("Hint:" / "提示:") is also
 * pinned because I18n::errorMessage() unconditionally looks it up.
 *
 * No production source was modified. The test reads the JSON bundles
 * directly from resources/lang/ (the same files I18n::loadLangDir scans).
 */
final class ErrorCodeI18nParityTest extends TestCase {

    /**
     * Canonical NC error codes that MUST appear in every locale bundle.
     *
     * This set mirrors the Java `com.nova.chat.common.error.ErrorCode`
     * enum (the authoritative source of truth). When a code is added to or
     * removed from the Java enum, this array must change in the same commit
     * and the PHP bundles updated.
     */
    private const CANONICAL_CODES = [
        'NC-400',
        'NC-401',
        'NC-403',
        'NC-404',
        'NC-409',
        'NC-410',
        'NC-411',
        'NC-420',
        'NC-429',
        'NC-430',
        'NC-431',
        'NC-432',
        'NC-433',
        'NC-434',
        'NC-435',
        'NC-436',
        'NC-437',
        'NC-438',
        'NC-439',
        'NC-500',
        'NC-501',
        'NC-502',
        'NC-503',
        'NC-504',
        'NC-510',
        'NC-511',
    ];

    /** Locales that MUST ship a complete bundle. */
    private const REQUIRED_LOCALES = ['en_US', 'zh_CN'];

    /** Shared keys (not per-code) that I18n::errorMessage() depends on. */
    private const SHARED_KEYS = ['error.suggestion_prefix'];

    /**
     * Resolves the path to a locale bundle.
     *
     * __DIR__ = .../pmmp/tests/I18n → up 2 = .../pmmp (plugin root).
     * The bundles live at resources/lang/<locale>.json, the same directory
     * I18n::resolveLangDir() scans in dev/test mode.
     */
    private static function langFile(string $locale): string {
        return dirname(__DIR__, 2) . '/resources/lang/' . $locale . '.json';
    }

    /**
     * Loads and decodes a locale bundle as an associative array.
     *
     * @return array<string, string>
     */
    private static function loadBundle(string $locale): array {
        $path = self::langFile($locale);
        self::assertFileExists($path, "Missing i18n bundle for locale $locale: $path");

        $contents = file_get_contents($path);
        self::assertNotFalse($contents, "Failed to read i18n bundle: $path");

        $data = json_decode($contents, true);
        self::assertIsArray($data, "i18n bundle is not a JSON object: $path");
        /** @var array<string, string> $data */
        return $data;
    }

    // ==================================================================
    // Per-locale structural tests
    // ==================================================================

    /**
     * @dataProvider requiredLocalesProvider
     */
    public function testBundleIsNonEmpty(string $locale): void {
        $bundle = self::loadBundle($locale);
        self::assertNotEmpty($bundle, "Bundle for $locale must not be empty");
    }

    /**
     * @dataProvider requiredLocalesProvider
     */
    public function testSharedKeysExist(string $locale): void {
        $bundle = self::loadBundle($locale);
        foreach (self::SHARED_KEYS as $key) {
            self::assertArrayHasKey(
                $key,
                $bundle,
                "Locale $locale missing shared key: $key"
            );
            self::assertNotSame(
                '',
                $bundle[$key],
                "Locale $locale has empty value for shared key: $key"
            );
        }
    }

    // ==================================================================
    // Per-code parity tests (the core VERIFY-007 assertion)
    // ==================================================================

    /**
     * For every canonical NC code, the locale bundle MUST define both
     * `error.<code>.message` and `error.<code>.suggestion`.
     *
     * @dataProvider requiredLocalesProvider
     */
    public function testCanonicalCodesHaveMessageAndSuggestion(string $locale): void {
        $bundle = self::loadBundle($locale);

        foreach (self::CANONICAL_CODES as $code) {
            $messageKey = "error.{$code}.message";
            $suggestionKey = "error.{$code}.suggestion";

            self::assertArrayHasKey(
                $messageKey,
                $bundle,
                "Locale $locale missing $messageKey for canonical code $code"
            );
            self::assertArrayHasKey(
                $suggestionKey,
                $bundle,
                "Locale $locale missing $suggestionKey for canonical code $code"
            );

            self::assertNotSame(
                '',
                $bundle[$messageKey],
                "Locale $locale has empty message for $code"
            );
            self::assertNotSame(
                '',
                $bundle[$suggestionKey],
                "Locale $locale has empty suggestion for $code"
            );
        }
    }

    /**
     * Cross-locale parity: the set of canonical codes is identical in both
     * locales. A code present in zh_CN but missing from en_US (or vice
     * versa) is a parity bug.
     *
     * @dataProvider canonicalCodesProvider
     */
    public function testCodePresentInBothLocales(string $code): void {
        foreach (self::REQUIRED_LOCALES as $locale) {
            $bundle = self::loadBundle($locale);
            self::assertArrayHasKey(
                "error.{$code}.message",
                $bundle,
                "Canonical code $code missing message in $locale"
            );
            self::assertArrayHasKey(
                "error.{$code}.suggestion",
                $bundle,
                "Canonical code $code missing suggestion in $locale"
            );
        }
    }

    /**
     * No locale defines a stray NC code that is not in the canonical set.
     *
     * This catches the inverse drift: a PHP bundle carrying a code that
     * Java no longer defines. (NC-402 and NC-408 are intentionally excluded
     * from the canonical set — 402 is unused, 408 is reserved — so their
     * presence would be flagged here.)
     *
     * @dataProvider requiredLocalesProvider
     */
    public function testNoStrayErrorCodes(string $locale): void {
        $bundle = self::loadBundle($locale);
        $canonical = array_flip(self::CANONICAL_CODES);

        $stray = [];
        foreach ($bundle as $key => $_) {
            if (preg_match('/^error\.(NC-\d{3})\.(message|suggestion)$/', $key, $m)) {
                if (!isset($canonical[$m[1]])) {
                    $stray[$m[1]] = true;
                }
            }
        }

        self::assertSame(
            [],
            array_keys($stray),
            "Locale $locale defines error codes not in the canonical set: " . implode(', ', array_keys($stray))
        );
    }

    // ==================================================================
    // Data providers
    // ==================================================================

    /**
     * @return array<string, array{0: string}>
     */
    public static function requiredLocalesProvider(): array {
        $cases = [];
        foreach (self::REQUIRED_LOCALES as $locale) {
            $cases[$locale] = [$locale];
        }
        return $cases;
    }

    /**
     * @return array<string, array{0: string}>
     */
    public static function canonicalCodesProvider(): array {
        $cases = [];
        foreach (self::CANONICAL_CODES as $code) {
            $cases[$code] = [$code];
        }
        return $cases;
    }
}
