// Package auth provides authentication and authorization functionality.
package auth

import (
	"sync"
	"time"
)

// IpBanManager handles IP-based banning after consecutive authentication failures.
// After a configurable number of consecutive failures from the same IP,
// the IP is temporarily banned for a configurable duration.
type IpBanManager struct {
	failedAttempts map[string]*IpFailureRecord
	bannedIPs      map[string]time.Time
	maxFailures    int
	banDuration    time.Duration
	mutex          sync.RWMutex
}

// IpFailureRecord tracks failed authentication attempts from an IP.
type IpFailureRecord struct {
	Count       int
	FirstAttempt time.Time
	LastAttempt  time.Time
}

// NewIpBanManager creates a new IpBanManager.
// maxFailures specifies the number of consecutive failures before an IP is banned.
// banDuration specifies how long an IP remains banned.
func NewIpBanManager(maxFailures int, banDuration time.Duration) *IpBanManager {
	return &IpBanManager{
		failedAttempts: make(map[string]*IpFailureRecord),
		bannedIPs:      make(map[string]time.Time),
		maxFailures:    maxFailures,
		banDuration:    banDuration,
	}
}

// RecordFailure records a failed authentication attempt from an IP.
// Returns true if the IP is now banned after this failure.
func (m *IpBanManager) RecordFailure(ip string) bool {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	// Check if already banned
	if m.isIPBannedLocked(ip) {
		return true
	}

	now := time.Now()
	record, exists := m.failedAttempts[ip]
	if !exists {
		record = &IpFailureRecord{
			FirstAttempt: now,
		}
		m.failedAttempts[ip] = record
	}

	record.Count++
	record.LastAttempt = now

	// Ban IP if max failures reached
	if record.Count >= m.maxFailures {
		m.bannedIPs[ip] = now.Add(m.banDuration)
		delete(m.failedAttempts, ip)
		return true
	}

	return false
}

// RecordSuccess clears the failure count for an IP on successful authentication.
func (m *IpBanManager) RecordSuccess(ip string) {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	delete(m.failedAttempts, ip)
}

// IsIPBanned checks if an IP is currently banned.
// Expired bans are automatically cleaned up.
func (m *IpBanManager) IsIPBanned(ip string) bool {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	return m.isIPBannedLocked(ip)
}

// isIPBannedLocked checks if an IP is banned (must hold lock).
func (m *IpBanManager) isIPBannedLocked(ip string) bool {
	banExpiry, banned := m.bannedIPs[ip]
	if !banned {
		return false
	}

	if time.Now().After(banExpiry) {
		delete(m.bannedIPs, ip)
		return false
	}

	return true
}

// GetBanExpiry returns the ban expiry time for an IP.
// Returns zero time if the IP is not banned.
func (m *IpBanManager) GetBanExpiry(ip string) time.Time {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	if banExpiry, banned := m.bannedIPs[ip]; banned {
		if time.Now().Before(banExpiry) {
			return banExpiry
		}
	}
	return time.Time{}
}

// GetFailureCount returns the current failure count for an IP.
func (m *IpBanManager) GetFailureCount(ip string) int {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	if record, exists := m.failedAttempts[ip]; exists {
		return record.Count
	}
	return 0
}

// UnbanIP manually removes an IP from the ban list.
func (m *IpBanManager) UnbanIP(ip string) {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	delete(m.bannedIPs, ip)
}

// ClearFailures clears the failure count for an IP without unbanning.
func (m *IpBanManager) ClearFailures(ip string) {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	delete(m.failedAttempts, ip)
}

// GetBannedIPs returns a list of currently banned IPs.
func (m *IpBanManager) GetBannedIPs() []string {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	now := time.Now()
	var banned []string
	for ip, expiry := range m.bannedIPs {
		if now.Before(expiry) {
			banned = append(banned, ip)
		}
	}
	return banned
}

// GetBannedIPCount returns the number of currently banned IPs.
func (m *IpBanManager) GetBannedIPCount() int {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	now := time.Now()
	count := 0
	for _, expiry := range m.bannedIPs {
		if now.Before(expiry) {
			count++
		}
	}
	return count
}

// CleanupExpiredBans removes expired bans from the internal map.
// This is called automatically during IsIPBanned checks, but can be
// called manually for maintenance.
func (m *IpBanManager) CleanupExpiredBans() int {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	now := time.Now()
	removed := 0
	for ip, expiry := range m.bannedIPs {
		if now.After(expiry) {
			delete(m.bannedIPs, ip)
			removed++
		}
	}
	return removed
}

// GetMaxFailures returns the configured maximum failures before ban.
func (m *IpBanManager) GetMaxFailures() int {
	return m.maxFailures
}

// GetBanDuration returns the configured ban duration.
func (m *IpBanManager) GetBanDuration() time.Duration {
	return m.banDuration
}
