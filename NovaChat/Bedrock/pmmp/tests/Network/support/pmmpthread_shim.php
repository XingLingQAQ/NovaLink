<?php

declare(strict_types=1);

/**
 * Minimal pmmp runtime surface so AsyncTask-derived classes can be loaded off
 * a PocketMine runtime.
 *
 * pocketmine\scheduler\AsyncTask extends pmmp\thread\Runnable, and both
 * pmmpthread and igbinary are PocketMine-bundled extensions: neither is in the
 * standard PHP the CI job installs (shivammathur/setup-php with
 * sockets/mbstring/openssl), and pmmpthread is in no PECL package at all.
 * Without this shim, merely autoloading AsyncSendTask to test it dies with
 * 'Class "pmmp\thread\Runnable" not found' before a single assertion runs.
 *
 * These are type shells only -- no threading is emulated. The tests call
 * onRun()/onCompletion() directly on the calling thread, which is the same
 * thread PocketMine itself uses for onCompletion(). Every declaration is
 * guarded, so on a real PocketMine runtime, or on any host where the real
 * extensions are installed, the native definitions win and this file does
 * nothing.
 */

namespace pmmp\thread {
    if (!class_exists(ThreadSafe::class, false)) {
        class ThreadSafe {
        }
    }

    if (!class_exists(Runnable::class, false)) {
        abstract class Runnable {
            abstract public function run(): void;

            public function isTerminated(): bool {
                return false;
            }
        }
    }

    if (!class_exists(ThreadSafeArray::class, false)) {
        class ThreadSafeArray extends ThreadSafe implements \ArrayAccess, \Countable, \IteratorAggregate {
            /** @var array<int|string, mixed> */
            private array $data = [];

            public function offsetExists(mixed $offset): bool {
                return isset($this->data[$offset]);
            }

            public function offsetGet(mixed $offset): mixed {
                return $this->data[$offset] ?? null;
            }

            public function offsetSet(mixed $offset, mixed $value): void {
                if ($offset === null) {
                    $this->data[] = $value;
                } else {
                    $this->data[$offset] = $value;
                }
            }

            public function offsetUnset(mixed $offset): void {
                unset($this->data[$offset]);
            }

            public function count(): int {
                return count($this->data);
            }

            public function getIterator(): \ArrayIterator {
                return new \ArrayIterator($this->data);
            }

            public function shift(): mixed {
                return array_shift($this->data);
            }
        }
    }
}

namespace {
    // AsyncTask::setResult() wraps a non-scalar result in NonThreadSafeValue,
    // whose constructor calls igbinary_serialize(). The round trip is a no-op
    // for these tests (same thread, no worker), so plain serialize() is a
    // faithful stand-in.
    if (!function_exists('igbinary_serialize')) {
        function igbinary_serialize(mixed $value): string {
            return serialize($value);
        }
    }

    if (!function_exists('igbinary_unserialize')) {
        function igbinary_unserialize(string $value): mixed {
            return unserialize($value);
        }
    }
}
