package com.nova.chat.client.command;

import java.util.Objects;

/**
 * Outcome of a {@link ChannelCommandService} invocation.
 *
 * <p>Carries success/failure, a human-readable message (platforms may translate
 * or ignore), the originating {@link CommandIntent}, and an optional
 * {@code errorCode} (NC-XXX) so platforms can render actionable error text via
 * {@link com.nova.chat.client.error.ErrorMessageFormatter}.
 */
public final class CommandResult {

    private final boolean success;
    private final String message;
    private final CommandIntent intent;
    /** NC-XXX error code, or null when the result carries no backend error. */
    private final String errorCode;

    private CommandResult(boolean success, CommandIntent intent, String message, String errorCode) {
        this.success = success;
        this.intent = Objects.requireNonNull(intent, "intent");
        this.message = message != null ? message : "";
        this.errorCode = errorCode;
    }

    /**
     * Creates a success result with no error code.
     *
     * @param intent  originating intent
     * @param message human-readable message (may be null → "")
     * @return a success result
     */
    public static CommandResult success(CommandIntent intent, String message) {
        return new CommandResult(true, intent, message, null);
    }

    /**
     * Creates a failure result carrying no NC-XXX error code. Use
     * {@link #failure(CommandIntent, String, String)} instead when the failure
     * maps to a known backend error code so platforms can render actionable text.
     *
     * @param intent  originating intent
     * @param message human-readable message (may be null → "")
     * @return a failure result with a null error code
     */
    public static CommandResult failure(CommandIntent intent, String message) {
        return new CommandResult(false, intent, message, null);
    }

    /**
     * Failure carrying an NC-XXX error code so platforms can map it to a
     * formatted, actionable message via {@link ErrorMessageFormatter}.
     *
     * @param intent    originating intent
     * @param message   human-readable message
     * @param errorCode NC-XXX code (e.g. "NC-503"); may be null
     * @return the failure result
     */
    public static CommandResult failure(CommandIntent intent, String message, String errorCode) {
        return new CommandResult(false, intent, message, errorCode);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isFailure() {
        return !success;
    }

    public String getMessage() {
        return message;
    }

    public CommandIntent getIntent() {
        return intent;
    }

    /**
     * @return the NC-XXX error code, or null if none
     */
    public String getErrorCode() {
        return errorCode;
    }

    @Override
    public String toString() {
        return "CommandResult{"
                + "success=" + success
                + ", intent=" + intent
                + ", message='" + message + '\''
                + ", errorCode=" + errorCode
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CommandResult that)) {
            return false;
        }
        return success == that.success
                && intent == that.intent
                && Objects.equals(message, that.message)
                && Objects.equals(errorCode, that.errorCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, message, intent, errorCode);
    }
}
