package com.nova.chat.common.extension;

/**
 * Exception thrown when an extension fails to load, enable, or disable.
 */
public class ExtensionException extends Exception {
    
    private final String extensionId;
    
    /**
     * Creates a new ExtensionException.
     * 
     * @param message the error message
     */
    public ExtensionException(String message) {
        super(message);
        this.extensionId = null;
    }
    
    /**
     * Creates a new ExtensionException with a cause.
     * 
     * @param message the error message
     * @param cause the underlying cause
     */
    public ExtensionException(String message, Throwable cause) {
        super(message, cause);
        this.extensionId = null;
    }
    
    /**
     * Creates a new ExtensionException for a specific extension.
     * 
     * @param extensionId the ID of the extension that failed
     * @param message the error message
     */
    public ExtensionException(String extensionId, String message) {
        super(message);
        this.extensionId = extensionId;
    }
    
    /**
     * Creates a new ExtensionException for a specific extension with a cause.
     * 
     * @param extensionId the ID of the extension that failed
     * @param message the error message
     * @param cause the underlying cause
     */
    public ExtensionException(String extensionId, String message, Throwable cause) {
        super(message, cause);
        this.extensionId = extensionId;
    }
    
    /**
     * Gets the ID of the extension that caused this exception.
     * 
     * @return the extension ID, or null if not associated with a specific extension
     */
    public String getExtensionId() {
        return extensionId;
    }
}
