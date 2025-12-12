package channel

import (
	"strings"
	"sync"
)

// WorldFilter handles world-based message filtering.
type WorldFilter struct {
	manager *Manager
	// worldMappings maps world names to channel IDs for automatic routing
	worldMappings map[string]string
	// worldPatterns stores glob patterns for world matching
	worldPatterns map[string][]string // channelID -> patterns
	mutex         sync.RWMutex
}

// NewWorldFilter creates a new WorldFilter.
func NewWorldFilter(manager *Manager) *WorldFilter {
	return &WorldFilter{
		manager:       manager,
		worldMappings: make(map[string]string),
		worldPatterns: make(map[string][]string),
	}
}

// IsWorldAllowed checks if a world is allowed for a channel.
func (f *WorldFilter) IsWorldAllowed(channelID, world string) bool {
	channel, err := f.manager.GetChannel(channelID)
	if err != nil {
		return false
	}

	channel.mutex.RLock()
	defer channel.mutex.RUnlock()

	// If no world restrictions, allow all
	if len(channel.AllowedWorlds) == 0 {
		return true
	}

	// Check if world is in allowed list
	for _, pattern := range channel.AllowedWorlds {
		if matchWorldPattern(pattern, world) {
			return true
		}
	}

	return false
}

// matchWorldPattern checks if a world matches a pattern.
// Supports:
// - "*" matches all worlds
// - "world_*" matches worlds starting with "world_"
// - "*_nether" matches worlds ending with "_nether"
// - Exact match
func matchWorldPattern(pattern, world string) bool {
	if pattern == "*" {
		return true
	}

	if pattern == world {
		return true
	}

	// Handle prefix wildcard: "*_nether" matches "world_nether"
	if strings.HasPrefix(pattern, "*") {
		suffix := pattern[1:]
		return strings.HasSuffix(world, suffix)
	}

	// Handle suffix wildcard: "world_*" matches "world_nether"
	if strings.HasSuffix(pattern, "*") {
		prefix := pattern[:len(pattern)-1]
		return strings.HasPrefix(world, prefix)
	}

	return false
}

// GetAllowedChannelsForWorld returns channels that allow the given world.
func (f *WorldFilter) GetAllowedChannelsForWorld(world string) []*Channel {
	var channels []*Channel
	for _, ch := range f.manager.GetAllChannels() {
		if f.IsWorldAllowed(ch.ID, world) {
			channels = append(channels, ch)
		}
	}
	return channels
}

// SetWorldMapping sets a mapping from a world to a default channel.
// This is used for automatic channel switching when players change worlds.
func (f *WorldFilter) SetWorldMapping(world, channelID string) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	f.worldMappings[world] = channelID
}

// GetWorldMapping returns the default channel for a world.
func (f *WorldFilter) GetWorldMapping(world string) (string, bool) {
	f.mutex.RLock()
	defer f.mutex.RUnlock()

	// First try exact match
	if channelID, ok := f.worldMappings[world]; ok {
		return channelID, true
	}

	// Then try pattern matching
	for pattern, channelID := range f.worldMappings {
		if matchWorldPattern(pattern, world) {
			return channelID, true
		}
	}

	return "", false
}

// RemoveWorldMapping removes a world mapping.
func (f *WorldFilter) RemoveWorldMapping(world string) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	delete(f.worldMappings, world)
}

// GetAllWorldMappings returns all world mappings.
func (f *WorldFilter) GetAllWorldMappings() map[string]string {
	f.mutex.RLock()
	defer f.mutex.RUnlock()

	result := make(map[string]string, len(f.worldMappings))
	for k, v := range f.worldMappings {
		result[k] = v
	}
	return result
}

// FilterMembersByWorld filters a list of members to only those in allowed worlds.
func (f *WorldFilter) FilterMembersByWorld(members []*MemberInfo, allowedWorlds []string) []*MemberInfo {
	if len(allowedWorlds) == 0 {
		return members
	}

	filtered := make([]*MemberInfo, 0, len(members))
	for _, member := range members {
		for _, pattern := range allowedWorlds {
			if matchWorldPattern(pattern, member.World) {
				filtered = append(filtered, member)
				break
			}
		}
	}
	return filtered
}

// GetMembersInWorld returns all members of a channel that are in a specific world.
func (f *WorldFilter) GetMembersInWorld(channelID, world string) []*MemberInfo {
	members := f.manager.GetAllMembers(channelID)
	if members == nil {
		return nil
	}

	result := make([]*MemberInfo, 0)
	for _, member := range members {
		if member.World == world {
			result = append(result, member)
		}
	}
	return result
}

// GetWorldsInChannel returns all unique worlds that have members in a channel.
func (f *WorldFilter) GetWorldsInChannel(channelID string) []string {
	members := f.manager.GetAllMembers(channelID)
	if members == nil {
		return nil
	}

	worldSet := make(map[string]bool)
	for _, member := range members {
		if member.World != "" {
			worldSet[member.World] = true
		}
	}

	worlds := make([]string, 0, len(worldSet))
	for world := range worldSet {
		worlds = append(worlds, world)
	}
	return worlds
}

// CanPlayerAccessChannelInWorld checks if a player can access a channel from their current world.
func (f *WorldFilter) CanPlayerAccessChannelInWorld(channelID, playerID, world string) bool {
	// First check if the world is allowed
	if !f.IsWorldAllowed(channelID, world) {
		return false
	}

	// Then check if the player is a member
	return f.manager.IsMember(channelID, playerID)
}

// UpdatePlayerWorld updates a player's world and returns channels they should leave/join.
func (f *WorldFilter) UpdatePlayerWorld(playerID, oldWorld, newWorld string) (leave []string, join []string) {
	channels := f.manager.GetPlayerChannels(playerID)

	for _, ch := range channels {
		wasAllowed := f.IsWorldAllowed(ch.ID, oldWorld)
		isAllowed := f.IsWorldAllowed(ch.ID, newWorld)

		if wasAllowed && !isAllowed {
			leave = append(leave, ch.ID)
		} else if !wasAllowed && isAllowed {
			join = append(join, ch.ID)
		}
	}

	return leave, join
}
