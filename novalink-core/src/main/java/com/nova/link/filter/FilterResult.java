package com.nova.link.filter;

import lombok.Builder;
import lombok.Getter;

/**
 * Result of a message filtering operation.
 */
@Getter
@Builder
public class FilterResult {
    
    private final boolean filtered;
    private final String originalMessage;
    private final String filteredMessage;
    private final int matchCount;
    
    /**
     * Creates a result indicating no filtering was needed.
     */
    public static FilterResult clean(String message) {
        return FilterResult.builder()
                .filtered(false)
                .originalMessage(message)
                .filteredMessage(message)
                .matchCount(0)
                .build();
    }
    
    /**
     * Creates a result indicating filtering was applied.
     */
    public static FilterResult filtered(String original, String filtered, int matchCount) {
        return FilterResult.builder()
                .filtered(true)
                .originalMessage(original)
                .filteredMessage(filtered)
                .matchCount(matchCount)
                .build();
    }
}
