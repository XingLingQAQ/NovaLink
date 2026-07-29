package com.nova.chat.client.command;

import java.util.Objects;

/**
 * Outcome of a {@link ChannelCommandService} invocation.
 *
 * <p>Carries success/failure, a human-readable message (platforms may translate
 * or ignore), and the originating {@link CommandIntent}.
 */
public final class CommandResult {

    private final boolean success;
    private final String message;
    private final CommandIntent intent;

    private CommandResult(boolean success, CommandIntent intent, String message) {
        this.success = success;
        this.intent = Objects.requireNonNull(intent, "intent");
        this.message = message != null ? message : "";
    }

    public static CommandResult success(CommandIntent intent, String message) {
        return new CommandResult(true, intent, message);
    }

    public static CommandResult failure(CommandIntent intent, String message) {
        return new CommandResult(false, intent, message);
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

    @Override
    public String toString() {
        return "CommandResult{"
                + "success=" + success
                + ", intent=" + intent
                + ", message='" + message + '\''
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
                && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, message, intent);
    }
}
