<?php

declare(strict_types=1);

namespace NovaChat\Extension;

use Exception;

/**
 * Exception thrown when an extension fails to load or encounters an error.
 * 
 * Requirements: 10.1 - THE PMMP 扩展 SHALL 使用 PHP 编写并放置在 extensions 目录
 */
class ExtensionException extends Exception {
    
    private ?string $extensionId;
    
    /**
     * Creates a new ExtensionException.
     * 
     * @param string $message the error message
     * @param string|null $extensionId the ID of the extension that caused the error
     * @param \Throwable|null $previous the previous exception
     */
    public function __construct(string $message, ?string $extensionId = null, ?\Throwable $previous = null) {
        $this->extensionId = $extensionId;
        
        $fullMessage = $extensionId !== null 
            ? "Extension '$extensionId': $message" 
            : $message;
        
        parent::__construct($fullMessage, 0, $previous);
    }
    
    /**
     * Gets the ID of the extension that caused the error.
     * 
     * @return string|null the extension ID, or null if not applicable
     */
    public function getExtensionId(): ?string {
        return $this->extensionId;
    }
}
