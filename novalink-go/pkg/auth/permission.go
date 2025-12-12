// Package auth provides authentication and authorization functionality.
package auth

import (
	"errors"
	"strings"
)

// PermissionLevel represents the four-level permission hierarchy.
// The hierarchy is: NONE < USER < MODERATOR < ADMIN < SUPER_ADMIN
// Higher levels inherit all permissions from lower levels.
type PermissionLevel int

const (
	// PermissionNone represents no permissions (unauthenticated).
	PermissionNone PermissionLevel = 0
	// PermissionUser represents basic user permissions (can chat, join channels).
	PermissionUser PermissionLevel = 1
	// PermissionMod represents moderator permissions (can mute, kick users).
	PermissionMod PermissionLevel = 2
	// PermissionAdmin represents administrator permissions (can manage channels, announcements).
	PermissionAdmin PermissionLevel = 3
	// PermissionSuper represents super administrator permissions (can reload config, manage system).
	PermissionSuper PermissionLevel = 4
)

var (
	// ErrPermissionDenied is returned when a user lacks required permissions.
	ErrPermissionDenied = errors.New("permission denied")
	// ErrInvalidPermissionLevel is returned for unknown permission levels.
	ErrInvalidPermissionLevel = errors.New("invalid permission level")
)

// String returns the string representation of the permission level.
func (p PermissionLevel) String() string {
	switch p {
	case PermissionNone:
		return "NONE"
	case PermissionUser:
		return "USER"
	case PermissionMod:
		return "MODERATOR"
	case PermissionAdmin:
		return "ADMIN"
	case PermissionSuper:
		return "SUPER_ADMIN"
	default:
		return "UNKNOWN"
	}
}

// ParsePermissionLevel parses a string into a PermissionLevel.
func ParsePermissionLevel(s string) (PermissionLevel, error) {
	switch strings.ToUpper(strings.TrimSpace(s)) {
	case "NONE", "0":
		return PermissionNone, nil
	case "USER", "1":
		return PermissionUser, nil
	case "MODERATOR", "MOD", "2":
		return PermissionMod, nil
	case "ADMIN", "ADMINISTRATOR", "3":
		return PermissionAdmin, nil
	case "SUPER_ADMIN", "SUPER", "SUPERADMIN", "4":
		return PermissionSuper, nil
	default:
		return PermissionNone, ErrInvalidPermissionLevel
	}
}

// IsValid checks if the permission level is a valid value.
func (p PermissionLevel) IsValid() bool {
	return p >= PermissionNone && p <= PermissionSuper
}

// PermissionManager handles permission checks.
// It enforces the four-level permission hierarchy where higher levels
// inherit all permissions from lower levels.
type PermissionManager struct {
	authManager *Manager
}

// NewPermissionManager creates a new PermissionManager.
func NewPermissionManager(authManager *Manager) *PermissionManager {
	return &PermissionManager{authManager: authManager}
}

// HasPermission checks if a client has at least the required permission level.
// Returns true if the client's permission level is >= required level.
func (pm *PermissionManager) HasPermission(clientID string, required PermissionLevel) bool {
	level := pm.authManager.GetClientPermission(clientID)
	return level >= required
}

// CheckPermission checks if a client has the required permission level.
// Returns ErrPermissionDenied if the client lacks the required permission.
func (pm *PermissionManager) CheckPermission(clientID string, required PermissionLevel) error {
	if !pm.HasPermission(clientID, required) {
		return ErrPermissionDenied
	}
	return nil
}

// GetPermissionLevel returns the permission level for a client.
func (pm *PermissionManager) GetPermissionLevel(clientID string) PermissionLevel {
	return pm.authManager.GetClientPermission(clientID)
}

// CanChat checks if a client can send chat messages.
func (pm *PermissionManager) CanChat(clientID string) bool {
	return pm.HasPermission(clientID, PermissionUser)
}

// CanJoinChannel checks if a client can join channels.
func (pm *PermissionManager) CanJoinChannel(clientID string) bool {
	return pm.HasPermission(clientID, PermissionUser)
}

// CanMute checks if a client can mute other users.
func (pm *PermissionManager) CanMute(clientID string) bool {
	return pm.HasPermission(clientID, PermissionMod)
}

// CanKick checks if a client can kick other users.
func (pm *PermissionManager) CanKick(clientID string) bool {
	return pm.HasPermission(clientID, PermissionMod)
}

// CanSendTitle checks if a client can send title messages.
func (pm *PermissionManager) CanSendTitle(clientID string) bool {
	return pm.HasPermission(clientID, PermissionMod)
}

// CanAnnounce checks if a client can send announcements.
func (pm *PermissionManager) CanAnnounce(clientID string) bool {
	return pm.HasPermission(clientID, PermissionAdmin)
}

// CanManageChannels checks if a client can create/delete channels.
func (pm *PermissionManager) CanManageChannels(clientID string) bool {
	return pm.HasPermission(clientID, PermissionAdmin)
}

// CanManageUsers checks if a client can manage user permissions.
func (pm *PermissionManager) CanManageUsers(clientID string) bool {
	return pm.HasPermission(clientID, PermissionAdmin)
}

// CanReload checks if a client can reload configuration.
func (pm *PermissionManager) CanReload(clientID string) bool {
	return pm.HasPermission(clientID, PermissionSuper)
}

// CanShutdown checks if a client can shutdown the server.
func (pm *PermissionManager) CanShutdown(clientID string) bool {
	return pm.HasPermission(clientID, PermissionSuper)
}

// CanBypassFilter checks if a client can bypass the sensitive word filter.
func (pm *PermissionManager) CanBypassFilter(clientID string) bool {
	return pm.HasPermission(clientID, PermissionAdmin)
}

// RequiredPermissionFor returns the required permission level for an operation.
func RequiredPermissionFor(operation string) PermissionLevel {
	switch strings.ToLower(operation) {
	case "chat", "join", "leave", "toggle":
		return PermissionUser
	case "mute", "unmute", "kick", "title":
		return PermissionMod
	case "announce", "channel_create", "channel_delete", "user_manage":
		return PermissionAdmin
	case "reload", "shutdown", "debug":
		return PermissionSuper
	default:
		return PermissionNone
	}
}
