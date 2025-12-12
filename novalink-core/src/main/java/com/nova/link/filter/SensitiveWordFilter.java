package com.nova.link.filter;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Sensitive word filter that supports:
 * - Built-in word list (500+ words)
 * - Custom words
 * - Regex patterns
 * 
 * Implements Requirements 12.1, 12.2, 12.3, 12.4
 */
@Slf4j
public class SensitiveWordFilter {
    
    private static final String REPLACEMENT = "***";
    private static final String BUILTIN_WORDLIST_PATH = "/sensitive_words.txt";
    
    // Simple word matching (case-insensitive)
    private final Set<String> sensitiveWords = ConcurrentHashMap.newKeySet();
    
    // Regex patterns for complex matching
    private final List<Pattern> regexPatterns = Collections.synchronizedList(new ArrayList<>());
    
    // Compiled patterns for word matching (built from sensitiveWords)
    private volatile Pattern wordPattern = null;
    
    public SensitiveWordFilter() {
        loadBuiltinWordList();
    }
    
    /**
     * Loads the built-in sensitive word list from resources.
     * If the resource file doesn't exist, initializes with a default set.
     */
    private void loadBuiltinWordList() {
        try (InputStream is = getClass().getResourceAsStream(BUILTIN_WORDLIST_PATH)) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.startsWith("#")) {
                            sensitiveWords.add(line.toLowerCase());
                        }
                    }
                }
                log.info("Loaded {} built-in sensitive words", sensitiveWords.size());
            } else {
                loadDefaultWordList();
            }
        } catch (IOException e) {
            log.warn("Failed to load built-in word list, using defaults: {}", e.getMessage());
            loadDefaultWordList();
        }
        rebuildWordPattern();
    }

    /**
     * Loads a default set of sensitive words when the resource file is not available.
     */
    private void loadDefaultWordList() {
        // Default sensitive words - a minimal set for testing
        // In production, this would be loaded from a comprehensive file
        String[] defaults = {
            "spam", "scam", "hack", "cheat", "exploit",
            "abuse", "toxic", "racist", "sexist", "hate",
            "phishing", "malware", "virus", "trojan", "keylogger"
        };
        for (String word : defaults) {
            sensitiveWords.add(word.toLowerCase());
        }
        log.info("Loaded {} default sensitive words", sensitiveWords.size());
    }
    
    /**
     * Rebuilds the compiled word pattern from the current word set.
     * Uses word boundaries for accurate matching.
     */
    private void rebuildWordPattern() {
        if (sensitiveWords.isEmpty()) {
            wordPattern = null;
            return;
        }
        
        StringBuilder patternBuilder = new StringBuilder();
        patternBuilder.append("(?i)\\b(");
        
        Iterator<String> it = sensitiveWords.iterator();
        while (it.hasNext()) {
            patternBuilder.append(Pattern.quote(it.next()));
            if (it.hasNext()) {
                patternBuilder.append("|");
            }
        }
        patternBuilder.append(")\\b");
        
        try {
            wordPattern = Pattern.compile(patternBuilder.toString());
        } catch (PatternSyntaxException e) {
            log.error("Failed to compile word pattern: {}", e.getMessage());
            wordPattern = null;
        }
    }
    
    /**
     * Adds a custom sensitive word to the filter.
     * 
     * @param word the word to add
     */
    public void addWord(String word) {
        if (word != null && !word.trim().isEmpty()) {
            sensitiveWords.add(word.trim().toLowerCase());
            rebuildWordPattern();
            log.debug("Added sensitive word: {}", word);
        }
    }
    
    /**
     * Adds multiple custom sensitive words to the filter.
     * 
     * @param words the words to add
     */
    public void addWords(Collection<String> words) {
        if (words != null) {
            for (String word : words) {
                if (word != null && !word.trim().isEmpty()) {
                    sensitiveWords.add(word.trim().toLowerCase());
                }
            }
            rebuildWordPattern();
            log.debug("Added {} sensitive words", words.size());
        }
    }
    
    /**
     * Removes a sensitive word from the filter.
     * 
     * @param word the word to remove
     * @return true if the word was removed
     */
    public boolean removeWord(String word) {
        if (word != null && sensitiveWords.remove(word.trim().toLowerCase())) {
            rebuildWordPattern();
            log.debug("Removed sensitive word: {}", word);
            return true;
        }
        return false;
    }
    
    /**
     * Adds a regex pattern for complex matching.
     * 
     * @param regex the regex pattern string
     * @return true if the pattern was added successfully
     */
    public boolean addRegexPattern(String regex) {
        if (regex == null || regex.trim().isEmpty()) {
            return false;
        }
        
        try {
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            regexPatterns.add(pattern);
            log.debug("Added regex pattern: {}", regex);
            return true;
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex pattern '{}': {}", regex, e.getMessage());
            return false;
        }
    }
    
    /**
     * Removes a regex pattern from the filter.
     * 
     * @param regex the regex pattern string to remove
     * @return true if the pattern was removed
     */
    public boolean removeRegexPattern(String regex) {
        return regexPatterns.removeIf(p -> p.pattern().equals(regex));
    }

    /**
     * Filters a message, replacing sensitive words with ***.
     * 
     * @param message the message to filter
     * @return the filter result containing the filtered message
     */
    public FilterResult filter(String message) {
        if (message == null || message.isEmpty()) {
            return FilterResult.clean(message == null ? "" : message);
        }
        
        String result = message;
        int totalMatches = 0;
        
        // Apply word pattern matching
        if (wordPattern != null) {
            Matcher matcher = wordPattern.matcher(result);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                matcher.appendReplacement(sb, REPLACEMENT);
                totalMatches++;
            }
            matcher.appendTail(sb);
            result = sb.toString();
        }
        
        // Apply regex patterns
        for (Pattern pattern : regexPatterns) {
            Matcher matcher = pattern.matcher(result);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                matcher.appendReplacement(sb, REPLACEMENT);
                totalMatches++;
            }
            matcher.appendTail(sb);
            result = sb.toString();
        }
        
        if (totalMatches > 0) {
            return FilterResult.filtered(message, result, totalMatches);
        }
        return FilterResult.clean(message);
    }
    
    /**
     * Checks if a message contains any sensitive words without filtering.
     * 
     * @param message the message to check
     * @return true if the message contains sensitive content
     */
    public boolean containsSensitiveContent(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        
        // Check word pattern
        if (wordPattern != null && wordPattern.matcher(message).find()) {
            return true;
        }
        
        // Check regex patterns
        for (Pattern pattern : regexPatterns) {
            if (pattern.matcher(message).find()) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Gets the current count of sensitive words in the filter.
     * 
     * @return the number of sensitive words
     */
    public int getWordCount() {
        return sensitiveWords.size();
    }
    
    /**
     * Gets the current count of regex patterns in the filter.
     * 
     * @return the number of regex patterns
     */
    public int getRegexPatternCount() {
        return regexPatterns.size();
    }
    
    /**
     * Gets an unmodifiable view of the sensitive words.
     * 
     * @return the set of sensitive words
     */
    public Set<String> getWords() {
        return Collections.unmodifiableSet(sensitiveWords);
    }
    
    /**
     * Clears all custom words and patterns, keeping only built-in words.
     */
    public void reset() {
        sensitiveWords.clear();
        regexPatterns.clear();
        loadBuiltinWordList();
    }
    
    /**
     * Clears all words and patterns including built-in ones.
     */
    public void clearAll() {
        sensitiveWords.clear();
        regexPatterns.clear();
        wordPattern = null;
    }
}
