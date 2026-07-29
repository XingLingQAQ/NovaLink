// Package websocket provides WebSocket gateway and REST API functionality.
package websocket

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/nova/novalink-go/pkg/auth"
	"github.com/nova/novalink-go/pkg/channel"
	"github.com/nova/novalink-go/pkg/mute"
	"github.com/google/uuid"
)

// APIServer provides REST API endpoints for the NovaLink system.
type APIServer struct {
	jwtService              *auth.JWTService
	channelManager          *channel.Manager
	muteManager             *mute.Manager
	authManager             *auth.Manager
	wsServer                *Server
	clientProvider          ClientProvider
	announcementBroadcaster AnnouncementBroadcaster
}

// APIResponse represents a standard API response.
type APIResponse struct {
	Success bool        `json:"success"`
	Data    interface{} `json:"data,omitempty"`
	Error   *APIError   `json:"error,omitempty"`
}

// APIError represents an API error.
type APIError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// NewAPIServer creates a new API server.
func NewAPIServer(
	jwtService *auth.JWTService,
	channelManager *channel.Manager,
	muteManager *mute.Manager,
	authManager *auth.Manager,
	wsServer *Server,
) *APIServer {
	return &APIServer{
		jwtService:     jwtService,
		channelManager: channelManager,
		muteManager:    muteManager,
		authManager:    authManager,
		wsServer:       wsServer,
	}
}


// RegisterRoutes registers all API routes on the given mux.
func (a *APIServer) RegisterRoutes(mux *http.ServeMux) {
	// Authentication endpoints
	mux.HandleFunc("/api/auth/login", a.corsMiddleware(a.handleLogin))
	// Refresh uses refreshToken in request body (same as Java backend / nova-panel).
	mux.HandleFunc("/api/auth/refresh", a.corsMiddleware(a.handleRefresh))

	// Channel endpoints
	mux.HandleFunc("/api/channels", a.corsMiddleware(a.authMiddleware(a.handleChannels)))
	mux.HandleFunc("/api/channels/", a.corsMiddleware(a.authMiddleware(a.handleChannelByID)))

	// Client endpoints
	mux.HandleFunc("/api/clients", a.corsMiddleware(a.authMiddleware(a.handleClients)))

	// Player endpoints
	mux.HandleFunc("/api/players", a.corsMiddleware(a.authMiddleware(a.handlePlayers)))
	mux.HandleFunc("/api/players/mute", a.corsMiddleware(a.authMiddleware(a.handleMute)))

	// Stats endpoint
	mux.HandleFunc("/api/stats", a.corsMiddleware(a.authMiddleware(a.handleStats)))

	// Announcement endpoint
	mux.HandleFunc("/api/announce", a.corsMiddleware(a.authMiddleware(a.handleAnnounce)))

	// Health check
	mux.HandleFunc("/api/health", a.corsMiddleware(a.handleHealth))
}

// corsMiddleware adds CORS headers to responses.
func (a *APIServer) corsMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

		if r.Method == "OPTIONS" {
			w.WriteHeader(http.StatusOK)
			return
		}

		next(w, r)
	}
}

// authMiddleware validates JWT tokens.
func (a *APIServer) authMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		if authHeader == "" {
			a.sendError(w, http.StatusUnauthorized, "NC-401", "Authorization header required")
			return
		}

		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || parts[0] != "Bearer" {
			a.sendError(w, http.StatusUnauthorized, "NC-401", "Invalid authorization format")
			return
		}

		claims, err := a.jwtService.VerifyToken(parts[1])
		if err != nil {
			a.sendError(w, http.StatusUnauthorized, "NC-401", "Invalid or expired token")
			return
		}
		// Refresh tokens must not be accepted for API authorization.
		if claims.Type == "refresh" {
			a.sendError(w, http.StatusUnauthorized, "NC-401", "Invalid or expired token")
			return
		}

		// Store claims in request context via header (simple approach)
		r.Header.Set("X-User-Subject", claims.Subject)
		r.Header.Set("X-User-Role", claims.Role)

		next(w, r)
	}
}


// sendJSON sends a JSON response.
func (a *APIServer) sendJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(APIResponse{
		Success: true,
		Data:    data,
	})
}

// sendError sends an error response.
func (a *APIServer) sendError(w http.ResponseWriter, status int, code, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	// Keep both legacy wrapped format and a top-level "message" for frontend compatibility.
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"success": false,
		"error": map[string]string{
			"code":    code,
			"message": message,
		},
		"message": message,
	})
}

// LoginRequest represents a login request.
type LoginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

// LoginResponse represents a login response.
type LoginResponse struct {
	Token     string `json:"token"`
	ExpiresAt int64  `json:"expires_at"`
	Role      string `json:"role"`
}

// handleLogin handles POST /api/auth/login
func (a *APIServer) handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		a.sendError(w, http.StatusMethodNotAllowed, "NC-405", "Method not allowed")
		return
	}

	var req LoginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		a.sendError(w, http.StatusBadRequest, "NC-400", "Invalid request body")
		return
	}

	// Validate credentials using auth manager if available
	role := "CLIENT_ADMIN"
	if a.authManager != nil {
		// Authenticate using the auth manager
		err := a.authManager.AuthenticateWithPassword(req.Username, req.Password, r.RemoteAddr)
		if err != nil {
			a.sendError(w, http.StatusUnauthorized, "NC-401", "Invalid credentials")
			return
		}
		// Get permission level to determine role
		perm := a.authManager.GetClientPermission(req.Username)
		role = perm.String()
	}

	// Generate tokens (aligned with Java backend response shape)
	userID := uuid.NewString()
	token, err := a.jwtService.GenerateAccessToken(userID, req.Username, role, "")
	if err != nil {
		a.sendError(w, http.StatusInternalServerError, "NC-500", "Failed to generate token")
		return
	}
	refreshToken, err := a.jwtService.GenerateRefreshToken(userID, req.Username, role, 7*24*time.Hour)
	if err != nil {
		a.sendError(w, http.StatusInternalServerError, "NC-500", "Failed to generate token")
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"token":        token,
		"refreshToken": refreshToken,
		"user": map[string]interface{}{
			"id":       userID,
			"username": req.Username,
			"role":     role,
		},
	})
}

// handleRefresh handles POST /api/auth/refresh
func (a *APIServer) handleRefresh(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		a.sendError(w, http.StatusMethodNotAllowed, "NC-405", "Method not allowed")
		return
	}

	var body struct {
		RefreshToken string `json:"refreshToken"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		a.sendError(w, http.StatusBadRequest, "NC-400", "Invalid request body")
		return
	}
	if strings.TrimSpace(body.RefreshToken) == "" {
		a.sendError(w, http.StatusBadRequest, "NC-400", "Missing refresh token")
		return
	}

	claims, err := a.jwtService.VerifyToken(body.RefreshToken)
	if err != nil || claims.Type != "refresh" {
		a.sendError(w, http.StatusUnauthorized, "NC-401", "Invalid or expired refresh token")
		return
	}

	newToken, err := a.jwtService.GenerateAccessToken(claims.Subject, claims.Username, claims.Role, claims.ClientID)
	if err != nil {
		a.sendError(w, http.StatusInternalServerError, "NC-500", "Failed to generate token")
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"token": newToken,
	})
}


// ChannelResponse represents a channel in API responses.
type ChannelResponse struct {
	ID            string   `json:"id"`
	DisplayName   string   `json:"display_name"`
	Scope         string   `json:"scope"`
	ClientID      string   `json:"client_id,omitempty"`
	Permission    string   `json:"permission,omitempty"`
	MaxCapacity   int      `json:"max_capacity"`
	MemberCount   int      `json:"member_count"`
	AllowedWorlds []string `json:"allowed_worlds,omitempty"`
	HasPassword   bool     `json:"has_password"`
	OwnerID       string   `json:"owner_id,omitempty"`
	CreatedAt     int64    `json:"created_at"`
}

// handleChannels handles GET /api/channels
func (a *APIServer) handleChannels(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		a.sendError(w, http.StatusMethodNotAllowed, "NC-405", "Method not allowed")
		return
	}

	if a.channelManager == nil {
		a.sendError(w, http.StatusServiceUnavailable, "NC-503", "Channel manager not available")
		return
	}

	infos := a.channelManager.GetAllChannelInfos()
	channels := make([]ChannelResponse, 0, len(infos))

	for _, info := range infos {
		channels = append(channels, ChannelResponse{
			ID:            info.ID,
			DisplayName:   info.DisplayName,
			Scope:         string(info.Scope),
			ClientID:      info.ClientID,
			Permission:    info.Permission,
			MaxCapacity:   info.MaxCapacity,
			MemberCount:   info.MemberCount,
			AllowedWorlds: info.AllowedWorlds,
			HasPassword:   info.HasPassword,
			OwnerID:       info.OwnerID,
			CreatedAt:     info.CreatedAt.Unix(),
		})
	}

	a.sendJSON(w, http.StatusOK, channels)
}

// handleChannelByID handles GET/DELETE /api/channels/{id}
func (a *APIServer) handleChannelByID(w http.ResponseWriter, r *http.Request) {
	// Extract channel ID from path
	path := strings.TrimPrefix(r.URL.Path, "/api/channels/")
	if path == "" {
		a.sendError(w, http.StatusBadRequest, "NC-400", "Channel ID required")
		return
	}

	if a.channelManager == nil {
		a.sendError(w, http.StatusServiceUnavailable, "NC-503", "Channel manager not available")
		return
	}

	switch r.Method {
	case "GET":
		info, err := a.channelManager.GetChannelInfo(path)
		if err != nil {
			a.sendError(w, http.StatusNotFound, "NC-404", "Channel not found")
			return
		}

		a.sendJSON(w, http.StatusOK, ChannelResponse{
			ID:            info.ID,
			DisplayName:   info.DisplayName,
			Scope:         string(info.Scope),
			ClientID:      info.ClientID,
			Permission:    info.Permission,
			MaxCapacity:   info.MaxCapacity,
			MemberCount:   info.MemberCount,
			AllowedWorlds: info.AllowedWorlds,
			HasPassword:   info.HasPassword,
			OwnerID:       info.OwnerID,
			CreatedAt:     info.CreatedAt.Unix(),
		})

	case "DELETE":
		if err := a.channelManager.DeleteChannel(path); err != nil {
			a.sendError(w, http.StatusNotFound, "NC-404", "Channel not found")
			return
		}
		a.sendJSON(w, http.StatusOK, map[string]string{"message": "Channel deleted"})

	default:
		a.sendError(w, http.StatusMethodNotAllowed, "NC-405", "Method not allowed")
	}
}


// ClientResponse represents a connected client in API responses.
type ClientResponse struct {
	ID          string `json:"id"`
	RemoteAddr  string `json:"remote_addr"`
	Platform    string `json:"platform"`
	PlayerCount int    `json:"player_count"`
	ConnectedAt int64  `json:"connected_at"`
}

// ClientProvider provides access to connected clients.
type ClientProvider interface {
	GetAllClients() []ClientInfo
}

// ClientInfo represents basic client information.
type ClientInfo struct {
	ID          string
	RemoteAddr  string
	Platform    string
	PlayerCount int
	ConnectedAt time.Time
}

// SetClientProvider sets the client provider for the API server.
func (a *APIServer) SetClientProvider(provider ClientProvider) {
	a.clientProvider = provider
}

// handleClients handles GET /api/clients
func (a *APIServer) handleClients(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		a.sendError(w, http.StatusMethodNotAllowed, "NC-405", "Method not allowed")
		return
	}

	if a.clientProvider == nil {
		// Return empty list if no provider
		a.sendJSON(w, http.StatusOK, []ClientResponse{})
		return
	}

	clientInfos := a.clientProvider.GetAllClients()
	clients := make([]ClientResponse, 0, len(clientInfos))

	for _, info := range clientInfos {
		clients = append(clients, ClientResponse{
			ID:          info.ID,
			RemoteAddr:  info.RemoteAddr,
			Platform:    info.Platform,
			PlayerCount: info.PlayerCount,
			ConnectedAt: info.ConnectedAt.Unix(),
		})
	}

	a.sendJSON(w, http.StatusOK, clients)
}

// PlayerResponse represents a player in API responses.
type PlayerResponse struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	ClientID string `json:"client_id"`
	Channel  string `json:"channel"`
	World    string `json:"world"`
	IsMuted  bool   `json:"is_muted"`
}

// handlePlayers handles GET /api/players
func (a *APIServer) handlePlayers(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		a.sendError(w, http.StatusMethodNotAllowed, "NC-405", "Method not allowed")
		return
	}

	if a.channelManager == nil {
		a.sendJSON(w, http.StatusOK, []PlayerResponse{})
		return
	}

	// Collect all players from all channels
	playerMap := make(map[string]*PlayerResponse)
	channels := a.channelManager.GetAllChannels()

	for _, ch := range channels {
		members := a.channelManager.GetAllMembers(ch.ID)
		for _, member := range members {
			if _, exists := playerMap[member.PlayerID]; !exists {
				isMuted := false
				if a.muteManager != nil {
					isMuted = a.muteManager.IsMuted(member.PlayerID)
				}
				playerMap[member.PlayerID] = &PlayerResponse{
					ID:       member.PlayerID,
					Name:     member.PlayerName,
					ClientID: member.ClientID,
					Channel:  ch.ID,
					World:    member.World,
					IsMuted:  isMuted,
				}
			}
		}
	}

	players := make([]PlayerResponse, 0, len(playerMap))
	for _, p := range playerMap {
		players = append(players, *p)
	}

	a.sendJSON(w, http.StatusOK, players)
}


// MuteRequest represents a mute request.
type MuteRequest struct {
	PlayerID string `json:"player_id"`
	Duration int64  `json:"duration"` // Duration in seconds, 0 for unmute
	Reason   string `json:"reason"`
}

// handleMute handles POST /api/players/mute
func (a *APIServer) handleMute(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		a.sendError(w, http.StatusMethodNotAllowed, "NC-405", "Method not allowed")
		return
	}

	if a.muteManager == nil {
		a.sendError(w, http.StatusServiceUnavailable, "NC-503", "Mute manager not available")
		return
	}

	var req MuteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		a.sendError(w, http.StatusBadRequest, "NC-400", "Invalid request body")
		return
	}

	if req.Duration == 0 {
		// Unmute
		a.muteManager.Unmute(req.PlayerID)
		a.sendJSON(w, http.StatusOK, map[string]string{"message": "Player unmuted"})
	} else {
		// Mute
		duration := time.Duration(req.Duration) * time.Second
		mutedBy := r.Header.Get("X-User-Subject")
		a.muteManager.Mute(req.PlayerID, req.PlayerID, req.Reason, mutedBy, duration)
		a.sendJSON(w, http.StatusOK, map[string]string{"message": "Player muted"})
	}
}

// StatsResponse represents system statistics.
type StatsResponse struct {
	ClientCount    int   `json:"client_count"`
	ChannelCount   int   `json:"channel_count"`
	PlayerCount    int   `json:"player_count"`
	WebSocketCount int   `json:"websocket_count"`
	Uptime         int64 `json:"uptime"`
}

var serverStartTime = time.Now()

// handleStats handles GET /api/stats
func (a *APIServer) handleStats(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		a.sendError(w, http.StatusMethodNotAllowed, "NC-405", "Method not allowed")
		return
	}

	clientCount := 0
	if a.clientProvider != nil {
		clientCount = len(a.clientProvider.GetAllClients())
	}

	channelCount := 0
	playerCount := 0
	if a.channelManager != nil {
		channels := a.channelManager.GetAllChannels()
		channelCount = len(channels)
		for _, ch := range channels {
			playerCount += a.channelManager.GetMemberCount(ch.ID)
		}
	}

	wsCount := 0
	if a.wsServer != nil {
		wsCount = a.wsServer.ClientCount()
	}

	a.sendJSON(w, http.StatusOK, StatsResponse{
		ClientCount:    clientCount,
		ChannelCount:   channelCount,
		PlayerCount:    playerCount,
		WebSocketCount: wsCount,
		Uptime:         int64(time.Since(serverStartTime).Seconds()),
	})
}

// AnnounceRequest represents an announcement request.
type AnnounceRequest struct {
	Content   string `json:"content"`
	Type      int    `json:"type"` // 0=chat, 1=actionbar, 2=bossbar
	ChannelID string `json:"channel_id,omitempty"`
	ClientID  string `json:"client_id,omitempty"`
}

// AnnouncementBroadcaster is an interface for broadcasting announcements.
type AnnouncementBroadcaster interface {
	Broadcast(content string, announcementType byte)
	BroadcastToChannel(channelID, content string, announcementType byte)
	BroadcastToClient(clientID, content string, announcementType byte)
}

// SetAnnouncementBroadcaster sets the announcement broadcaster.
func (a *APIServer) SetAnnouncementBroadcaster(broadcaster AnnouncementBroadcaster) {
	a.announcementBroadcaster = broadcaster
}

// handleAnnounce handles POST /api/announce
func (a *APIServer) handleAnnounce(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		a.sendError(w, http.StatusMethodNotAllowed, "NC-405", "Method not allowed")
		return
	}

	var req AnnounceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		a.sendError(w, http.StatusBadRequest, "NC-400", "Invalid request body")
		return
	}

	if a.announcementBroadcaster == nil {
		a.sendError(w, http.StatusServiceUnavailable, "NC-503", "Announcement system not available")
		return
	}

	announcementType := byte(req.Type)

	if req.ClientID != "" {
		a.announcementBroadcaster.BroadcastToClient(req.ClientID, req.Content, announcementType)
	} else if req.ChannelID != "" {
		a.announcementBroadcaster.BroadcastToChannel(req.ChannelID, req.Content, announcementType)
	} else {
		a.announcementBroadcaster.Broadcast(req.Content, announcementType)
	}

	a.sendJSON(w, http.StatusOK, map[string]string{"message": "Announcement sent"})
}

// handleHealth handles GET /api/health
func (a *APIServer) handleHealth(w http.ResponseWriter, r *http.Request) {
	a.sendJSON(w, http.StatusOK, map[string]string{
		"status":  "healthy",
		"version": "1.0.0",
	})
}
