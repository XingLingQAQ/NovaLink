package com.nova.link.announcement;
import com.nova.chat.common.NovaConstants;

/**
 * Result of an announcement operation.
 * 
 * Requirements: 14.1-14.6
 */
public class AnnouncementResult {

    /** Error code for bad request */
    public static final String CODE_BAD_REQUEST = NovaConstants.ERROR_BAD_REQUEST;

    /** Error code for forbidden operation */
    public static final String CODE_FORBIDDEN = NovaConstants.ERROR_FORBIDDEN;

    /** Error code for not found */
    public static final String CODE_NOT_FOUND = NovaConstants.ERROR_NOT_FOUND;

    /** Error code for internal error */
    public static final String CODE_INTERNAL_ERROR = NovaConstants.ERROR_INTERNAL;

    private final boolean success;
    private final String message;
    private final String errorCode;
    private final Announcement announcement;

    private AnnouncementResult(boolean success, String message, String errorCode, Announcement announcement) {
        this.success = success;
        this.message = message;
        this.errorCode = errorCode;
        this.announcement = announcement;
    }

    /**
     * Creates a successful result.
     *
     * @param message the success message
     * @return the result
     */
    public static AnnouncementResult success(String message) {
        return new AnnouncementResult(true, message, null, null);
    }

    /**
     * Creates a successful result with the created announcement.
     *
     * @param message the success message
     * @param announcement the created announcement
     * @return the result
     */
    public static AnnouncementResult success(String message, Announcement announcement) {
        return new AnnouncementResult(true, message, null, announcement);
    }

    /**
     * Creates a bad request result.
     *
     * @param message the error message
     * @return the result
     */
    public static AnnouncementResult badRequest(String message) {
        return new AnnouncementResult(false, message, CODE_BAD_REQUEST, null);
    }

    /**
     * Creates a forbidden result.
     *
     * @param message the error message
     * @return the result
     */
    public static AnnouncementResult forbidden(String message) {
        return new AnnouncementResult(false, message, CODE_FORBIDDEN, null);
    }

    /**
     * Creates a not found result.
     *
     * @param message the error message
     * @return the result
     */
    public static AnnouncementResult notFound(String message) {
        return new AnnouncementResult(false, message, CODE_NOT_FOUND, null);
    }

    /**
     * Creates an internal error result.
     *
     * @param message the error message
     * @return the result
     */
    public static AnnouncementResult internalError(String message) {
        return new AnnouncementResult(false, message, CODE_INTERNAL_ERROR, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Announcement getAnnouncement() {
        return announcement;
    }

    @Override
    public String toString() {
        return "AnnouncementResult{" +
                "success=" + success +
                ", message='" + message + '\'' +
                ", errorCode='" + errorCode + '\'' +
                '}';
    }
}
