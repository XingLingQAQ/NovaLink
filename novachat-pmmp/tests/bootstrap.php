<?php

declare(strict_types=1);

/**
 * Custom autoloader for NovaChat-PMMP tests.
 *
 * This bootstrap loads:
 * 1. NovaChat PSR-4 classes from src/
 * 2. Eris library (cloned from github into vendor/giorgiosironi/eris)
 * 3. PHPUnit phar (if not loaded via composer)
 */

// 1. NovaChat PSR-4 autoloader
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

// 2. Eris PSR-4 autoloader (cloned into vendor/giorgiosironi/eris)
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

// 3. Load PHPUnit phar if running standalone (not via composer)
$phpunitPhar = __DIR__ . '/../vendor/bin/phpunit.phar';
if (file_exists($phpunitPhar) && !class_exists('PHPUnit\\Framework\\TestCase', false)) {
    require $phpunitPhar;
}
