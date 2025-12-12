// Package websocket provides WebSocket gateway and webhook functionality.
package websocket

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"
)

// WebhookEventType defines the type of webhook event.
type WebhookEventType string

const (
	// EventChatMessage is triggered when a chat message is sent
	EventChatMessage WebhookEventType = "chat_message"
	// EventPlayerJoin is triggered when a player joins
	EventPlayerJoin WebhookEventType = "player_join"
	// EventPlayerLeave is triggered when a player leaves
	EventPlayerLeave WebhookEventType = "player_leave"
	// EventChannelCreate is triggered when a channel is created
	EventChannelCreate WebhookEventType = "channel_create"
	// EventChannelDelete is triggered when a channel is deleted
	EventChannelDelete WebhookEventType = "channel_delete"
	// EventClientConnect is triggered when a client connects
	EventClientConnect WebhookEventType = "client_connect"
	// EventClientDisconnect is triggered when a client disconnects
	EventClientDisconnect WebhookEventType = "client_disconnect"
	// EventAnnouncement is triggered when an announcement is sent
	EventAnnouncement WebhookEventType = "announcement"
	// EventPlayerMute is triggered when a player is muted
	EventPlayerMute WebhookEventType = "player_mute"
	// EventPlayerUnmute is triggered when a player is unmuted
	EventPlayerUnmute WebhookEventType = "player_unmute"
)

// Webhook represents a registered webhook endpoint.
type Webhook struct {
	ID          string             `json:"id"`
	URL         string             `json:"url"`
	Secret      string             `json:"secret,omitempty"`
	Events      []WebhookEventType `json:"events"`
	Enabled     bool               `json:"enabled"`
	CreatedAt   time.Time          `json:"created_at"`
	Description string             `json:"description,omitempty"`
	RetryCount  int                `json:"retry_count"`
	Timeout     time.Duration      `json:"timeout"`
}


// WebhookPayload represents the payload sent to webhook endpoints.
type WebhookPayload struct {
	ID        string           `json:"id"`
	Event     WebhookEventType `json:"event"`
	Timestamp int64            `json:"timestamp"`
	Data      interface{}      `json:"data"`
}

// WebhookDelivery represents a webhook delivery attempt.
type WebhookDelivery struct {
	WebhookID    string
	PayloadID    string
	Attempt      int
	StatusCode   int
	ResponseBody string
	Error        string
	DeliveredAt  time.Time
	Duration     time.Duration
}

// WebhookManager handles webhook registration and event delivery.
type WebhookManager struct {
	webhooks   map[string]*Webhook
	deliveries []WebhookDelivery
	httpClient *http.Client
	mutex      sync.RWMutex
	stopChan   chan struct{}
	eventChan  chan *webhookEvent
	wg         sync.WaitGroup

	// Settings
	maxDeliveries int // Maximum number of deliveries to keep in history
	workerCount   int // Number of concurrent delivery workers
}

// webhookEvent represents an event to be delivered.
type webhookEvent struct {
	webhook *Webhook
	payload *WebhookPayload
}

// WebhookManagerOption is a function that configures a WebhookManager.
type WebhookManagerOption func(*WebhookManager)

// WithMaxDeliveries sets the maximum number of deliveries to keep in history.
func WithMaxDeliveries(max int) WebhookManagerOption {
	return func(m *WebhookManager) {
		m.maxDeliveries = max
	}
}

// WithWorkerCount sets the number of concurrent delivery workers.
func WithWorkerCount(count int) WebhookManagerOption {
	return func(m *WebhookManager) {
		m.workerCount = count
	}
}

// NewWebhookManager creates a new WebhookManager.
func NewWebhookManager(opts ...WebhookManagerOption) *WebhookManager {
	m := &WebhookManager{
		webhooks:      make(map[string]*Webhook),
		deliveries:    make([]WebhookDelivery, 0),
		httpClient:    &http.Client{Timeout: 10 * time.Second},
		stopChan:      make(chan struct{}),
		eventChan:     make(chan *webhookEvent, 1000),
		maxDeliveries: 1000,
		workerCount:   5,
	}

	for _, opt := range opts {
		opt(m)
	}

	return m
}

// Start starts the webhook delivery workers.
func (m *WebhookManager) Start() {
	for i := 0; i < m.workerCount; i++ {
		m.wg.Add(1)
		go m.deliveryWorker()
	}
}

// Stop stops the webhook manager and waits for pending deliveries.
func (m *WebhookManager) Stop() {
	close(m.stopChan)
	m.wg.Wait()
}


// RegisterWebhook registers a new webhook.
func (m *WebhookManager) RegisterWebhook(webhook *Webhook) error {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	if webhook.ID == "" {
		webhook.ID = fmt.Sprintf("wh-%d", time.Now().UnixNano())
	}
	if webhook.CreatedAt.IsZero() {
		webhook.CreatedAt = time.Now()
	}
	if webhook.Timeout == 0 {
		webhook.Timeout = 10 * time.Second
	}
	if webhook.RetryCount == 0 {
		webhook.RetryCount = 3
	}

	m.webhooks[webhook.ID] = webhook
	return nil
}

// UnregisterWebhook removes a webhook.
func (m *WebhookManager) UnregisterWebhook(id string) bool {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	if _, exists := m.webhooks[id]; exists {
		delete(m.webhooks, id)
		return true
	}
	return false
}

// GetWebhook returns a webhook by ID.
func (m *WebhookManager) GetWebhook(id string) *Webhook {
	m.mutex.RLock()
	defer m.mutex.RUnlock()
	return m.webhooks[id]
}

// GetAllWebhooks returns all registered webhooks.
func (m *WebhookManager) GetAllWebhooks() []*Webhook {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	webhooks := make([]*Webhook, 0, len(m.webhooks))
	for _, wh := range m.webhooks {
		webhooks = append(webhooks, wh)
	}
	return webhooks
}

// EnableWebhook enables a webhook.
func (m *WebhookManager) EnableWebhook(id string) bool {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	if wh, exists := m.webhooks[id]; exists {
		wh.Enabled = true
		return true
	}
	return false
}

// DisableWebhook disables a webhook.
func (m *WebhookManager) DisableWebhook(id string) bool {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	if wh, exists := m.webhooks[id]; exists {
		wh.Enabled = false
		return true
	}
	return false
}

// UpdateWebhook updates a webhook's configuration.
func (m *WebhookManager) UpdateWebhook(id string, url string, events []WebhookEventType, secret string) bool {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	if wh, exists := m.webhooks[id]; exists {
		if url != "" {
			wh.URL = url
		}
		if events != nil {
			wh.Events = events
		}
		if secret != "" {
			wh.Secret = secret
		}
		return true
	}
	return false
}


// TriggerEvent triggers an event and delivers it to all matching webhooks.
func (m *WebhookManager) TriggerEvent(eventType WebhookEventType, data interface{}) {
	m.mutex.RLock()
	webhooks := make([]*Webhook, 0)
	for _, wh := range m.webhooks {
		if wh.Enabled && m.webhookMatchesEvent(wh, eventType) {
			webhooks = append(webhooks, wh)
		}
	}
	m.mutex.RUnlock()

	if len(webhooks) == 0 {
		return
	}

	payload := &WebhookPayload{
		ID:        fmt.Sprintf("evt-%d", time.Now().UnixNano()),
		Event:     eventType,
		Timestamp: time.Now().UnixMilli(),
		Data:      data,
	}

	for _, wh := range webhooks {
		select {
		case m.eventChan <- &webhookEvent{webhook: wh, payload: payload}:
		default:
			fmt.Printf("[WARN] Webhook event queue full, dropping event for %s\n", wh.ID)
		}
	}
}

// webhookMatchesEvent checks if a webhook is subscribed to an event type.
func (m *WebhookManager) webhookMatchesEvent(wh *Webhook, eventType WebhookEventType) bool {
	if len(wh.Events) == 0 {
		return true // Subscribe to all events if no specific events are set
	}
	for _, e := range wh.Events {
		if e == eventType {
			return true
		}
	}
	return false
}

// deliveryWorker processes webhook deliveries.
func (m *WebhookManager) deliveryWorker() {
	defer m.wg.Done()

	for {
		select {
		case <-m.stopChan:
			return
		case event := <-m.eventChan:
			m.deliverWebhook(event.webhook, event.payload)
		}
	}
}

// deliverWebhook delivers a payload to a webhook with retries.
func (m *WebhookManager) deliverWebhook(wh *Webhook, payload *WebhookPayload) {
	var lastErr error
	var lastStatusCode int
	var lastResponseBody string

	for attempt := 1; attempt <= wh.RetryCount; attempt++ {
		startTime := time.Now()
		statusCode, responseBody, err := m.sendWebhook(wh, payload)
		duration := time.Since(startTime)

		lastStatusCode = statusCode
		lastResponseBody = responseBody
		lastErr = err

		// Record delivery attempt
		delivery := WebhookDelivery{
			WebhookID:    wh.ID,
			PayloadID:    payload.ID,
			Attempt:      attempt,
			StatusCode:   statusCode,
			ResponseBody: responseBody,
			DeliveredAt:  time.Now(),
			Duration:     duration,
		}
		if err != nil {
			delivery.Error = err.Error()
		}
		m.recordDelivery(delivery)

		// Success if status code is 2xx
		if statusCode >= 200 && statusCode < 300 {
			return
		}

		// Wait before retry (exponential backoff)
		if attempt < wh.RetryCount {
			backoff := time.Duration(attempt*attempt) * time.Second
			time.Sleep(backoff)
		}
	}

	if lastErr != nil {
		fmt.Printf("[WARN] Webhook delivery failed for %s: %v (status: %d)\n", wh.ID, lastErr, lastStatusCode)
	} else {
		fmt.Printf("[WARN] Webhook delivery failed for %s: status %d, body: %s\n", wh.ID, lastStatusCode, lastResponseBody)
	}
}


// sendWebhook sends a single webhook request.
func (m *WebhookManager) sendWebhook(wh *Webhook, payload *WebhookPayload) (int, string, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return 0, "", fmt.Errorf("failed to marshal payload: %w", err)
	}

	req, err := http.NewRequest("POST", wh.URL, bytes.NewReader(body))
	if err != nil {
		return 0, "", fmt.Errorf("failed to create request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "NovaLink-Go/1.0")
	req.Header.Set("X-NovaLink-Event", string(payload.Event))
	req.Header.Set("X-NovaLink-Delivery", payload.ID)

	// Add HMAC signature if secret is configured
	if wh.Secret != "" {
		signature := m.computeSignature(body, wh.Secret)
		req.Header.Set("X-NovaLink-Signature", signature)
	}

	// Use custom timeout if set
	client := m.httpClient
	if wh.Timeout > 0 && wh.Timeout != 10*time.Second {
		client = &http.Client{Timeout: wh.Timeout}
	}

	resp, err := client.Do(req)
	if err != nil {
		return 0, "", fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()

	// Read response body (limited to 1KB)
	respBody := make([]byte, 1024)
	n, _ := resp.Body.Read(respBody)
	responseBody := string(respBody[:n])

	return resp.StatusCode, responseBody, nil
}

// computeSignature computes HMAC-SHA256 signature for webhook payload.
func (m *WebhookManager) computeSignature(payload []byte, secret string) string {
	h := hmac.New(sha256.New, []byte(secret))
	h.Write(payload)
	return "sha256=" + hex.EncodeToString(h.Sum(nil))
}

// recordDelivery records a delivery attempt.
func (m *WebhookManager) recordDelivery(delivery WebhookDelivery) {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	m.deliveries = append(m.deliveries, delivery)

	// Trim old deliveries if exceeding max
	if len(m.deliveries) > m.maxDeliveries {
		m.deliveries = m.deliveries[len(m.deliveries)-m.maxDeliveries:]
	}
}

// GetDeliveries returns recent delivery attempts.
func (m *WebhookManager) GetDeliveries(webhookID string, limit int) []WebhookDelivery {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	var result []WebhookDelivery
	for i := len(m.deliveries) - 1; i >= 0 && len(result) < limit; i-- {
		if webhookID == "" || m.deliveries[i].WebhookID == webhookID {
			result = append(result, m.deliveries[i])
		}
	}
	return result
}

// GetDeliveryStats returns delivery statistics for a webhook.
func (m *WebhookManager) GetDeliveryStats(webhookID string) (total, success, failed int) {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	for _, d := range m.deliveries {
		if webhookID == "" || d.WebhookID == webhookID {
			total++
			if d.StatusCode >= 200 && d.StatusCode < 300 {
				success++
			} else {
				failed++
			}
		}
	}
	return
}

// ClearDeliveries clears delivery history.
func (m *WebhookManager) ClearDeliveries() {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	m.deliveries = make([]WebhookDelivery, 0)
}

// WebhookCount returns the number of registered webhooks.
func (m *WebhookManager) WebhookCount() int {
	m.mutex.RLock()
	defer m.mutex.RUnlock()
	return len(m.webhooks)
}

// EnabledWebhookCount returns the number of enabled webhooks.
func (m *WebhookManager) EnabledWebhookCount() int {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	count := 0
	for _, wh := range m.webhooks {
		if wh.Enabled {
			count++
		}
	}
	return count
}


// TriggerChatMessage triggers a chat message event.
func (m *WebhookManager) TriggerChatMessage(senderID, senderName, clientID, channelID, content string) {
	m.TriggerEvent(EventChatMessage, ChatMessageData{
		SenderID:   senderID,
		SenderName: senderName,
		ClientID:   clientID,
		ChannelID:  channelID,
		Content:    content,
	})
}

// TriggerPlayerJoin triggers a player join event.
func (m *WebhookManager) TriggerPlayerJoin(playerID, playerName, clientID, channelID string) {
	m.TriggerEvent(EventPlayerJoin, PlayerEventData{
		PlayerID:   playerID,
		PlayerName: playerName,
		ClientID:   clientID,
		ChannelID:  channelID,
	})
}

// TriggerPlayerLeave triggers a player leave event.
func (m *WebhookManager) TriggerPlayerLeave(playerID, playerName, clientID, channelID string) {
	m.TriggerEvent(EventPlayerLeave, PlayerEventData{
		PlayerID:   playerID,
		PlayerName: playerName,
		ClientID:   clientID,
		ChannelID:  channelID,
	})
}

// TriggerChannelCreate triggers a channel create event.
func (m *WebhookManager) TriggerChannelCreate(channelID, displayName string, memberCount int) {
	m.TriggerEvent(EventChannelCreate, ChannelUpdateData{
		ChannelID:   channelID,
		DisplayName: displayName,
		MemberCount: memberCount,
		Action:      "created",
	})
}

// TriggerChannelDelete triggers a channel delete event.
func (m *WebhookManager) TriggerChannelDelete(channelID, displayName string) {
	m.TriggerEvent(EventChannelDelete, ChannelUpdateData{
		ChannelID:   channelID,
		DisplayName: displayName,
		Action:      "deleted",
	})
}

// TriggerClientConnect triggers a client connect event.
func (m *WebhookManager) TriggerClientConnect(clientID string, playerCount int) {
	m.TriggerEvent(EventClientConnect, ClientStatusData{
		ClientID:    clientID,
		Status:      "connected",
		PlayerCount: playerCount,
	})
}

// TriggerClientDisconnect triggers a client disconnect event.
func (m *WebhookManager) TriggerClientDisconnect(clientID string) {
	m.TriggerEvent(EventClientDisconnect, ClientStatusData{
		ClientID: clientID,
		Status:   "disconnected",
	})
}

// TriggerAnnouncement triggers an announcement event.
func (m *WebhookManager) TriggerAnnouncement(content string, announcementType int) {
	m.TriggerEvent(EventAnnouncement, map[string]interface{}{
		"content": content,
		"type":    announcementType,
	})
}

// TriggerPlayerMute triggers a player mute event.
func (m *WebhookManager) TriggerPlayerMute(playerID, playerName, reason, mutedBy string, duration int64) {
	m.TriggerEvent(EventPlayerMute, map[string]interface{}{
		"player_id":   playerID,
		"player_name": playerName,
		"reason":      reason,
		"muted_by":    mutedBy,
		"duration":    duration,
	})
}

// TriggerPlayerUnmute triggers a player unmute event.
func (m *WebhookManager) TriggerPlayerUnmute(playerID, playerName string) {
	m.TriggerEvent(EventPlayerUnmute, map[string]interface{}{
		"player_id":   playerID,
		"player_name": playerName,
	})
}
