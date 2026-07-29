// Package websocket provides WebSocket gateway and webhook functionality.
package websocket

// ChatMessageData represents chat message data for webhook delivery.
type ChatMessageData struct {
	SenderID   string `json:"sender_id"`
	SenderName string `json:"sender_name"`
	ClientID   string `json:"client_id"`
	ChannelID  string `json:"channel_id"`
	Content    string `json:"content"`
}

// PlayerEventData represents player join/leave data for webhook delivery.
type PlayerEventData struct {
	PlayerID   string `json:"player_id"`
	PlayerName string `json:"player_name"`
	ClientID   string `json:"client_id"`
	ChannelID  string `json:"channel_id,omitempty"`
}

// ChannelUpdateData represents channel update data for webhook delivery.
type ChannelUpdateData struct {
	ChannelID   string `json:"channel_id"`
	DisplayName string `json:"display_name"`
	MemberCount int    `json:"member_count"`
	Action      string `json:"action"` // created, deleted, updated
}

// ClientStatusData represents client status data for webhook delivery.
type ClientStatusData struct {
	ClientID    string `json:"client_id"`
	Status      string `json:"status"` // connected, disconnected
	PlayerCount int    `json:"player_count"`
}


