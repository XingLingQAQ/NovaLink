package com.nova.chat.client.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DurationFormatter")
class DurationFormatterTest {

    @Test
    @DisplayName("formats seconds under a minute with 秒")
    void formatsSeconds() {
        assertThat(DurationFormatter.formatSeconds(1L)).isEqualTo("1秒");
        assertThat(DurationFormatter.formatSeconds(59L)).isEqualTo("59秒");
    }

    @Test
    @DisplayName("formats minutes between 60 and 3599 seconds")
    void formatsMinutes() {
        assertThat(DurationFormatter.formatSeconds(60L)).isEqualTo("1分钟");
        assertThat(DurationFormatter.formatSeconds(120L)).isEqualTo("2分钟");
        assertThat(DurationFormatter.formatSeconds(3599L)).isEqualTo("59分钟");
    }

    @Test
    @DisplayName("formats hours between 3600 and 86399 seconds")
    void formatsHours() {
        assertThat(DurationFormatter.formatSeconds(3600L)).isEqualTo("1小时");
        assertThat(DurationFormatter.formatSeconds(7200L)).isEqualTo("2小时");
        assertThat(DurationFormatter.formatSeconds(86399L)).isEqualTo("23小时");
    }

    @Test
    @DisplayName("formats days at and above 86400 seconds")
    void formatsDays() {
        assertThat(DurationFormatter.formatSeconds(86400L)).isEqualTo("1天");
        assertThat(DurationFormatter.formatSeconds(172800L)).isEqualTo("2天");
    }

    @Test
    @DisplayName("parses a seconds string")
    void parsesSecondsString() {
        assertThat(DurationFormatter.formatSeconds("300")).isEqualTo("5分钟");
    }

    @Test
    @DisplayName("returns UNKNOWN for null, blank, or non-numeric input")
    void returnsUnknownForBadInput() {
        assertThat(DurationFormatter.formatSeconds((String) null)).isEqualTo(DurationFormatter.unknown());
        assertThat(DurationFormatter.formatSeconds("")).isEqualTo(DurationFormatter.unknown());
        assertThat(DurationFormatter.formatSeconds("abc")).isEqualTo(DurationFormatter.unknown());
    }

    @Test
    @DisplayName("returns UNKNOWN for non-positive seconds")
    void returnsUnknownForNonPositive() {
        assertThat(DurationFormatter.formatSeconds(0L)).isEqualTo(DurationFormatter.unknown());
        assertThat(DurationFormatter.formatSeconds(-5L)).isEqualTo(DurationFormatter.unknown());
    }
}
