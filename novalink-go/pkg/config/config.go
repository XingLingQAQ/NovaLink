// Package config provides configuration loading and management for NovaLink-Go.
// The configuration format is compatible with the Java version's novalink.yml.
package config

import (
	"fmt"
	"os"
	"path/filepath"

	"gopkg.in/yaml.v3"
)

// Config represents the main configuration structure for NovaLink-Go.
// It mirrors the Java version's novalink.yml format for compatibility.
type Config struct {
	Server         ServerConfig                `yaml:"server"`
	Database       DatabaseConfig              `yaml:"database"`
	Security       SecurityConfig              `yaml:"security"`
	SuperAdmins    []SuperAdminConfig          `yaml:"super-admins"`
	GlobalChannels map[string]GlobalChannel    `yaml:"global_channels"`
	Templates      map[string]ChannelTemplate  `yaml:"templates"`
	Clients        []ClientConfig              `yaml:"clients"`
	Webhooks       []WebhookConfig             `yaml:"webhooks"`
	Filter         FilterConfig                `yaml:"filter"`
	Announcements  AnnouncementsConfig         `yaml:"announcements"`
	Debug          bool                        `yaml:"debug"`

	// Legacy fields for backward compatibility
	Auth       AuthConfig       `yaml:"auth"`
	Redis      RedisConfig      `yaml:"redis"`
	Channels   ChannelsConfig   `yaml:"channels"`
	WebSocket  WebSocketConfig  `yaml:"websocket"`
	Moderation ModerationConfig `yaml:"moderation"`
}

// ServerConfig contains TCP server settings.
type ServerConfig struct {
	BindAddress   string `yaml:"bind-address"`
	Host          string `yaml:"host"` // Legacy field
	Port          int    `yaml:"port"`
	WebSocketPort int    `yaml:"websocket-port"`
	SecretKey     string `yaml:"secret-key"`
	WorkerThreads int    `yaml:"worker-threads"`
}

// DatabaseConfig contains database connection settings.
type DatabaseConfig struct {
	Type  string      `yaml:"type"` // mysql, redis, memory
	MySQL MySQLConfig `yaml:"mysql"`
	Redis RedisConfig `yaml:"redis"`

	// Legacy flat fields for backward compatibility
	Host     string `yaml:"host"`
	DBPort   int    `yaml:"port"`
	Database string `yaml:"database"`
	Username string `yaml:"username"`
	Password string `yaml:"password"`
	PoolSize int    `yaml:"pool-size"`
}

// MySQLConfig contains MySQL-specific settings.
type MySQLConfig struct {
	Host     string `yaml:"host"`
	Port     int    `yaml:"port"`
	Database string `yaml:"database"`
	Username string `yaml:"username"`
	Password string `yaml:"password"`
	PoolSize int    `yaml:"pool-size"`
}

// RedisConfig contains Redis connection settings.
type RedisConfig struct {
	Enabled  bool   `yaml:"enabled"`
	Host     string `yaml:"host"`
	Port     int    `yaml:"port"`
	Password string `yaml:"password"`
	Database int    `yaml:"database"`
}

// SecurityConfig contains security settings.
type SecurityConfig struct {
	AllowedIPs      []string `yaml:"allowed-ips"`
	IPBanDuration   int      `yaml:"ip-ban-duration"`
	MaxAuthFailures int      `yaml:"max-auth-failures"`
}

// SuperAdminConfig represents a super admin configuration.
type SuperAdminConfig struct {
	UUID         string `yaml:"uuid"`
	PasswordHash string `yaml:"password-hash"`
}

// GlobalChannel represents a global channel configuration.
type GlobalChannel struct {
	DisplayName string `yaml:"display_name"`
	Permission  string `yaml:"permission"`
	MaxCapacity int    `yaml:"max_capacity"`
}

// ChannelTemplate represents a reusable channel template.
type ChannelTemplate struct {
	DisplayName   string   `yaml:"display_name"`
	Scope         string   `yaml:"scope"` // GLOBAL, SERVER, PRIVATE
	MaxCapacity   int      `yaml:"max_capacity"`
	Permission    string   `yaml:"permission"`
	AllowedWorlds []string `yaml:"allowed_worlds"`
	Format        string   `yaml:"format"`
}


// ClientConfig represents a client (Minecraft server) configuration.
type ClientConfig struct {
	Username    string                       `yaml:"username"`
	Password    string                       `yaml:"password"`
	DisplayName string                       `yaml:"display_name"`
	Channels    map[string]ClientChannelConfig `yaml:"channels"`
}

// ClientChannelConfig represents a client-specific channel configuration.
type ClientChannelConfig struct {
	UseTemplate   string   `yaml:"use_template"`
	DisplayName   string   `yaml:"display_name"`
	Scope         string   `yaml:"scope"`
	Permission    string   `yaml:"permission"`
	MaxCapacity   int      `yaml:"max_capacity"`
	AllowedWorlds []string `yaml:"allowed_worlds"`
}

// WebhookConfig represents a webhook configuration.
type WebhookConfig struct {
	URL     string   `yaml:"url"`
	Events  []string `yaml:"events"`
	Secret  string   `yaml:"secret"`
	Enabled bool     `yaml:"enabled"`
}

// FilterConfig contains sensitive word filter settings.
type FilterConfig struct {
	Enabled     bool     `yaml:"enabled"`
	CustomWords []string `yaml:"custom_words"`
	Patterns    []string `yaml:"patterns"`
	Replacement string   `yaml:"replacement"`
}

// AnnouncementsConfig contains announcement settings.
type AnnouncementsConfig struct {
	Scheduled []ScheduledAnnouncement `yaml:"scheduled"`
	Join      []JoinAnnouncement      `yaml:"join"`
}

// ScheduledAnnouncement represents a scheduled announcement.
type ScheduledAnnouncement struct {
	ID       string   `yaml:"id"`
	Cron     string   `yaml:"cron"`
	Channels []string `yaml:"channels"`
	Message  string   `yaml:"message"`
	Enabled  bool     `yaml:"enabled"`
}

// JoinAnnouncement represents a join announcement.
type JoinAnnouncement struct {
	Channel string `yaml:"channel"`
	Message string `yaml:"message"`
	Enabled bool   `yaml:"enabled"`
}

// Legacy configuration types for backward compatibility

// AuthConfig contains authentication settings (legacy).
type AuthConfig struct {
	Enabled        bool     `yaml:"enabled"`
	SuperAdminHash string   `yaml:"super-admin-hash"`
	MaxFailures    int      `yaml:"max-failures"`
	BanDuration    int      `yaml:"ban-duration"`
	AllowedIPs     []string `yaml:"allowed-ips"`
}

// ChannelsConfig contains channel system settings (legacy).
type ChannelsConfig struct {
	DefaultChannel string                   `yaml:"default-channel"`
	Templates      map[string]ChannelConfig `yaml:"templates"`
}

// ChannelConfig represents a channel template configuration (legacy).
type ChannelConfig struct {
	DisplayName   string   `yaml:"display-name"`
	Scope         string   `yaml:"scope"` // GLOBAL, SERVER, PRIVATE
	Permission    string   `yaml:"permission"`
	MaxCapacity   int      `yaml:"max-capacity"`
	AllowedWorlds []string `yaml:"allowed-worlds"`
	Format        string   `yaml:"format"`
}

// WebSocketConfig contains WebSocket gateway settings (legacy).
type WebSocketConfig struct {
	Enabled bool   `yaml:"enabled"`
	Host    string `yaml:"host"`
	Port    int    `yaml:"port"`
	Path    string `yaml:"path"`
}

// ModerationConfig contains moderation settings (legacy).
type ModerationConfig struct {
	SensitiveWordsFile string `yaml:"sensitive-words-file"`
	MuteEnabled        bool   `yaml:"mute-enabled"`
	FilterEnabled      bool   `yaml:"filter-enabled"`
}

// ConfigLoader provides methods for loading and managing configuration.
// This implementation is compatible with the Java version's ConfigLoader.
// Requirements: 12.3, 19.1 - Configuration format compatibility with Java version
type ConfigLoader struct {
	configPath      string
	config          *Config
	lastModified    int64
	originalContent string
}

// NewConfigLoader creates a new ConfigLoader instance.
func NewConfigLoader(configPath string) *ConfigLoader {
	return &ConfigLoader{
		configPath: configPath,
	}
}

// Load reads and parses the configuration file.
// If the file doesn't exist, creates a default configuration.
// Auto-completes missing fields with defaults.
func (cl *ConfigLoader) Load() (*Config, error) {
	cfg, err := Load(cl.configPath)
	if err != nil {
		return nil, err
	}
	cl.config = cfg

	// Track file modification time for hot-reload detection
	if info, err := os.Stat(cl.configPath); err == nil {
		cl.lastModified = info.ModTime().UnixMilli()
	}

	return cfg, nil
}

// Reload reloads the configuration from disk.
func (cl *ConfigLoader) Reload() (*Config, error) {
	return cl.Load()
}

// GetConfig returns the currently loaded configuration.
func (cl *ConfigLoader) GetConfig() *Config {
	return cl.config
}

// SaveDefault saves the default configuration to the specified path.
func (cl *ConfigLoader) SaveDefault() error {
	cfg := DefaultConfig()
	return cfg.Save(cl.configPath)
}

// HasFileChanged checks if the configuration file has been modified since last load.
// This is useful for implementing hot-reload functionality.
func (cl *ConfigLoader) HasFileChanged() bool {
	info, err := os.Stat(cl.configPath)
	if err != nil {
		return false
	}
	return info.ModTime().UnixMilli() > cl.lastModified
}

// GetConfigPath returns the configuration file path.
func (cl *ConfigLoader) GetConfigPath() string {
	return cl.configPath
}

// Load reads and parses the configuration file from the given path.
func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		// Return default config if file doesn't exist
		if os.IsNotExist(err) {
			return DefaultConfig(), nil
		}
		return nil, fmt.Errorf("failed to read config file: %w", err)
	}

	var cfg Config
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("failed to parse config file: %w", err)
	}

	// Normalize first (merge legacy fields), then apply defaults
	cfg.normalize()
	cfg.applyDefaults()

	return &cfg, nil
}


// applyDefaults fills in missing values with sensible defaults.
// This should be called AFTER normalize() to avoid overwriting legacy values.
func (c *Config) applyDefaults() {
	// Server defaults
	if c.Server.Port == 0 {
		c.Server.Port = 8888
	}
	if c.Server.BindAddress == "" && c.Server.Host == "" {
		c.Server.BindAddress = "0.0.0.0"
	}
	if c.Server.WebSocketPort == 0 {
		c.Server.WebSocketPort = 8889
	}
	if c.Server.WorkerThreads == 0 {
		c.Server.WorkerThreads = 4
	}

	// Security defaults
	if c.Security.MaxAuthFailures == 0 {
		c.Security.MaxAuthFailures = 3
	}
	if c.Security.IPBanDuration == 0 {
		c.Security.IPBanDuration = 300
	}

	// Database defaults
	if c.Database.Type == "" {
		c.Database.Type = "memory"
	}
	if c.Database.MySQL.Port == 0 {
		c.Database.MySQL.Port = 3306
	}
	if c.Database.MySQL.PoolSize == 0 {
		c.Database.MySQL.PoolSize = 10
	}
	if c.Database.Redis.Port == 0 {
		c.Database.Redis.Port = 6379
	}

	// Filter defaults
	if c.Filter.Replacement == "" {
		c.Filter.Replacement = "***"
	}

	// Legacy auth defaults
	if c.Auth.MaxFailures == 0 {
		c.Auth.MaxFailures = 3
	}
	if c.Auth.BanDuration == 0 {
		c.Auth.BanDuration = 300
	}

	// Legacy database defaults
	if c.Database.PoolSize == 0 {
		c.Database.PoolSize = 10
	}

	// Legacy websocket defaults
	if c.WebSocket.Port == 0 {
		c.WebSocket.Port = 8889
	}
	if c.WebSocket.Path == "" {
		c.WebSocket.Path = "/ws"
	}
}

// normalize merges legacy fields into new structure.
func (c *Config) normalize() {
	// Normalize server host
	if c.Server.BindAddress == "" && c.Server.Host != "" {
		c.Server.BindAddress = c.Server.Host
	}
	if c.Server.Host == "" && c.Server.BindAddress != "" {
		c.Server.Host = c.Server.BindAddress
	}

	// Normalize database config - merge legacy flat fields into MySQL config
	if c.Database.MySQL.Host == "" && c.Database.Host != "" {
		c.Database.MySQL.Host = c.Database.Host
	}
	if c.Database.MySQL.Port == 0 && c.Database.DBPort != 0 {
		c.Database.MySQL.Port = c.Database.DBPort
	}
	if c.Database.MySQL.Database == "" && c.Database.Database != "" {
		c.Database.MySQL.Database = c.Database.Database
	}
	if c.Database.MySQL.Username == "" && c.Database.Username != "" {
		c.Database.MySQL.Username = c.Database.Username
	}
	if c.Database.MySQL.Password == "" && c.Database.Password != "" {
		c.Database.MySQL.Password = c.Database.Password
	}
	if c.Database.MySQL.PoolSize == 0 && c.Database.PoolSize != 0 {
		c.Database.MySQL.PoolSize = c.Database.PoolSize
	}

	// Normalize Redis config from database.redis to top-level redis
	if !c.Redis.Enabled && c.Database.Redis.Enabled {
		c.Redis = c.Database.Redis
	}

	// Normalize security from legacy auth
	if len(c.Security.AllowedIPs) == 0 && len(c.Auth.AllowedIPs) > 0 {
		c.Security.AllowedIPs = c.Auth.AllowedIPs
	}
	if c.Security.MaxAuthFailures == 0 && c.Auth.MaxFailures > 0 {
		c.Security.MaxAuthFailures = c.Auth.MaxFailures
	}
	if c.Security.IPBanDuration == 0 && c.Auth.BanDuration > 0 {
		c.Security.IPBanDuration = c.Auth.BanDuration
	}

	// Normalize filter from legacy moderation
	if !c.Filter.Enabled && c.Moderation.FilterEnabled {
		c.Filter.Enabled = c.Moderation.FilterEnabled
	}

	// Normalize websocket from server
	if !c.WebSocket.Enabled && c.Server.WebSocketPort > 0 {
		c.WebSocket.Port = c.Server.WebSocketPort
	}
}

// DefaultConfig returns a configuration with sensible defaults.
func DefaultConfig() *Config {
	return &Config{
		Server: ServerConfig{
			BindAddress:   "0.0.0.0",
			Host:          "0.0.0.0",
			Port:          8888,
			WebSocketPort: 8889,
			SecretKey:     "change-this-to-a-secure-random-string",
			WorkerThreads: 4,
		},
		Database: DatabaseConfig{
			Type: "memory",
			MySQL: MySQLConfig{
				Host:     "127.0.0.1",
				Port:     3306,
				Database: "novalink",
				Username: "root",
				Password: "",
				PoolSize: 10,
			},
			Redis: RedisConfig{
				Enabled:  false,
				Host:     "127.0.0.1",
				Port:     6379,
				Password: "",
				Database: 0,
			},
		},
		Security: SecurityConfig{
			AllowedIPs:      []string{"127.0.0.1"},
			IPBanDuration:   300,
			MaxAuthFailures: 3,
		},
		SuperAdmins: []SuperAdminConfig{},
		GlobalChannels: map[string]GlobalChannel{
			"global": {
				DisplayName: "Global",
				Permission:  "novachat.channel.global",
				MaxCapacity: 0,
			},
		},
		Templates: map[string]ChannelTemplate{
			"standard_local": {
				DisplayName: "Local",
				Scope:       "SERVER",
				MaxCapacity: 100,
			},
			"private_template": {
				DisplayName: "Private",
				Scope:       "PRIVATE",
				MaxCapacity: 20,
			},
		},
		Clients:  []ClientConfig{},
		Webhooks: []WebhookConfig{},
		Filter: FilterConfig{
			Enabled:     true,
			CustomWords: []string{},
			Patterns:    []string{},
			Replacement: "***",
		},
		Announcements: AnnouncementsConfig{
			Scheduled: []ScheduledAnnouncement{},
			Join:      []JoinAnnouncement{},
		},
		Debug: false,

		// Legacy fields
		Auth: AuthConfig{
			Enabled:     true,
			MaxFailures: 3,
			BanDuration: 300,
		},
		Redis: RedisConfig{
			Enabled: false,
		},
		Channels: ChannelsConfig{
			DefaultChannel: "global",
			Templates: map[string]ChannelConfig{
				"global": {
					DisplayName: "全服频道",
					Scope:       "GLOBAL",
					MaxCapacity: 0,
					Format:      "&c[全服] &7{player}&f: {message}",
				},
				"local": {
					DisplayName: "本地频道",
					Scope:       "SERVER",
					MaxCapacity: 0,
					Format:      "&e[本地] &7{player}&f: {message}",
				},
			},
		},
		WebSocket: WebSocketConfig{
			Enabled: false,
			Host:    "0.0.0.0",
			Port:    8889,
			Path:    "/ws",
		},
		Moderation: ModerationConfig{
			SensitiveWordsFile: "sensitive_words.txt",
			MuteEnabled:        true,
			FilterEnabled:      true,
		},
	}
}


// Save writes the configuration to the given path.
func (c *Config) Save(path string) error {
	// Ensure directory exists
	dir := filepath.Dir(path)
	if dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0755); err != nil {
			return fmt.Errorf("failed to create config directory: %w", err)
		}
	}

	data, err := yaml.Marshal(c)
	if err != nil {
		return fmt.Errorf("failed to marshal config: %w", err)
	}

	if err := os.WriteFile(path, data, 0644); err != nil {
		return fmt.Errorf("failed to write config file: %w", err)
	}

	return nil
}

// SaveWithComments writes the configuration to the given path with header comments.
// This produces output similar to the Java version's default config file.
func (c *Config) SaveWithComments(path string) error {
	// Ensure directory exists
	dir := filepath.Dir(path)
	if dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0755); err != nil {
			return fmt.Errorf("failed to create config directory: %w", err)
		}
	}

	data, err := yaml.Marshal(c)
	if err != nil {
		return fmt.Errorf("failed to marshal config: %w", err)
	}

	// Add header comments (compatible with Java version)
	header := `# ==========================================
# NovaLink Configuration / NovaLink 配置文件
# ==========================================
# This configuration format is compatible with the Java version's novalink.yml.
# 此配置格式与 Java 版本的 novalink.yml 兼容。

`
	content := header + string(data)

	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		return fmt.Errorf("failed to write config file: %w", err)
	}

	return nil
}

// Validate checks the configuration for errors.
func (c *Config) Validate() error {
	if c.Server.Port <= 0 || c.Server.Port > 65535 {
		return fmt.Errorf("invalid server port: %d", c.Server.Port)
	}

	if c.Database.Type != "memory" && c.Database.Type != "mysql" && c.Database.Type != "redis" {
		return fmt.Errorf("invalid database type: %s (must be memory, mysql, or redis)", c.Database.Type)
	}

	if c.Database.Type == "mysql" {
		if c.Database.MySQL.Host == "" {
			return fmt.Errorf("mysql host is required when database type is mysql")
		}
		if c.Database.MySQL.Database == "" {
			return fmt.Errorf("mysql database name is required when database type is mysql")
		}
	}

	// Validate channel templates
	for name, tmpl := range c.Templates {
		if tmpl.Scope != "" && tmpl.Scope != "GLOBAL" && tmpl.Scope != "SERVER" && tmpl.Scope != "PRIVATE" {
			return fmt.Errorf("invalid scope '%s' for template '%s' (must be GLOBAL, SERVER, or PRIVATE)", tmpl.Scope, name)
		}
	}

	// Validate global channels
	for name, ch := range c.GlobalChannels {
		if ch.MaxCapacity < 0 {
			return fmt.Errorf("invalid max_capacity %d for global channel '%s'", ch.MaxCapacity, name)
		}
	}

	// Validate clients
	for i, client := range c.Clients {
		if client.Username == "" {
			return fmt.Errorf("client at index %d has empty username", i)
		}
	}

	// Validate webhooks
	for i, webhook := range c.Webhooks {
		if webhook.Enabled && webhook.URL == "" {
			return fmt.Errorf("webhook at index %d is enabled but has no URL", i)
		}
	}

	return nil
}

// GetServerAddress returns the server bind address.
func (c *Config) GetServerAddress() string {
	if c.Server.BindAddress != "" {
		return c.Server.BindAddress
	}
	return c.Server.Host
}

// GetMySQLConfig returns the MySQL configuration, merging legacy fields.
func (c *Config) GetMySQLConfig() MySQLConfig {
	cfg := c.Database.MySQL

	// Fall back to legacy flat fields if nested config is empty
	if cfg.Host == "" && c.Database.Host != "" {
		cfg.Host = c.Database.Host
	}
	if cfg.Port == 0 && c.Database.DBPort != 0 {
		cfg.Port = c.Database.DBPort
	}
	if cfg.Database == "" && c.Database.Database != "" {
		cfg.Database = c.Database.Database
	}
	if cfg.Username == "" && c.Database.Username != "" {
		cfg.Username = c.Database.Username
	}
	if cfg.Password == "" && c.Database.Password != "" {
		cfg.Password = c.Database.Password
	}
	if cfg.PoolSize == 0 && c.Database.PoolSize != 0 {
		cfg.PoolSize = c.Database.PoolSize
	}

	return cfg
}

// GetRedisConfig returns the Redis configuration, merging from database.redis.
func (c *Config) GetRedisConfig() RedisConfig {
	if c.Redis.Enabled {
		return c.Redis
	}
	return c.Database.Redis
}

// IsAuthEnabled returns whether authentication is enabled.
func (c *Config) IsAuthEnabled() bool {
	return c.Auth.Enabled
}

// GetMaxAuthFailures returns the maximum authentication failures before IP ban.
func (c *Config) GetMaxAuthFailures() int {
	if c.Security.MaxAuthFailures > 0 {
		return c.Security.MaxAuthFailures
	}
	return c.Auth.MaxFailures
}

// GetIPBanDuration returns the IP ban duration in seconds.
func (c *Config) GetIPBanDuration() int {
	if c.Security.IPBanDuration > 0 {
		return c.Security.IPBanDuration
	}
	return c.Auth.BanDuration
}

// GetAllowedIPs returns the list of allowed IP addresses.
func (c *Config) GetAllowedIPs() []string {
	if len(c.Security.AllowedIPs) > 0 {
		return c.Security.AllowedIPs
	}
	return c.Auth.AllowedIPs
}

// GetClientByUsername finds a client configuration by username.
func (c *Config) GetClientByUsername(username string) *ClientConfig {
	for i := range c.Clients {
		if c.Clients[i].Username == username {
			return &c.Clients[i]
		}
	}
	return nil
}

// GetChannelTemplate finds a channel template by name.
func (c *Config) GetChannelTemplate(name string) *ChannelTemplate {
	if tmpl, ok := c.Templates[name]; ok {
		return &tmpl
	}
	return nil
}

// GetGlobalChannel finds a global channel by name.
func (c *Config) GetGlobalChannel(name string) *GlobalChannel {
	if ch, ok := c.GlobalChannels[name]; ok {
		return &ch
	}
	return nil
}

// GetEnabledWebhooks returns all enabled webhooks.
func (c *Config) GetEnabledWebhooks() []WebhookConfig {
	var enabled []WebhookConfig
	for _, wh := range c.Webhooks {
		if wh.Enabled {
			enabled = append(enabled, wh)
		}
	}
	return enabled
}

// GetWebhooksForEvent returns webhooks that are subscribed to a specific event.
func (c *Config) GetWebhooksForEvent(event string) []WebhookConfig {
	var matching []WebhookConfig
	for _, wh := range c.Webhooks {
		if !wh.Enabled {
			continue
		}
		for _, e := range wh.Events {
			if e == event {
				matching = append(matching, wh)
				break
			}
		}
	}
	return matching
}

// GetEnabledScheduledAnnouncements returns all enabled scheduled announcements.
func (c *Config) GetEnabledScheduledAnnouncements() []ScheduledAnnouncement {
	var enabled []ScheduledAnnouncement
	for _, ann := range c.Announcements.Scheduled {
		if ann.Enabled {
			enabled = append(enabled, ann)
		}
	}
	return enabled
}

// GetEnabledJoinAnnouncements returns all enabled join announcements.
func (c *Config) GetEnabledJoinAnnouncements() []JoinAnnouncement {
	var enabled []JoinAnnouncement
	for _, ann := range c.Announcements.Join {
		if ann.Enabled {
			enabled = append(enabled, ann)
		}
	}
	return enabled
}
