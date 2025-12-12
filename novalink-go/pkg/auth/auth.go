// Package auth provides authentication and authorization functionality.
// This package implements SHA-256 password hashing for compatibility with
// the Java NovaLink backend, ensuring cross-platform authentication consistency.
package auth

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strings"
	"sync"
	"time"
)

var (
	ErrInvalidCredentials = errors.New("invalid credentials")
	ErrIPBanned           = errors.New("IP address is banned")
	ErrTooManyFailures    = errors.New("too many authentication failures")
	ErrClientNotFound     = errors.New("client not found")
)

// Manager handles authentication with SHA-256 password hashing.
// It provides the same authentication mechanism as the Java NovaLink backend.
type Manager struct {
	clients      map[string]ClientAuth
	failedLogins map[string]*FailedLogin
	bannedIPs    map[string]time.Time
	maxFailures  int
	banDuration  time.Duration
	mutex        sync.RWMutex
}

// ClientAuth represents authentication info for a client.
type ClientAuth struct {
	ClientID     string
	PasswordHash string
	Permission   PermissionLevel
}

// FailedLogin tracks failed login attempts.
type FailedLogin struct {
	Count       int
	LastAttempt time.Time
}

// NewManager creates a new authentication Manager.
// maxFailures specifies the number of consecutive failures before IP ban.
// banDuration specifies how long an IP remains banned.
func NewManager(maxFailures int, banDuration time.Duration) *Manager {
	return &Manager{
		clients:      make(map[string]ClientAuth),
		failedLogins: make(map[string]*FailedLogin),
		bannedIPs:    make(map[string]time.Time),
		maxFailures:  maxFailures,
		banDuration:  banDuration,
	}
}

// RegisterClient registers a new client for authentication.
// The password is hashed using SHA-256 before storage.
func (m *Manager) RegisterClient(clientID, password string, permission PermissionLevel) {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	m.clients[clientID] = ClientAuth{
		ClientID:     clientID,
		PasswordHash: HashPassword(password),
		Permission:   permission,
	}
}

// RegisterClientWithHash registers a client with a pre-computed password hash.
// This is useful when loading clients from configuration or database.
func (m *Manager) RegisterClientWithHash(clientID, passwordHash string, permission PermissionLevel) {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	m.clients[clientID] = ClientAuth{
		ClientID:     clientID,
		PasswordHash: strings.ToLower(passwordHash), // Normalize to lowercase
		Permission:   permission,
	}
}

// Authenticate verifies client credentials.
// The passwordHash parameter should be the SHA-256 hash of the password.
// Returns nil on success, or an error describing the failure.
func (m *Manager) Authenticate(clientID, passwordHash, ip string) error {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	// Check if IP is banned
	if banExpiry, banned := m.bannedIPs[ip]; banned {
		if time.Now().Before(banExpiry) {
			return ErrIPBanned
		}
		delete(m.bannedIPs, ip)
	}

	// Get client auth info
	auth, exists := m.clients[clientID]
	if !exists {
		m.recordFailure(ip)
		return ErrInvalidCredentials
	}

	// Compare hashes (case-insensitive for hex strings)
	if !strings.EqualFold(auth.PasswordHash, passwordHash) {
		m.recordFailure(ip)
		return ErrInvalidCredentials
	}

	// Clear failed login count on success
	delete(m.failedLogins, ip)
	return nil
}

// AuthenticateWithPassword verifies client credentials using a plain password.
// The password is hashed internally before comparison.
func (m *Manager) AuthenticateWithPassword(clientID, password, ip string) error {
	return m.Authenticate(clientID, HashPassword(password), ip)
}

// recordFailure records a failed login attempt.
func (m *Manager) recordFailure(ip string) {
	failed, exists := m.failedLogins[ip]
	if !exists {
		failed = &FailedLogin{}
		m.failedLogins[ip] = failed
	}

	failed.Count++
	failed.LastAttempt = time.Now()

	// Ban IP if too many failures
	if failed.Count >= m.maxFailures {
		m.bannedIPs[ip] = time.Now().Add(m.banDuration)
		delete(m.failedLogins, ip)
	}
}

// GetFailureCount returns the current failure count for an IP.
func (m *Manager) GetFailureCount(ip string) int {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	if failed, exists := m.failedLogins[ip]; exists {
		return failed.Count
	}
	return 0
}

// IsIPBanned checks if an IP is currently banned.
func (m *Manager) IsIPBanned(ip string) bool {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	banExpiry, banned := m.bannedIPs[ip]
	if !banned {
		return false
	}

	if time.Now().After(banExpiry) {
		return false
	}

	return true
}

// GetBanExpiry returns the ban expiry time for an IP.
// Returns zero time if the IP is not banned.
func (m *Manager) GetBanExpiry(ip string) time.Time {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	if banExpiry, banned := m.bannedIPs[ip]; banned {
		return banExpiry
	}
	return time.Time{}
}

// UnbanIP removes an IP from the ban list.
func (m *Manager) UnbanIP(ip string) {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	delete(m.bannedIPs, ip)
}

// ClearFailures clears the failure count for an IP.
func (m *Manager) ClearFailures(ip string) {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	delete(m.failedLogins, ip)
}

// GetClientPermission returns the permission level for a client.
func (m *Manager) GetClientPermission(clientID string) PermissionLevel {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	auth, exists := m.clients[clientID]
	if !exists {
		return PermissionNone
	}
	return auth.Permission
}

// ClientExists checks if a client is registered.
func (m *Manager) ClientExists(clientID string) bool {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	_, exists := m.clients[clientID]
	return exists
}

// RemoveClient removes a client from the authentication system.
func (m *Manager) RemoveClient(clientID string) {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	delete(m.clients, clientID)
}

// GetClientCount returns the number of registered clients.
func (m *Manager) GetClientCount() int {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	return len(m.clients)
}

// HashPassword creates a SHA-256 hash of the password.
// This produces the same hash as Java's MessageDigest.getInstance("SHA-256").
// The output is a lowercase hexadecimal string.
func HashPassword(password string) string {
	hash := sha256.Sum256([]byte(password))
	return hex.EncodeToString(hash[:])
}

// VerifyHash verifies a password against a hash.
// Comparison is case-insensitive for hex strings.
func VerifyHash(password, hash string) bool {
	return strings.EqualFold(HashPassword(password), hash)
}
