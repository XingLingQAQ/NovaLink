package com.nova.chat.client.error;

import com.nova.chat.client.i18n.I18n;

/**
 * Shared enumeration of all NovaChat error codes, available to every platform
 * plugin via {@code novachat-client-core}.
 *
 * <p>Error codes follow the NC-XXX format where:
 * <ul>
 *   <li>NC-4XX: Client errors (user input, permissions, etc.)</li>
 *   <li>NC-5XX: Server errors (backend, network, etc.)</li>
 * </ul>
 *
 * <p>Each code carries a short {@link #getMessage() message} and an actionable
 * {@link #getSuggestion() suggestion} resolved through {@link I18n} (keys
 * {@code error.<code>.message} / {@code error.<code>.suggestion}) so platforms
 * render consistent, operator-friendly, locale-aware error text instead of
 * generic "not connected" strings. The natural-language text lives in
 * {@code messages_zh_CN.properties} / {@code messages_en_US.properties}; the
 * enum itself only owns the stable {@code NC-XXX} code string.
 *
 * <p>Architecture B: plugin-only. Never imported by {@code novalink-core}.
 *
 * <p>Requirements: 27.1-27.4
 */
public enum ErrorCode {

    // ==========================================
    // 4XX - Client Errors (客户端错误)
    // ==========================================

    /** Bad Request - 请求参数错误 */
    BAD_REQUEST("NC-400"),

    /** Unauthorized - 认证失败 */
    UNAUTHORIZED("NC-401"),

    /** Forbidden - 权限不足 */
    FORBIDDEN("NC-403"),

    /** Not Found - 资源不存在 */
    NOT_FOUND("NC-404"),

    /** Conflict - 资源冲突 */
    CONFLICT("NC-409"),

    /** Gone - 邀请码过期 */
    INVITE_EXPIRED("NC-410"),

    /** Used - 邀请码已使用 */
    INVITE_USED("NC-411"),

    /** Protocol Mismatch - 协议版本不兼容 */
    PROTOCOL_MISMATCH("NC-420"),

    /** Too Many Requests - 请求过于频繁 */
    RATE_LIMITED("NC-429"),

    /** Invalid Format - 格式错误 */
    INVALID_FORMAT("NC-430"),

    /** Channel Full - 频道已满 */
    CHANNEL_FULL("NC-431"),

    /** Already Joined - 已加入频道 */
    ALREADY_JOINED("NC-432"),

    /** Not In Channel - 不在频道中 */
    NOT_IN_CHANNEL("NC-433"),

    /** Wrong Password - 密码错误 */
    WRONG_PASSWORD("NC-434"),

    /** World Restricted - 世界限制 */
    WORLD_RESTRICTED("NC-435"),

    /** Muted - 被禁言 */
    MUTED("NC-436"),

    /** Self Action - 不能对自己操作 */
    SELF_ACTION("NC-437"),

    /** Target Offline - 目标玩家离线 */
    TARGET_OFFLINE("NC-438"),

    /** Invalid Duration - 无效时长 */
    INVALID_DURATION("NC-439"),

    // ==========================================
    // 5XX - Server Errors (服务端错误)
    // ==========================================

    /** Internal Error - 服务器内部错误 */
    INTERNAL_ERROR("NC-500"),

    /** Not Implemented - 功能未实现 */
    NOT_IMPLEMENTED("NC-501"),

    /** Bad Gateway - 后端网关错误 */
    BAD_GATEWAY("NC-502"),

    /** Service Unavailable - 服务不可用 */
    SERVICE_UNAVAILABLE("NC-503"),

    /** Gateway Timeout - 网关超时 */
    GATEWAY_TIMEOUT("NC-504"),

    /** Database Error - 数据库错误 */
    DATABASE_ERROR("NC-510"),

    /** Config Error - 配置错误 */
    CONFIG_ERROR("NC-511");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    /**
     * Gets the error code string (e.g., "NC-401").
     *
     * @return the error code
     */
    public String getCode() {
        return code;
    }

    /**
     * Gets the error message, resolved through {@link I18n} in the current
     * default locale (key {@code error.<code>.message}).
     *
     * @return the error message, never null
     */
    public String getMessage() {
        return I18n.tr("error." + code + ".message");
    }

    /**
     * Gets the suggestion for resolving the error, resolved through
     * {@link I18n} in the current default locale (key
     * {@code error.<code>.suggestion}).
     *
     * @return the suggestion, never null
     */
    public String getSuggestion() {
        return I18n.tr("error." + code + ".suggestion");
    }

    /**
     * Checks if this is a client error (4XX).
     *
     * @return true if client error
     */
    public boolean isClientError() {
        return code.startsWith("NC-4");
    }

    /**
     * Checks if this is a server error (5XX).
     *
     * @return true if server error
     */
    public boolean isServerError() {
        return code.startsWith("NC-5");
    }

    /**
     * Finds an ErrorCode by its code string.
     *
     * <p>Unknown codes resolve to {@link #INTERNAL_ERROR} (NC-500) so callers
     * always get a renderable, actionable result rather than null.
     *
     * @param code the code string (e.g., "NC-401"); null returns INTERNAL_ERROR
     * @return the ErrorCode, never null (unknown → INTERNAL_ERROR)
     */
    public static ErrorCode fromCode(String code) {
        if (code == null) {
            return INTERNAL_ERROR;
        }
        for (ErrorCode errorCode : values()) {
            if (errorCode.code.equals(code)) {
                return errorCode;
            }
        }
        return INTERNAL_ERROR;
    }

    /**
     * Finds an ErrorCode by its numeric code.
     *
     * @param numericCode the numeric code (e.g., 401)
     * @return the ErrorCode, never null (unknown → INTERNAL_ERROR)
     */
    public static ErrorCode fromNumericCode(int numericCode) {
        return fromCode("NC-" + numericCode);
    }

    @Override
    public String toString() {
        return code + ": " + getMessage();
    }
}
