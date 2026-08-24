package com.nova.link.announcement;

/**
 * Result of a campaign operation (§11.6 提案 06 — slice A).
 *
 * <p>Mirrors {@link AnnouncementResult}: factories for the common outcomes
 * (success / badRequest / notFound / forbidden / rateLimited / internalError)
 * plus the created/updated {@link Campaign} for success cases.
 */
public final class CampaignResult {

    /** Error code for bad request */
    public static final String CODE_BAD_REQUEST = "NC-400";
    /** Error code for forbidden operation */
    public static final String CODE_FORBIDDEN = "NC-403";
    /** Error code for not found */
    public static final String CODE_NOT_FOUND = "NC-404";
    /** Error code for rate-limited (too many deliveries in the window) */
    public static final String CODE_RATE_LIMITED = "NC-429";
    /** Error code for internal error */
    public static final String CODE_INTERNAL_ERROR = "NC-500";

    private final boolean success;
    private final String message;
    private final String errorCode;
    private final Campaign campaign;

    private CampaignResult(boolean success, String message, String errorCode, Campaign campaign) {
        this.success = success;
        this.message = message;
        this.errorCode = errorCode;
        this.campaign = campaign;
    }

    public static CampaignResult success(String message) {
        return new CampaignResult(true, message, null, null);
    }

    public static CampaignResult success(String message, Campaign campaign) {
        return new CampaignResult(true, message, null, campaign);
    }

    public static CampaignResult badRequest(String message) {
        return new CampaignResult(false, message, CODE_BAD_REQUEST, null);
    }

    public static CampaignResult forbidden(String message) {
        return new CampaignResult(false, message, CODE_FORBIDDEN, null);
    }

    public static CampaignResult notFound(String message) {
        return new CampaignResult(false, message, CODE_NOT_FOUND, null);
    }

    public static CampaignResult rateLimited(String message) {
        return new CampaignResult(false, message, CODE_RATE_LIMITED, null);
    }

    public static CampaignResult internalError(String message) {
        return new CampaignResult(false, message, CODE_INTERNAL_ERROR, null);
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

    public Campaign getCampaign() {
        return campaign;
    }

    @Override
    public String toString() {
        return "CampaignResult{"
                + "success=" + success
                + ", message='" + message + '\''
                + ", errorCode='" + errorCode + '\''
                + '}';
    }
}
