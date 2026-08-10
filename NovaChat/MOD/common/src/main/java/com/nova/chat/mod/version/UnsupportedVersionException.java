package com.nova.chat.mod.version;

/**
 * Exception thrown when an unsupported Minecraft version is detected.
 * 
 * Requirements: 4.4 - Clear error message for incompatible versions
 */
public class UnsupportedVersionException extends Exception {
    
    private final String detectedVersion;
    private final String supportedRange;
    
    /**
     * Creates a new UnsupportedVersionException with a message.
     * @param message the error message
     */
    public UnsupportedVersionException(String message) {
        super(message);
        this.detectedVersion = null;
        this.supportedRange = null;
    }
    
    /**
     * Creates a new UnsupportedVersionException with version details.
     * @param detectedVersion the detected Minecraft version
     * @param supportedRange the supported version range
     */
    public UnsupportedVersionException(String detectedVersion, String supportedRange) {
        super(String.format(
                "Minecraft version %s is not supported. Supported versions: %s",
                detectedVersion, supportedRange));
        this.detectedVersion = detectedVersion;
        this.supportedRange = supportedRange;
    }
    
    /**
     * Gets the detected Minecraft version.
     * @return the detected version, or null if not specified
     */
    public String getDetectedVersion() {
        return detectedVersion;
    }
    
    /**
     * Gets the supported version range.
     * @return the supported range, or null if not specified
     */
    public String getSupportedRange() {
        return supportedRange;
    }
}
