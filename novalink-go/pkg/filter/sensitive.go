// Package filter provides message filtering functionality.
package filter

import (
	"bufio"
	"os"
	"strings"
	"sync"
)

// SensitiveWordFilter filters sensitive words from messages.
type SensitiveWordFilter struct {
	words   map[string]bool
	enabled bool
	mutex   sync.RWMutex
}

// NewSensitiveWordFilter creates a new SensitiveWordFilter.
func NewSensitiveWordFilter() *SensitiveWordFilter {
	return &SensitiveWordFilter{
		words:   make(map[string]bool),
		enabled: true,
	}
}

// LoadFromFile loads sensitive words from a file (one word per line).
func (f *SensitiveWordFilter) LoadFromFile(path string) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()

	f.mutex.Lock()
	defer f.mutex.Unlock()

	f.words = make(map[string]bool)
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		word := strings.TrimSpace(scanner.Text())
		if word != "" && !strings.HasPrefix(word, "#") {
			f.words[strings.ToLower(word)] = true
		}
	}

	return scanner.Err()
}

// AddWord adds a word to the filter.
func (f *SensitiveWordFilter) AddWord(word string) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	f.words[strings.ToLower(word)] = true
}

// RemoveWord removes a word from the filter.
func (f *SensitiveWordFilter) RemoveWord(word string) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	delete(f.words, strings.ToLower(word))
}

// SetEnabled enables or disables the filter.
func (f *SensitiveWordFilter) SetEnabled(enabled bool) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	f.enabled = enabled
}

// IsEnabled returns whether the filter is enabled.
func (f *SensitiveWordFilter) IsEnabled() bool {
	f.mutex.RLock()
	defer f.mutex.RUnlock()
	return f.enabled
}

// ContainsSensitiveWord checks if a message contains any sensitive words.
func (f *SensitiveWordFilter) ContainsSensitiveWord(message string) bool {
	if !f.IsEnabled() {
		return false
	}

	f.mutex.RLock()
	defer f.mutex.RUnlock()

	lowerMessage := strings.ToLower(message)
	for word := range f.words {
		if strings.Contains(lowerMessage, word) {
			return true
		}
	}

	return false
}

// Filter replaces sensitive words in a message with asterisks.
func (f *SensitiveWordFilter) Filter(message string) string {
	if !f.IsEnabled() {
		return message
	}

	f.mutex.RLock()
	defer f.mutex.RUnlock()

	result := message
	lowerMessage := strings.ToLower(message)

	for word := range f.words {
		if idx := strings.Index(lowerMessage, word); idx != -1 {
			replacement := strings.Repeat("*", len(word))
			result = result[:idx] + replacement + result[idx+len(word):]
			lowerMessage = lowerMessage[:idx] + replacement + lowerMessage[idx+len(word):]
		}
	}

	return result
}

// GetWordCount returns the number of words in the filter.
func (f *SensitiveWordFilter) GetWordCount() int {
	f.mutex.RLock()
	defer f.mutex.RUnlock()
	return len(f.words)
}
