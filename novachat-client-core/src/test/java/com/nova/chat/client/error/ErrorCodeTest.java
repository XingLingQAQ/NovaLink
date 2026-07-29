package com.nova.chat.client.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErrorCode")
class ErrorCodeTest {

    @Nested
    @DisplayName("code string")
    class CodeString {
        @Test
        void unauthorizedIs401() {
            assertThat(ErrorCode.UNAUTHORIZED.getCode()).isEqualTo("NC-401");
        }

        @Test
        void wrongPasswordIs434() {
            assertThat(ErrorCode.WRONG_PASSWORD.getCode()).isEqualTo("NC-434");
        }

        @Test
        void serviceUnavailableIs503() {
            assertThat(ErrorCode.SERVICE_UNAVAILABLE.getCode()).isEqualTo("NC-503");
        }

        @Test
        void notInChannelIs433() {
            assertThat(ErrorCode.NOT_IN_CHANNEL.getCode()).isEqualTo("NC-433");
        }
    }

    @Nested
    @DisplayName("message and suggestion")
    class MessageAndSuggestion {
        @Test
        void messageIsNonBlank() {
            for (ErrorCode code : ErrorCode.values()) {
                assertThat(code.getMessage()).isNotBlank();
            }
        }

        @Test
        void suggestionIsNonBlank() {
            for (ErrorCode code : ErrorCode.values()) {
                assertThat(code.getSuggestion()).isNotBlank();
            }
        }
    }

    @Nested
    @DisplayName("isClientError / isServerError")
    class Category {
        @Test
        void fourHundredSeriesIsClientError() {
            assertThat(ErrorCode.UNAUTHORIZED.isClientError()).isTrue();
            assertThat(ErrorCode.WRONG_PASSWORD.isClientError()).isTrue();
            assertThat(ErrorCode.UNAUTHORIZED.isServerError()).isFalse();
        }

        @Test
        void fiveHundredSeriesIsServerError() {
            assertThat(ErrorCode.SERVICE_UNAVAILABLE.isServerError()).isTrue();
            assertThat(ErrorCode.SERVICE_UNAVAILABLE.isClientError()).isFalse();
        }
    }

    @Nested
    @DisplayName("fromCode")
    class FromCode {
        @Test
        void knownCodeResolves() {
            assertThat(ErrorCode.fromCode("NC-434")).isEqualTo(ErrorCode.WRONG_PASSWORD);
        }

        @Test
        void nullResolvesToInternalError() {
            assertThat(ErrorCode.fromCode(null)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        }

        @Test
        void unknownCodeResolvesToInternalError() {
            assertThat(ErrorCode.fromCode("NC-999")).isEqualTo(ErrorCode.INTERNAL_ERROR);
        }

        @Test
        void blankCodeResolvesToInternalError() {
            assertThat(ErrorCode.fromCode("")).isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Nested
    @DisplayName("fromNumericCode")
    class FromNumericCode {
        @Test
        void numeric401Resolves() {
            assertThat(ErrorCode.fromNumericCode(401)).isEqualTo(ErrorCode.UNAUTHORIZED);
        }

        @Test
        void numeric503Resolves() {
            assertThat(ErrorCode.fromNumericCode(503)).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        }

        @Test
        void unknownNumericResolvesToInternalError() {
            assertThat(ErrorCode.fromNumericCode(999)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Test
    @DisplayName("toString contains code and message")
    void toStringContainsCodeAndMessage() {
        String s = ErrorCode.WRONG_PASSWORD.toString();
        assertThat(s).contains("NC-434").contains("密码错误");
    }

    @Test
    @DisplayName("every code is unique")
    void everyCodeIsUnique() {
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(codes.add(code.getCode()))
                    .as("duplicate code: " + code.getCode())
                    .isTrue();
        }
    }
}
