package com.nova.chat.client.error;

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
 * <p>Each code carries a short {@link #message} and an actionable
 * {@link #suggestion} so platforms can render consistent, operator-friendly
 * error text instead of generic "not connected" strings.
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
    BAD_REQUEST("NC-400", "请求参数错误", "请检查命令参数是否正确"),

    /** Unauthorized - 认证失败 */
    UNAUTHORIZED("NC-401", "认证失败", "请检查用户名和密码是否正确"),

    /** Forbidden - 权限不足 */
    FORBIDDEN("NC-403", "权限不足", "您没有执行此操作的权限，请联系管理员"),

    /** Not Found - 资源不存在 */
    NOT_FOUND("NC-404", "资源不存在", "请检查频道ID或玩家名称是否正确"),

    /** Conflict - 资源冲突 */
    CONFLICT("NC-409", "资源冲突", "该资源已存在或正在被使用"),

    /** Gone - 邀请码过期 */
    INVITE_EXPIRED("NC-410", "邀请码已过期", "请联系频道管理员获取新的邀请码"),

    /** Used - 邀请码已使用 */
    INVITE_USED("NC-411", "邀请码已使用", "每个邀请码只能使用一次"),

    /** Too Many Requests - 请求过于频繁 */
    RATE_LIMITED("NC-429", "请求过于频繁", "请稍后再试"),

    /** Invalid Format - 格式错误 */
    INVALID_FORMAT("NC-430", "格式错误", "请检查输入格式是否正确"),

    /** Channel Full - 频道已满 */
    CHANNEL_FULL("NC-431", "频道已满", "该频道已达到最大容量"),

    /** Already Joined - 已加入频道 */
    ALREADY_JOINED("NC-432", "已加入该频道", "您已经在该频道中"),

    /** Not In Channel - 不在频道中 */
    NOT_IN_CHANNEL("NC-433", "不在该频道中", "您需要先加入该频道"),

    /** Wrong Password - 密码错误 */
    WRONG_PASSWORD("NC-434", "密码错误", "请检查频道密码是否正确"),

    /** World Restricted - 世界限制 */
    WORLD_RESTRICTED("NC-435", "世界限制", "该频道仅在特定世界可用"),

    /** Muted - 被禁言 */
    MUTED("NC-436", "您已被禁言", "禁言期间无法发送消息"),

    /** Self Action - 不能对自己操作 */
    SELF_ACTION("NC-437", "不能对自己执行此操作", "请选择其他玩家"),

    /** Target Offline - 目标玩家离线 */
    TARGET_OFFLINE("NC-438", "目标玩家离线", "请确认玩家在线后再试"),

    /** Invalid Duration - 无效时长 */
    INVALID_DURATION("NC-439", "无效的时间格式", "请使用正确的时间格式，如: 1h, 30m, 1d"),

    // ==========================================
    // 5XX - Server Errors (服务端错误)
    // ==========================================

    /** Internal Error - 服务器内部错误 */
    INTERNAL_ERROR("NC-500", "服务器内部错误", "请联系管理员检查服务器日志"),

    /** Not Implemented - 功能未实现 */
    NOT_IMPLEMENTED("NC-501", "功能未实现", "该功能尚未开放"),

    /** Bad Gateway - 后端网关错误 */
    BAD_GATEWAY("NC-502", "后端网关错误", "请检查后端服务是否正常运行"),

    /** Service Unavailable - 服务不可用 */
    SERVICE_UNAVAILABLE("NC-503", "服务不可用", "未连接到后端服务器，请稍后再试"),

    /** Gateway Timeout - 网关超时 */
    GATEWAY_TIMEOUT("NC-504", "请求超时", "后端响应超时，请稍后再试"),

    /** Database Error - 数据库错误 */
    DATABASE_ERROR("NC-510", "数据库错误", "数据存储出现问题，请联系管理员"),

    /** Config Error - 配置错误 */
    CONFIG_ERROR("NC-511", "配置错误", "请检查配置文件是否正确");

    private final String code;
    private final String message;
    private final String suggestion;

    ErrorCode(String code, String message, String suggestion) {
        this.code = code;
        this.message = message;
        this.suggestion = suggestion;
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
     * Gets the error message.
     *
     * @return the error message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Gets the suggestion for resolving the error.
     *
     * @return the suggestion
     */
    public String getSuggestion() {
        return suggestion;
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
        return code + ": " + message;
    }
}
