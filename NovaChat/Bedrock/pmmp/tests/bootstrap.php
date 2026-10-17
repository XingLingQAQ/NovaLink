<?php

declare(strict_types=1);

/**
 * Custom autoloader for NovaChat-PMMP tests.
 *
 * This bootstrap loads:
 * 1. Composer's autoloader (when vendor/ is installed) so the pocketmine
 *    runtime classes NovaChatPlugin extends -- pocketmine\plugin\PluginBase,
 *    pocketmine\utils\TextFormat, the scheduler/event APIs -- are resolvable.
 *    Without it every test that builds a NovaChatPlugin double dies with
 *    'Class "pocketmine\plugin\PluginBase" not found'.
 * 2. NovaChat PSR-4 classes from src/
 * 3. Eris library (cloned from github into vendor/giorgiosironi/eris)
 * 4. PHPUnit phar (if not loaded via composer)
 */

// 1. Composer autoloader (pocketmine runtime + dev deps). Guarded so the
//    phar-only path still works on a host where vendor/ was never installed;
//    tests that need pocketmine runtime classes will then fail loudly with a
//    clear "class not found" rather than being silently skipped.
$composerAutoload = __DIR__ . '/../vendor/autoload.php';
if (file_exists($composerAutoload)) {
    require $composerAutoload;
}

// 2. NovaChat PSR-4 autoloader
spl_autoload_register(function (string $class): void {
    $prefix = 'NovaChat\\';
    $baseDir = __DIR__ . '/../src/NovaChat/';

    if (!str_starts_with($class, $prefix)) {
        return;
    }

    $relativeClass = substr($class, strlen($prefix));
    $file = $baseDir . str_replace('\\', '/', $relativeClass) . '.php';

    if (file_exists($file)) {
        require $file;
    }
});

// 3. Eris PSR-4 autoloader (cloned into vendor/giorgiosironi/eris)
$erisBase = __DIR__ . '/../vendor/giorgiosironi/eris/src/';
spl_autoload_register(function (string $class) use ($erisBase): void {
    $prefix = 'Eris\\';

    if (!str_starts_with($class, $prefix)) {
        return;
    }

    $relativeClass = substr($class, strlen($prefix));
    $file = $erisBase . str_replace('\\', '/', $relativeClass) . '.php';

    if (file_exists($file)) {
        require $file;
    }
});

// 4. Load PHPUnit phar if running standalone (not via composer)
$phpunitPhar = __DIR__ . '/../vendor/bin/phpunit.phar';
if (file_exists($phpunitPhar) && !class_exists('PHPUnit\\Framework\\TestCase', false)) {
    require $phpunitPhar;
}
