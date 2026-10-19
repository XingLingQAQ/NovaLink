package com.nova.chat.proxy.lite;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sensitive-word filter for {@code lite} mode.
 *
 * <p>The backend owns the canonical implementation
 * ({@code com.nova.link.filter.SensitiveWordFilter}), but {@code proxy-common}
 * cannot depend on {@code :StarLink:core}: that module drags in Netty, JWT and
 * the whole server stack a proxy must never load. This is therefore a small,
 * self-contained copy of the matching rules — deliberately the *rules*, not the
 * word list or the panel-managed state, which stay backend concerns.
 *
 * <p>Matching mirrors the backend exactly, because a network running lite on
 * one proxy and relay on another must not censor differently:
 * <ul>
 *   <li>Words match case-insensitively on word boundaries
 *       ({@code (?i)\b(word|word)\b}), so "ass" does not fire inside "class".</li>
 *   <li>Regex patterns compile case-insensitively; an invalid pattern is
 *       skipped rather than poisoning the rest.</li>
 *   <li>Every match is replaced with {@code ***} — a fixed replacement, not a
 *       same-length mask, matching the backend.</li>
 * </ul>
 *
 * <p>Instances are safe to read from the chat hot path; mutating methods
 * rebuild the compiled pattern, so they are not meant for per-message use.
 */
public final class LiteWordFilter {

    /** Replacement token; mirrors the backend's {@code REPLACEMENT}. */
    public static final String REPLACEMENT = "***";

    private final Set<String> words = ConcurrentHashMap.newKeySet();
    private final Set<String> regexSources = ConcurrentHashMap.newKeySet();

    private volatile List<Pattern> regexPatterns = List.of();
    private volatile Pattern wordPattern;

    /**
     * Builds a filter from the configured words and patterns.
     *
     * @param words literal words (null/blank entries ignored)
     * @param patterns regex sources (null/blank entries ignored)
     * @return the filter; an empty one when nothing is configured
     */
    public static LiteWordFilter of(Collection<String> words, Collection<String> patterns) {
        LiteWordFilter filter = new LiteWordFilter();
        filter.setWords(words);
        filter.setPatterns(patterns);
        return filter;
    }

    /**
     * @param configured the literal words to match
     */
    public void setWords(Collection<String> configured) {
        words.clear();
        if (configured != null) {
            for (String word : configured) {
                if (word != null && !word.isBlank()) {
                    words.add(word.trim());
                }
            }
        }
        rebuildWordPattern();
    }

    /**
     * @param configured the regex sources to match; invalid ones are dropped
     */
    public void setPatterns(Collection<String> configured) {
        regexSources.clear();
        List<Pattern> compiled = new ArrayList<>();
        if (configured != null) {
            for (String source : configured) {
                if (source == null || source.isBlank()) {
                    continue;
                }
                try {
                    compiled.add(Pattern.compile(source, Pattern.CASE_INSENSITIVE));
                    regexSources.add(source);
                } catch (RuntimeException e) {
                    // Skip the bad pattern, keep the rest: one operator typo must
                    // not silently disable every other rule.
                }
            }
        }
        regexPatterns = List.copyOf(compiled);
    }

    /**
     * @return whether any rule is configured
     */
    public boolean isEmpty() {
        return words.isEmpty() && regexPatterns.isEmpty();
    }

    /**
     * @return the configured words (read-only)
     */
    Set<String> getWords() {
        return Collections.unmodifiableSet(words);
    }

    /**
     * @return the configured regex sources (read-only)
     */
    Set<String> getPatterns() {
        return Collections.unmodifiableSet(regexSources);
    }

    /**
     * Applies every rule to a message.
     *
     * @param message the message to clean (null is treated as empty)
     * @return the filtered text, or the input unchanged when nothing matched
     */
    public String filter(String message) {
        if (message == null || message.isEmpty() || isEmpty()) {
            return message != null ? message : "";
        }
        String result = message;
        Pattern wordsPattern = wordPattern;
        if (wordsPattern != null) {
            result = replaceAll(wordsPattern, result);
        }
        for (Pattern pattern : regexPatterns) {
            result = replaceAll(pattern, result);
        }
        return result;
    }

    /**
     * @param message the message to inspect
     * @return the number of matches the configured rules would replace
     */
    public int countMatches(String message) {
        if (message == null || message.isEmpty() || isEmpty()) {
            return 0;
        }
        int total = 0;
        Pattern wordsPattern = wordPattern;
        if (wordsPattern != null) {
            total += count(wordsPattern, message);
        }
        for (Pattern pattern : regexPatterns) {
            total += count(pattern, message);
        }
        return total;
    }

    private void rebuildWordPattern() {
        if (words.isEmpty()) {
            wordPattern = null;
            return;
        }
        StringBuilder builder = new StringBuilder(words.size() * 8).append("(?i)\\b(");
        boolean first = true;
        for (String word : words) {
            if (!first) {
                builder.append('|');
            }
            builder.append(Pattern.quote(word));
            first = false;
        }
        builder.append(")\\b");
        try {
            wordPattern = Pattern.compile(builder.toString());
        } catch (RuntimeException e) {
            wordPattern = null;
        }
    }

    private static String replaceAll(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        do {
            out.append(text, last, matcher.start()).append(REPLACEMENT);
            last = matcher.end();
        } while (matcher.find());
        out.append(text, last, text.length());
        return out.toString();
    }

    private static int count(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int matches = 0;
        while (matcher.find()) {
            matches++;
        }
        return matches;
    }

    @Override
    public String toString() {
        return "LiteWordFilter{words=" + words.size()
                + ", patterns=" + regexPatterns.size()
                + "}";
    }
}
