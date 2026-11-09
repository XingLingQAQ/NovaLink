package com.nova.link.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SensitiveWordFilterReloadTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reloadDoesNotInterruptAMessageUsingThePreviousRules(boolean detectionOnly) throws Exception {
        SensitiveWordFilter filter = new SensitiveWordFilter();
        filter.clearAll();
        CountDownLatch matching = new CountDownLatch(1);
        CountDownLatch finishMatching = new CountDownLatch(1);
        Pattern gate = mock(Pattern.class);
        when(gate.matcher(anyString())).thenAnswer(invocation -> {
            matching.countDown();
            if (!finishMatching.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Matching gate timed out");
            }
            return Pattern.compile("never-matches").matcher(invocation.<String>getArgument(0));
        });
        filter.setCustomPatterns(List.of("old"));
        var field = SensitiveWordFilter.class.getDeclaredField("regexPatterns");
        field.setAccessible(true);
        field.set(filter, new ArrayList<>(List.of(gate, Pattern.compile("old"))));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> detectionOnly
                    ? filter.containsSensitiveContent("old") : filter.filter("old").isFiltered());
            assertThat(matching.await(5, TimeUnit.SECONDS)).isTrue();

            filter.setCustomPatterns(List.of("new"));
            finishMatching.countDown();

            assertThat(pending.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(filter.containsSensitiveContent("old")).isFalse();
            assertThat(filter.filter("new").getFilteredMessage()).isEqualTo("***");
            assertThat(filter.getCustomPatterns()).containsExactly("new");
            assertThat(filter.getRegexPatternCount()).isEqualTo(1);
        } finally {
            finishMatching.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void incrementalUpdatesAndResetUseTheCurrentRules() {
        SensitiveWordFilter filter = new SensitiveWordFilter();
        filter.clearAll();
        filter.setCustomPatterns(List.of("old"));
        assertThat(filter.addRegexPattern("new")).isTrue();
        assertThat(filter.removeRegexPattern("old")).isTrue();
        assertThat(filter.filter("old new").getFilteredMessage()).isEqualTo("old ***");

        filter.reset();

        assertThat(filter.getRegexPatternCount()).isZero();
        assertThat(filter.filter("spam").isFiltered()).isTrue();
        filter.clearAll();
        assertThat(filter.containsSensitiveContent("spam")).isFalse();
    }

    @Test
    void invalidReplacementKeepsTheCommittedPatterns() {
        SensitiveWordFilter filter = new SensitiveWordFilter();
        filter.clearAll();
        filter.setCustomPatterns(List.of("old"));

        assertThatThrownBy(() -> filter.setCustomPatterns(List.of("new", "[")))
                .isInstanceOf(PatternSyntaxException.class);

        assertThat(filter.getCustomPatterns()).containsExactly("old");
        assertThat(filter.filter("old new").getFilteredMessage()).isEqualTo("*** new");
    }
}
