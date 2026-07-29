package storage

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	_ "github.com/go-sql-driver/mysql"
)

// MySQLProvider implements Provider using MySQL database.
type MySQLProvider struct {
	config    MySQLConfig
	db        *sql.DB
	mutex     sync.RWMutex
	connected bool
}

// MySQLConfig contains MySQL connection settings.
type MySQLConfig struct {
	Host     string
	Port     int
	Database string
	Username string
	Password string
	PoolSize int
}

// NewMySQLProvider creates a new MySQLProvider.
func NewMySQLProvider(config MySQLConfig) *MySQLProvider {
	if config.PoolSize <= 0 {
		config.PoolSize = 10
	}
	return &MySQLProvider{
		config: config,
	}
}

func (p *MySQLProvider) Connect() error {
	p.mutex.Lock()
	defer p.mutex.Unlock()

	dsn := fmt.Sprintf("%s:%s@tcp(%s:%d)/%s?parseTime=true",
		p.config.Username,
		p.config.Password,
		p.config.Host,
		p.config.Port,
		p.config.Database,
	)

	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return fmt.Errorf("failed to open database: %w", err)
	}

	// Configure connection pool
	db.SetMaxOpenConns(p.config.PoolSize)
	db.SetMaxIdleConns(p.config.PoolSize / 2)
	db.SetConnMaxLifetime(time.Hour)

	// Test connection
	if err := db.Ping(); err != nil {
		db.Close()
		return fmt.Errorf("failed to ping database: %w", err)
	}

	p.db = db
	p.connected = true

	// Initialize tables
	if err := p.initTables(); err != nil {
		p.db.Close()
		p.connected = false
		return fmt.Errorf("failed to initialize tables: %w", err)
	}

	return nil
}

func (p *MySQLProvider) initTables() error {
	queries := []string{
		`CREATE TABLE IF NOT EXISTS player_states (
			player_id VARCHAR(36) PRIMARY KEY,
			player_name VARCHAR(64) NOT NULL,
			current_channel VARCHAR(64) DEFAULT '',
			joined_channels TEXT,
			chat_enabled BOOLEAN DEFAULT TRUE,
			last_seen BIGINT DEFAULT 0,
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
			updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
		)`,
		`CREATE TABLE IF NOT EXISTS mutes (
			player_id VARCHAR(36) PRIMARY KEY,
			player_name VARCHAR(64) NOT NULL,
			reason TEXT,
			muted_by VARCHAR(64) NOT NULL,
			muted_at BIGINT NOT NULL,
			expires_at BIGINT DEFAULT 0,
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
		)`,
		`CREATE TABLE IF NOT EXISTS channels (
			id VARCHAR(64) PRIMARY KEY,
			display_name VARCHAR(128) NOT NULL,
			scope VARCHAR(16) NOT NULL,
			client_id VARCHAR(64) DEFAULT '',
			permission VARCHAR(64) DEFAULT '',
			max_capacity INT DEFAULT 0,
			allowed_worlds TEXT,
			password VARCHAR(256) DEFAULT '',
			owner_id VARCHAR(36) DEFAULT '',
			format TEXT,
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
			updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
		)`,
	}

	for _, query := range queries {
		if _, err := p.db.Exec(query); err != nil {
			return fmt.Errorf("failed to execute query: %w", err)
		}
	}

	return nil
}

func (p *MySQLProvider) Close() error {
	p.mutex.Lock()
	defer p.mutex.Unlock()

	if p.db != nil {
		if err := p.db.Close(); err != nil {
			return err
		}
	}
	p.connected = false
	return nil
}

func (p *MySQLProvider) IsConnected() bool {
	p.mutex.RLock()
	defer p.mutex.RUnlock()
	return p.connected
}


// Player State Operations

func (p *MySQLProvider) GetPlayerState(playerID string) (*PlayerState, error) {
	if !p.IsConnected() {
		return nil, ErrNotConnected
	}

	query := `SELECT player_id, player_name, current_channel, joined_channels, chat_enabled, last_seen 
			  FROM player_states WHERE player_id = ?`

	var state PlayerState
	var joinedChannelsJSON string

	err := p.db.QueryRow(query, playerID).Scan(
		&state.PlayerID,
		&state.PlayerName,
		&state.CurrentChannel,
		&joinedChannelsJSON,
		&state.ChatEnabled,
		&state.LastSeen,
	)

	if err == sql.ErrNoRows {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("failed to query player state: %w", err)
	}

	// Parse joined channels JSON
	if joinedChannelsJSON != "" {
		if err := json.Unmarshal([]byte(joinedChannelsJSON), &state.JoinedChannels); err != nil {
			state.JoinedChannels = []string{}
		}
	}

	return &state, nil
}

func (p *MySQLProvider) SavePlayerState(state *PlayerState) error {
	if !p.IsConnected() {
		return ErrNotConnected
	}

	joinedChannelsJSON, err := json.Marshal(state.JoinedChannels)
	if err != nil {
		joinedChannelsJSON = []byte("[]")
	}

	query := `INSERT INTO player_states (player_id, player_name, current_channel, joined_channels, chat_enabled, last_seen)
			  VALUES (?, ?, ?, ?, ?, ?)
			  ON DUPLICATE KEY UPDATE
			  player_name = VALUES(player_name),
			  current_channel = VALUES(current_channel),
			  joined_channels = VALUES(joined_channels),
			  chat_enabled = VALUES(chat_enabled),
			  last_seen = VALUES(last_seen)`

	_, err = p.db.Exec(query,
		state.PlayerID,
		state.PlayerName,
		state.CurrentChannel,
		string(joinedChannelsJSON),
		state.ChatEnabled,
		state.LastSeen,
	)

	if err != nil {
		return fmt.Errorf("failed to save player state: %w", err)
	}

	return nil
}

func (p *MySQLProvider) DeletePlayerState(playerID string) error {
	if !p.IsConnected() {
		return ErrNotConnected
	}

	query := `DELETE FROM player_states WHERE player_id = ?`
	_, err := p.db.Exec(query, playerID)
	if err != nil {
		return fmt.Errorf("failed to delete player state: %w", err)
	}

	return nil
}


// Mute Operations

func (p *MySQLProvider) GetMute(playerID string) (*MuteRecord, error) {
	if !p.IsConnected() {
		return nil, ErrNotConnected
	}

	query := `SELECT player_id, player_name, reason, muted_by, muted_at, expires_at 
			  FROM mutes WHERE player_id = ?`

	var mute MuteRecord
	err := p.db.QueryRow(query, playerID).Scan(
		&mute.PlayerID,
		&mute.PlayerName,
		&mute.Reason,
		&mute.MutedBy,
		&mute.MutedAt,
		&mute.ExpiresAt,
	)

	if err == sql.ErrNoRows {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("failed to query mute: %w", err)
	}

	// Check if mute has expired
	if mute.ExpiresAt > 0 && time.Now().Unix() > mute.ExpiresAt {
		// Delete expired mute
		p.DeleteMute(playerID)
		return nil, ErrNotFound
	}

	return &mute, nil
}

func (p *MySQLProvider) SaveMute(mute *MuteRecord) error {
	if !p.IsConnected() {
		return ErrNotConnected
	}

	query := `INSERT INTO mutes (player_id, player_name, reason, muted_by, muted_at, expires_at)
			  VALUES (?, ?, ?, ?, ?, ?)
			  ON DUPLICATE KEY UPDATE
			  player_name = VALUES(player_name),
			  reason = VALUES(reason),
			  muted_by = VALUES(muted_by),
			  muted_at = VALUES(muted_at),
			  expires_at = VALUES(expires_at)`

	_, err := p.db.Exec(query,
		mute.PlayerID,
		mute.PlayerName,
		mute.Reason,
		mute.MutedBy,
		mute.MutedAt,
		mute.ExpiresAt,
	)

	if err != nil {
		return fmt.Errorf("failed to save mute: %w", err)
	}

	return nil
}

func (p *MySQLProvider) DeleteMute(playerID string) error {
	if !p.IsConnected() {
		return ErrNotConnected
	}

	query := `DELETE FROM mutes WHERE player_id = ?`
	_, err := p.db.Exec(query, playerID)
	if err != nil {
		return fmt.Errorf("failed to delete mute: %w", err)
	}

	return nil
}

func (p *MySQLProvider) GetActiveMutes() ([]*MuteRecord, error) {
	if !p.IsConnected() {
		return nil, ErrNotConnected
	}

	now := time.Now().Unix()
	query := `SELECT player_id, player_name, reason, muted_by, muted_at, expires_at 
			  FROM mutes WHERE expires_at = 0 OR expires_at > ?`

	rows, err := p.db.Query(query, now)
	if err != nil {
		return nil, fmt.Errorf("failed to query active mutes: %w", err)
	}
	defer rows.Close()

	var mutes []*MuteRecord
	for rows.Next() {
		var mute MuteRecord
		if err := rows.Scan(
			&mute.PlayerID,
			&mute.PlayerName,
			&mute.Reason,
			&mute.MutedBy,
			&mute.MutedAt,
			&mute.ExpiresAt,
		); err != nil {
			continue
		}
		mutes = append(mutes, &mute)
	}

	return mutes, nil
}


// Channel Operations

func (p *MySQLProvider) GetChannels() ([]*ChannelRecord, error) {
	if !p.IsConnected() {
		return nil, ErrNotConnected
	}

	query := `SELECT id, display_name, scope, client_id, permission, max_capacity, 
			  allowed_worlds, password, owner_id, format FROM channels`

	rows, err := p.db.Query(query)
	if err != nil {
		return nil, fmt.Errorf("failed to query channels: %w", err)
	}
	defer rows.Close()

	var channels []*ChannelRecord
	for rows.Next() {
		var ch ChannelRecord
		var allowedWorldsJSON string

		if err := rows.Scan(
			&ch.ID,
			&ch.DisplayName,
			&ch.Scope,
			&ch.ClientID,
			&ch.Permission,
			&ch.MaxCapacity,
			&allowedWorldsJSON,
			&ch.Password,
			&ch.OwnerID,
			&ch.Format,
		); err != nil {
			continue
		}

		// Parse allowed worlds JSON
		if allowedWorldsJSON != "" {
			if err := json.Unmarshal([]byte(allowedWorldsJSON), &ch.AllowedWorlds); err != nil {
				ch.AllowedWorlds = []string{}
			}
		}

		channels = append(channels, &ch)
	}

	return channels, nil
}

func (p *MySQLProvider) SaveChannel(channel *ChannelRecord) error {
	if !p.IsConnected() {
		return ErrNotConnected
	}

	allowedWorldsJSON, err := json.Marshal(channel.AllowedWorlds)
	if err != nil {
		allowedWorldsJSON = []byte("[]")
	}

	query := `INSERT INTO channels (id, display_name, scope, client_id, permission, max_capacity, 
			  allowed_worlds, password, owner_id, format)
			  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			  ON DUPLICATE KEY UPDATE
			  display_name = VALUES(display_name),
			  scope = VALUES(scope),
			  client_id = VALUES(client_id),
			  permission = VALUES(permission),
			  max_capacity = VALUES(max_capacity),
			  allowed_worlds = VALUES(allowed_worlds),
			  password = VALUES(password),
			  owner_id = VALUES(owner_id),
			  format = VALUES(format)`

	_, err = p.db.Exec(query,
		channel.ID,
		channel.DisplayName,
		channel.Scope,
		channel.ClientID,
		channel.Permission,
		channel.MaxCapacity,
		string(allowedWorldsJSON),
		channel.Password,
		channel.OwnerID,
		channel.Format,
	)

	if err != nil {
		return fmt.Errorf("failed to save channel: %w", err)
	}

	return nil
}

func (p *MySQLProvider) DeleteChannel(channelID string) error {
	if !p.IsConnected() {
		return ErrNotConnected
	}

	query := `DELETE FROM channels WHERE id = ?`
	_, err := p.db.Exec(query, channelID)
	if err != nil {
		return fmt.Errorf("failed to delete channel: %w", err)
	}

	return nil
}
