package com.nova.chat.client.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommandResult errorCode")
class CommandResultErrorCodeTest {

    @Test
    @DisplayName("success result has no errorCode")
    void successHasNoErrorCode() {
        CommandResult r = CommandResult.success(CommandIntent.JOIN, "ok");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getErrorCode()).isNull();
    }

    @Test
    @DisplayName("plain failure has no errorCode (backward compat)")
    void plainFailureHasNoErrorCode() {
        CommandResult r = CommandResult.failure(CommandIntent.JOIN, "fail");
        assertThat(r.isFailure()).isTrue();
        assertThat(r.getErrorCode()).isNull();
    }

    @Test
    @DisplayName("failure with errorCode carries the code")
    void failureWithCode() {
        CommandResult r = CommandResult.failure(CommandIntent.JOIN, "fail", "NC-503");
        assertThat(r.isFailure()).isTrue();
        assertThat(r.getErrorCode()).isEqualTo("NC-503");
        assertThat(r.getMessage()).isEqualTo("fail");
    }

    @Test
    @DisplayName("errorCode is reflected in toString")
    void toStringIncludesErrorCode() {
        CommandResult r = CommandResult.failure(CommandIntent.LEAVE, "no channel", "NC-433");
        assertThat(r.toString()).contains("NC-433");
    }

    @Test
    @DisplayName("equality includes errorCode")
    void equalityIncludesErrorCode() {
        CommandResult a = CommandResult.failure(CommandIntent.JOIN, "x", "NC-503");
        CommandResult b = CommandResult.failure(CommandIntent.JOIN, "x", "NC-503");
        CommandResult c = CommandResult.failure(CommandIntent.JOIN, "x", "NC-500");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
