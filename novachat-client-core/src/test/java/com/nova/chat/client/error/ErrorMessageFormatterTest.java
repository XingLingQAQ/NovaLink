package com.nova.chat.client.error;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ErrorMessageFormatter}, covering locale-aware error
 * message formatting and default-locale save/restore around each test.
 */
@DisplayName("ErrorMessageFormatter")
class ErrorMessageFormatterTest {

    private java.util.Locale savedDefault;

    @BeforeEach
    void saveDefault() {
        savedDefault = I18n.getDefaultLocale();
        // Default to zh_CN so the existing Chinese assertions pass.
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
    }

    @AfterEach
    void restoreDefault() {
        I18n.setDefaultLocale(savedDefault);
    }

    @Nested
    @DisplayName("format(ErrorCode)")
    class FormatErrorCode {
        @Test
        void includesBracketedCode() {
            String out = ErrorMessageFormatter.format(ErrorCode.WRONG_PASSWORD);
            assertThat(out).contains("[NC-434]");
        }

        @Test
        void includesMessageOnFirstLine() {
            String out = ErrorMessageFormatter.format(ErrorCode.WRONG_PASSWORD);
            assertThat(out.split("\n")[0]).contains("密码错误");
        }

        @Test
        void includesSuggestionPrefixedOnSecondLine() {
            String out = ErrorMessageFormatter.format(ErrorCode.WRONG_PASSWORD);
            String[] lines = out.split("\n");
            assertThat(lines).hasSize(2);
            assertThat(lines[1]).startsWith("提示: ").contains("请检查频道密码是否正确");
        }

        @Test
        void serviceUnavailableIncludesRetryHint() {
            String out = ErrorMessageFormatter.format(ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(out).contains("[NC-503]").contains("未连接到后端服务器");
        }
    }

    @Nested
    @DisplayName("format(String code)")
    class FormatStringCode {
        @Test
        void knownCodeResolves() {
            String out = ErrorMessageFormatter.format("NC-401");
            assertThat(out).contains("[NC-401]").contains("认证失败");
        }

        @Test
        void nullCodeFallsBackTo500() {
            String out = ErrorMessageFormatter.format((String) null);
            assertThat(out).contains("[NC-500]");
        }

        @Test
        void unknownCodeFallsBackTo500() {
            String out = ErrorMessageFormatter.format("NC-999");
            assertThat(out).contains("[NC-500]");
        }
    }

    @Nested
    @DisplayName("format(ErrorCode, messageOverride)")
    class FormatWithOverride {
        @Test
        void overrideMessageReplacesFirstLine() {
            String out = ErrorMessageFormatter.format(ErrorCode.NOT_FOUND, "频道 'pvp' 不存在");
            assertThat(out.split("\n")[0]).contains("[NC-404]").contains("频道 'pvp' 不存在");
        }

        @Test
        void nullOverrideFallsBackToCodeMessage() {
            String out = ErrorMessageFormatter.format(ErrorCode.NOT_FOUND, null);
            assertThat(out.split("\n")[0]).contains("资源不存在");
        }

        @Test
        void blankOverrideFallsBackToCodeMessage() {
            String out = ErrorMessageFormatter.format(ErrorCode.NOT_FOUND, "   ");
            assertThat(out.split("\n")[0]).contains("资源不存在");
        }

        @Test
        void suggestionAlwaysFromCodeNotOverride() {
            String out = ErrorMessageFormatter.format(ErrorCode.NOT_FOUND, "custom msg");
            assertThat(linesAfter(out, 1)).anyMatch(l -> l.contains("请检查频道ID或玩家名称是否正确"));
        }
    }

    private static java.util.List<String> linesAfter(String s, int skip) {
        String[] lines = s.split("\n");
        java.util.List<String> tail = new java.util.ArrayList<>();
        for (int i = skip; i < lines.length; i++) {
            tail.add(lines[i]);
        }
        return tail;
    }

    // ====================== en_US locale sample ======================

    @Nested
    @DisplayName("en_US locale")
    class EnUSLocale {
        @Test
        void enUSFormatRendersEnglish() {
            I18n.setDefaultLocale(LocaleResolver.EN_US);
            String out = ErrorMessageFormatter.format(ErrorCode.WRONG_PASSWORD);
            assertThat(out).contains("[NC-434]");
            assertThat(out.split("\n")[0]).contains("Wrong password");
            assertThat(linesAfter(out, 1)).anyMatch(l -> l.startsWith("Suggestion:"));
        }
    }
}
