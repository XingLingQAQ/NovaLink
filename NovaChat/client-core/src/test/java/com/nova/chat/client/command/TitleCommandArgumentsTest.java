package com.nova.chat.client.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TitleCommandArgumentsTest {

    @Test
    void preservesLegacySubtitleAndDefaults() {
        assertThat(TitleCommandArguments.parse(new String[]{"global", "Title", "several", "subtitle", "words"}))
                .isEqualTo(new TitleCommandArguments("global", "Title", "several subtitle words", 10, 70, 20));
        assertThat(TitleCommandArguments.parse(new String[]{"local", "Title"}))
                .isEqualTo(new TitleCommandArguments("local", "Title", "", 10, 70, 20));
    }

    @Test
    void removesNamedTimingsWithoutReorderingSubtitleWords() {
        assertThat(TitleCommandArguments.parse(new String[]{"global", "Title", "first", "--stay", "37",
                "second", "--fadeOut", "9", "--fadeIn", "3"}))
                .isEqualTo(new TitleCommandArguments("global", "Title", "first second", 3, 37, 9));
    }

    @Test
    void defaultsOmittedOptionsAndAcceptsTickBounds() {
        assertThat(TitleCommandArguments.parse(new String[]{"local", "Title", "--stay", "43"}))
                .isEqualTo(new TitleCommandArguments("local", "Title", "", 10, 43, 20));
        assertThat(TitleCommandArguments.parse(new String[]{"global", "Title", "--fadeIn", "0",
                "--fadeOut", "2147483647"}))
                .isEqualTo(new TitleCommandArguments("global", "Title", "", 0, 70, Integer.MAX_VALUE));
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void rejectsInvalidTimingsInsteadOfTreatingThemAsSubtitle(String[] args) {
        assertThatIllegalArgumentException().isThrownBy(() -> TitleCommandArguments.parse(args));
    }

    private static Stream<Arguments> invalidArguments() {
        return Stream.of(
                new String[]{},
                new String[]{"global"},
                new String[]{"--stay", "37", "global", "Title"},
                new String[]{"global", "--fadeIn", "3"},
                new String[]{"global", "Title", "subtitle", "--unknown", "3"},
                new String[]{"global", "Title", "--fadeIn=3"},
                new String[]{"global", "Title", "--fadeIn"},
                new String[]{"global", "Title", "--stay", "--fadeOut", "9"},
                new String[]{"global", "Title", "--fadeIn", "-1"},
                new String[]{"global", "Title", "--stay", "2147483648"},
                new String[]{"global", "Title", "--fadeOut", "999999999999999999999"},
                new String[]{"global", "Title", "--stay", "1.5"},
                new String[]{"global", "Title", "--stay", "+1"},
                new String[]{"global", "Title", "--fadeOut", "later"},
                new String[]{"global", "Title", "--fadeIn", "3", "--fadeIn", "3"},
                new String[]{"global", "Title", "--stay", "37", "--stay", "43"},
                new String[]{"global", "Title", "--fadeOut", "9", "--fadeOut", "11"}
        ).map(args -> Arguments.of((Object) args));
    }
}
