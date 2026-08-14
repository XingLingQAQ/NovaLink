package com.nova.link.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Custom sensitive-word filter configuration (the {@code filter} section of
 * {@code novalink.yml}).
 *
 * <p>Holds the panel-managed custom word list and regex pattern list that are
 * loaded into {@link com.nova.link.filter.SensitiveWordFilter} at startup and
 * replaced wholesale by PUT /api/filter. The on/off switch lives separately in
 * {@code features.filter-enabled} (same source of truth as the Settings page).
 */
public class FilterConfig {

    /** Custom sensitive words (in addition to the built-in list). */
    private List<String> words = new ArrayList<>();

    /** Custom regex patterns. */
    private List<String> patterns = new ArrayList<>();

    public FilterConfig() {}

    public List<String> getWords() {
        return words;
    }

    public void setWords(List<String> words) {
        this.words = words != null ? words : new ArrayList<>();
    }

    public List<String> getPatterns() {
        return patterns;
    }

    public void setPatterns(List<String> patterns) {
        this.patterns = patterns != null ? patterns : new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FilterConfig that = (FilterConfig) o;
        return Objects.equals(words, that.words) &&
               Objects.equals(patterns, that.patterns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(words, patterns);
    }
}
