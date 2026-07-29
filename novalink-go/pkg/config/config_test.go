// Package config provides configuration loading and management for NovaLink-Go.
package config

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestDefaultConfig(t *testing.T) {
	cfg := DefaultConfig()

	if cfg.Server.Port != 8888 {
		t.Errorf("expected default server port 8888, got %d", cfg.Server.Port)
	}

	if cfg.Server.BindAddress != "0.0.0.0" {
		t.Errorf("expected default bind address 0.0.0.0, got %s", cfg.Server.BindAddress)
	}

	if cfg.Database.Type != "memory" {
		t.Errorf("expected default database type memory, got %s", cfg.Database.Type)
	}

	if cfg.Security.MaxAuthFailures != 3 {
		t.Errorf("expected default max auth failures 3, got %d", cfg.Security.MaxAuthFailures)
	}

	if cfg.Security.IPBanDuration != 300 {
		t.Errorf("expected default IP ban duration 300, got %d", cfg.Security.IPBanDuration)
	}

	if cfg.Filter.Replacement != "***" {
		t.Errorf("expected default filter replacement ***, got %s", cfg.Filter.Replacement)
	}
}

func TestLoadNonExistentFile(t *testing.T) {
	cfg, err := Load("nonexistent.yml")
	if err != nil {
		t.Fatalf("expected no error for nonexistent file, got %v", err)
	}

	// Should return default config
	if cfg.Server.Port != 8888 {
		t.Errorf("expected default server port 8888, got %d", cfg.Server.Port)
	}
}

func TestLoadAndSave(t *testing.T) {
	// Create a temporary directory
	tmpDir, err := os.MkdirTemp("", "config_test")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	configPath := filepath.Join(tmpDir, "test_config.yml")

	// Create and save a config
	original := DefaultConfig()
	original.Server.Port = 9999
	original.Debug = true
	original.Database.Type = "mysql"
	original.Database.MySQL.Host = "testhost"
	original.Database.MySQL.Database = "testdb"

	if err := original.Save(configPath); err != nil {
		t.Fatalf("failed to save config: %v", err)
	}

	// Load the config back
	loaded, err := Load(configPath)
	if err != nil {
		t.Fatalf("failed to load config: %v", err)
	}

	if loaded.Server.Port != 9999 {
		t.Errorf("expected server port 9999, got %d", loaded.Server.Port)
	}

	if !loaded.Debug {
		t.Error("expected debug to be true")
	}

	if loaded.Database.Type != "mysql" {
		t.Errorf("expected database type mysql, got %s", loaded.Database.Type)
	}

	if loaded.Database.MySQL.Host != "testhost" {
		t.Errorf("expected mysql host testhost, got %s", loaded.Database.MySQL.Host)
	}
}

func TestConfigValidation(t *testing.T) {
	tests := []struct {
		name    string
		modify  func(*Config)
		wantErr bool
	}{
		{
			name:    "valid default config",
			modify:  func(c *Config) {},
			wantErr: false,
		},
		{
			name: "invalid server port - negative",
			modify: func(c *Config) {
				c.Server.Port = -1
			},
			wantErr: true,
		},
		{
			name: "invalid server port - too high",
			modify: func(c *Config) {
				c.Server.Port = 70000
			},
			wantErr: true,
		},
		{
			name: "invalid database type",
			modify: func(c *Config) {
				c.Database.Type = "invalid"
			},
			wantErr: true,
		},
		{
			name: "mysql without host",
			modify: func(c *Config) {
				c.Database.Type = "mysql"
				c.Database.MySQL.Host = ""
				c.Database.Host = ""
			},
			wantErr: true,
		},
		{
			name: "mysql without database",
			modify: func(c *Config) {
				c.Database.Type = "mysql"
				c.Database.MySQL.Host = "localhost"
				c.Database.MySQL.Database = ""
				c.Database.Database = ""
			},
			wantErr: true,
		},
		{
			name: "invalid template scope",
			modify: func(c *Config) {
				c.Templates["test"] = ChannelTemplate{
					DisplayName: "Test",
					Scope:       "INVALID",
				}
			},
			wantErr: true,
		},
		{
			name: "client without username",
			modify: func(c *Config) {
				c.Clients = []ClientConfig{{Username: ""}}
			},
			wantErr: true,
		},
		{
			name: "enabled webhook without URL",
			modify: func(c *Config) {
				c.Webhooks = []WebhookConfig{{Enabled: true, URL: ""}}
			},
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cfg := DefaultConfig()
			tt.modify(cfg)
			err := cfg.Validate()
			if (err != nil) != tt.wantErr {
				t.Errorf("Validate() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}

func TestConfigLoader(t *testing.T) {
	// Create a temporary directory
	tmpDir, err := os.MkdirTemp("", "config_loader_test")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	configPath := filepath.Join(tmpDir, "test_config.yml")

	// Test ConfigLoader
	loader := NewConfigLoader(configPath)

	// Save default config
	if err := loader.SaveDefault(); err != nil {
		t.Fatalf("failed to save default config: %v", err)
	}

	// Load config
	cfg, err := loader.Load()
	if err != nil {
		t.Fatalf("failed to load config: %v", err)
	}

	if cfg.Server.Port != 8888 {
		t.Errorf("expected server port 8888, got %d", cfg.Server.Port)
	}

	// Test GetConfig
	if loader.GetConfig() != cfg {
		t.Error("GetConfig should return the loaded config")
	}

	// Test Reload
	cfg2, err := loader.Reload()
	if err != nil {
		t.Fatalf("failed to reload config: %v", err)
	}

	if cfg2.Server.Port != 8888 {
		t.Errorf("expected server port 8888 after reload, got %d", cfg2.Server.Port)
	}
}

func TestGetClientByUsername(t *testing.T) {
	cfg := DefaultConfig()
	cfg.Clients = []ClientConfig{
		{Username: "Server1", DisplayName: "Server One"},
		{Username: "Server2", DisplayName: "Server Two"},
	}

	client := cfg.GetClientByUsername("Server1")
	if client == nil {
		t.Fatal("expected to find Server1")
	}
	if client.DisplayName != "Server One" {
		t.Errorf("expected display name 'Server One', got '%s'", client.DisplayName)
	}

	client = cfg.GetClientByUsername("NonExistent")
	if client != nil {
		t.Error("expected nil for non-existent client")
	}
}

func TestGetChannelTemplate(t *testing.T) {
	cfg := DefaultConfig()

	tmpl := cfg.GetChannelTemplate("standard_local")
	if tmpl == nil {
		t.Fatal("expected to find standard_local template")
	}
	if tmpl.Scope != "SERVER" {
		t.Errorf("expected scope SERVER, got %s", tmpl.Scope)
	}

	tmpl = cfg.GetChannelTemplate("nonexistent")
	if tmpl != nil {
		t.Error("expected nil for non-existent template")
	}
}

func TestGetGlobalChannel(t *testing.T) {
	cfg := DefaultConfig()

	ch := cfg.GetGlobalChannel("global")
	if ch == nil {
		t.Fatal("expected to find global channel")
	}
	if ch.DisplayName != "Global" {
		t.Errorf("expected display name 'Global', got '%s'", ch.DisplayName)
	}

	ch = cfg.GetGlobalChannel("nonexistent")
	if ch != nil {
		t.Error("expected nil for non-existent channel")
	}
}

func TestGetWebhooksForEvent(t *testing.T) {
	cfg := DefaultConfig()
	cfg.Webhooks = []WebhookConfig{
		{URL: "http://example1.com", Events: []string{"message", "join"}, Enabled: true},
		{URL: "http://example2.com", Events: []string{"message"}, Enabled: true},
		{URL: "http://example3.com", Events: []string{"leave"}, Enabled: false},
	}

	webhooks := cfg.GetWebhooksForEvent("message")
	if len(webhooks) != 2 {
		t.Errorf("expected 2 webhooks for message event, got %d", len(webhooks))
	}

	webhooks = cfg.GetWebhooksForEvent("join")
	if len(webhooks) != 1 {
		t.Errorf("expected 1 webhook for join event, got %d", len(webhooks))
	}

	webhooks = cfg.GetWebhooksForEvent("leave")
	if len(webhooks) != 0 {
		t.Errorf("expected 0 webhooks for leave event (disabled), got %d", len(webhooks))
	}
}

func TestGetEnabledAnnouncements(t *testing.T) {
	cfg := DefaultConfig()
	cfg.Announcements.Scheduled = []ScheduledAnnouncement{
		{ID: "ann1", Enabled: true},
		{ID: "ann2", Enabled: false},
		{ID: "ann3", Enabled: true},
	}
	cfg.Announcements.Join = []JoinAnnouncement{
		{Channel: "global", Enabled: true},
		{Channel: "local", Enabled: false},
	}

	scheduled := cfg.GetEnabledScheduledAnnouncements()
	if len(scheduled) != 2 {
		t.Errorf("expected 2 enabled scheduled announcements, got %d", len(scheduled))
	}

	join := cfg.GetEnabledJoinAnnouncements()
	if len(join) != 1 {
		t.Errorf("expected 1 enabled join announcement, got %d", len(join))
	}
}

func TestNormalizeLegacyConfig(t *testing.T) {
	// Create a config with legacy fields
	cfg := &Config{
		Server: ServerConfig{
			Host: "192.168.1.1",
			Port: 8888,
		},
		Auth: AuthConfig{
			Enabled:     true,
			MaxFailures: 5,
			BanDuration: 600,
			AllowedIPs:  []string{"10.0.0.0/8"},
		},
		Database: DatabaseConfig{
			Type:     "mysql",
			Host:     "dbhost",
			DBPort:   3307,
			Database: "mydb",
			Username: "user",
			Password: "pass",
			PoolSize: 20,
		},
	}

	// Normalize first (merge legacy fields), then apply defaults
	// This is the same order as Load() uses
	cfg.normalize()
	cfg.applyDefaults()

	// Check that legacy fields are normalized
	if cfg.Server.BindAddress != "192.168.1.1" {
		t.Errorf("expected bind address to be normalized from host, got %s", cfg.Server.BindAddress)
	}

	if cfg.Security.MaxAuthFailures != 5 {
		t.Errorf("expected security max auth failures to be normalized from auth, got %d", cfg.Security.MaxAuthFailures)
	}

	if cfg.Security.IPBanDuration != 600 {
		t.Errorf("expected security IP ban duration to be normalized from auth, got %d", cfg.Security.IPBanDuration)
	}

	mysqlCfg := cfg.GetMySQLConfig()
	if mysqlCfg.Host != "dbhost" {
		t.Errorf("expected mysql host to be normalized, got %s", mysqlCfg.Host)
	}
	if mysqlCfg.Port != 3307 {
		t.Errorf("expected mysql port to be normalized, got %d", mysqlCfg.Port)
	}
}


func TestConfigLoaderHasFileChanged(t *testing.T) {
	// Create a temporary directory
	tmpDir, err := os.MkdirTemp("", "config_change_test")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	configPath := filepath.Join(tmpDir, "test_config.yml")

	// Create loader and save default config
	loader := NewConfigLoader(configPath)
	if err := loader.SaveDefault(); err != nil {
		t.Fatalf("failed to save default config: %v", err)
	}

	// Load config
	if _, err := loader.Load(); err != nil {
		t.Fatalf("failed to load config: %v", err)
	}

	// File should not have changed immediately after load
	if loader.HasFileChanged() {
		t.Error("expected HasFileChanged to return false immediately after load")
	}

	// Wait a bit to ensure file modification time will be different
	// (some file systems have 1-second resolution for modification times)
	time.Sleep(100 * time.Millisecond)

	// Modify the file by touching it with a new modification time
	cfg := loader.GetConfig()
	cfg.Server.Port = 9999
	if err := cfg.Save(configPath); err != nil {
		t.Fatalf("failed to save modified config: %v", err)
	}

	// Touch the file to ensure modification time is updated
	now := time.Now().Add(time.Second)
	if err := os.Chtimes(configPath, now, now); err != nil {
		t.Fatalf("failed to update file modification time: %v", err)
	}

	// File should now be detected as changed
	if !loader.HasFileChanged() {
		t.Error("expected HasFileChanged to return true after file modification")
	}

	// After reload, HasFileChanged should return false again
	if _, err := loader.Reload(); err != nil {
		t.Fatalf("failed to reload config: %v", err)
	}
	if loader.HasFileChanged() {
		t.Error("expected HasFileChanged to return false after reload")
	}
}

func TestConfigLoaderGetConfigPath(t *testing.T) {
	loader := NewConfigLoader("/path/to/config.yml")
	if loader.GetConfigPath() != "/path/to/config.yml" {
		t.Errorf("expected config path '/path/to/config.yml', got '%s'", loader.GetConfigPath())
	}
}

func TestSaveWithComments(t *testing.T) {
	// Create a temporary directory
	tmpDir, err := os.MkdirTemp("", "config_comments_test")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	configPath := filepath.Join(tmpDir, "test_config.yml")

	// Create and save a config with comments
	cfg := DefaultConfig()
	if err := cfg.SaveWithComments(configPath); err != nil {
		t.Fatalf("failed to save config with comments: %v", err)
	}

	// Read the file and verify it has comments
	data, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatalf("failed to read config file: %v", err)
	}

	content := string(data)
	if !contains(content, "# ==========================================") {
		t.Error("expected config file to contain header comment separator")
	}
	if !contains(content, "NovaLink Configuration") {
		t.Error("expected config file to contain 'NovaLink Configuration' in header")
	}

	// Verify the config can still be loaded
	loaded, err := Load(configPath)
	if err != nil {
		t.Fatalf("failed to load config with comments: %v", err)
	}

	if loaded.Server.Port != 8888 {
		t.Errorf("expected server port 8888, got %d", loaded.Server.Port)
	}
}

func contains(s, substr string) bool {
	return len(s) >= len(substr) && (s == substr || len(s) > 0 && containsHelper(s, substr))
}

func containsHelper(s, substr string) bool {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}

func TestLoadActualConfigFile(t *testing.T) {
	// Test loading the actual novalink.yml file
	cfg, err := Load("../../novalink.yml")
	if err != nil {
		t.Fatalf("failed to load actual config file: %v", err)
	}

	// Verify some expected values from the actual config
	if cfg.Server.Port != 8888 {
		t.Errorf("expected server port 8888, got %d", cfg.Server.Port)
	}

	if cfg.Server.BindAddress != "0.0.0.0" {
		t.Errorf("expected bind address 0.0.0.0, got %s", cfg.Server.BindAddress)
	}

	if cfg.Database.Type != "memory" {
		t.Errorf("expected database type memory, got %s", cfg.Database.Type)
	}

	// Verify global channels are loaded
	if len(cfg.GlobalChannels) == 0 {
		t.Error("expected global channels to be loaded")
	}

	globalCh := cfg.GetGlobalChannel("global")
	if globalCh == nil {
		t.Error("expected to find global channel")
	} else if globalCh.DisplayName != "Global" {
		t.Errorf("expected global channel display name 'Global', got '%s'", globalCh.DisplayName)
	}

	// Verify templates are loaded
	if len(cfg.Templates) == 0 {
		t.Error("expected templates to be loaded")
	}

	localTmpl := cfg.GetChannelTemplate("standard_local")
	if localTmpl == nil {
		t.Error("expected to find standard_local template")
	} else if localTmpl.Scope != "SERVER" {
		t.Errorf("expected standard_local scope SERVER, got %s", localTmpl.Scope)
	}

	// Verify filter settings
	if !cfg.Filter.Enabled {
		t.Error("expected filter to be enabled")
	}

	if cfg.Filter.Replacement != "***" {
		t.Errorf("expected filter replacement ***, got %s", cfg.Filter.Replacement)
	}

	// Validate the config
	if err := cfg.Validate(); err != nil {
		t.Errorf("config validation failed: %v", err)
	}
}

// TestJavaCompatibleConfigFormat tests that the Go version can parse a config file
// that matches the Java version's format exactly.
// This ensures Requirements 12.3 and 19.1 are satisfied.
func TestJavaCompatibleConfigFormat(t *testing.T) {
	// Create a config string that matches the Java version's format
	javaFormatConfig := `
server:
  bind-address: "192.168.1.100"
  port: 9999
  websocket-port: 9998
  secret-key: "test-secret-key"
  worker-threads: 8

database:
  type: mysql
  mysql:
    host: "db.example.com"
    port: 3307
    database: "novalink_test"
    username: "testuser"
    password: "testpass"
    pool-size: 20
  redis:
    enabled: true
    host: "redis.example.com"
    port: 6380
    password: "redispass"
    database: 1

security:
  allowed-ips:
    - "127.0.0.1"
    - "192.168.0.0/16"
  ip-ban-duration: 600
  max-auth-failures: 5

super-admins:
  - uuid: "12345678-1234-1234-1234-123456789012"
    password-hash: "abcdef1234567890"

debug: true

global_channels:
  global:
    display_name: "全服频道"
    permission: "novachat.channel.global"
    max_capacity: 1000
  staff:
    display_name: "员工频道"
    permission: "novachat.channel.staff"
    max_capacity: 50

templates:
  standard_local:
    display_name: "本地频道"
    scope: SERVER
    max_capacity: 100
  private_room:
    display_name: "私人房间"
    scope: PRIVATE
    max_capacity: 10
    permission: "novachat.private"

clients:
  - username: "Survival_Server"
    password: "hashed_password_here"
    display_name: "生存服"
    channels:
      local:
        use_template: "standard_local"
        display_name: "生存本地"
      mining:
        display_name: "矿区频道"
        scope: SERVER
        max_capacity: 50
        allowed_worlds:
          - "mining_world"
          - "mining_nether"
`

	// Create a temporary file with this config
	tmpDir, err := os.MkdirTemp("", "java_compat_test")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	configPath := filepath.Join(tmpDir, "java_format.yml")
	if err := os.WriteFile(configPath, []byte(javaFormatConfig), 0644); err != nil {
		t.Fatalf("failed to write test config: %v", err)
	}

	// Load the config
	cfg, err := Load(configPath)
	if err != nil {
		t.Fatalf("failed to load Java-format config: %v", err)
	}

	// Verify server settings
	if cfg.Server.BindAddress != "192.168.1.100" {
		t.Errorf("expected bind-address '192.168.1.100', got '%s'", cfg.Server.BindAddress)
	}
	if cfg.Server.Port != 9999 {
		t.Errorf("expected port 9999, got %d", cfg.Server.Port)
	}
	if cfg.Server.WebSocketPort != 9998 {
		t.Errorf("expected websocket-port 9998, got %d", cfg.Server.WebSocketPort)
	}
	if cfg.Server.SecretKey != "test-secret-key" {
		t.Errorf("expected secret-key 'test-secret-key', got '%s'", cfg.Server.SecretKey)
	}
	if cfg.Server.WorkerThreads != 8 {
		t.Errorf("expected worker-threads 8, got %d", cfg.Server.WorkerThreads)
	}

	// Verify database settings
	if cfg.Database.Type != "mysql" {
		t.Errorf("expected database type 'mysql', got '%s'", cfg.Database.Type)
	}
	if cfg.Database.MySQL.Host != "db.example.com" {
		t.Errorf("expected mysql host 'db.example.com', got '%s'", cfg.Database.MySQL.Host)
	}
	if cfg.Database.MySQL.Port != 3307 {
		t.Errorf("expected mysql port 3307, got %d", cfg.Database.MySQL.Port)
	}
	if cfg.Database.MySQL.Database != "novalink_test" {
		t.Errorf("expected mysql database 'novalink_test', got '%s'", cfg.Database.MySQL.Database)
	}
	if cfg.Database.MySQL.PoolSize != 20 {
		t.Errorf("expected mysql pool-size 20, got %d", cfg.Database.MySQL.PoolSize)
	}
	if !cfg.Database.Redis.Enabled {
		t.Error("expected redis to be enabled")
	}
	if cfg.Database.Redis.Host != "redis.example.com" {
		t.Errorf("expected redis host 'redis.example.com', got '%s'", cfg.Database.Redis.Host)
	}

	// Verify security settings
	if len(cfg.Security.AllowedIPs) != 2 {
		t.Errorf("expected 2 allowed IPs, got %d", len(cfg.Security.AllowedIPs))
	}
	if cfg.Security.IPBanDuration != 600 {
		t.Errorf("expected ip-ban-duration 600, got %d", cfg.Security.IPBanDuration)
	}
	if cfg.Security.MaxAuthFailures != 5 {
		t.Errorf("expected max-auth-failures 5, got %d", cfg.Security.MaxAuthFailures)
	}

	// Verify super-admins
	if len(cfg.SuperAdmins) != 1 {
		t.Errorf("expected 1 super-admin, got %d", len(cfg.SuperAdmins))
	} else {
		if cfg.SuperAdmins[0].UUID != "12345678-1234-1234-1234-123456789012" {
			t.Errorf("expected super-admin UUID '12345678-1234-1234-1234-123456789012', got '%s'", cfg.SuperAdmins[0].UUID)
		}
	}

	// Verify debug
	if !cfg.Debug {
		t.Error("expected debug to be true")
	}

	// Verify global channels
	if len(cfg.GlobalChannels) != 2 {
		t.Errorf("expected 2 global channels, got %d", len(cfg.GlobalChannels))
	}
	globalCh := cfg.GetGlobalChannel("global")
	if globalCh == nil {
		t.Error("expected to find 'global' channel")
	} else {
		if globalCh.DisplayName != "全服频道" {
			t.Errorf("expected global display_name '全服频道', got '%s'", globalCh.DisplayName)
		}
		if globalCh.MaxCapacity != 1000 {
			t.Errorf("expected global max_capacity 1000, got %d", globalCh.MaxCapacity)
		}
	}

	// Verify templates
	if len(cfg.Templates) != 2 {
		t.Errorf("expected 2 templates, got %d", len(cfg.Templates))
	}
	localTmpl := cfg.GetChannelTemplate("standard_local")
	if localTmpl == nil {
		t.Error("expected to find 'standard_local' template")
	} else {
		if localTmpl.Scope != "SERVER" {
			t.Errorf("expected standard_local scope 'SERVER', got '%s'", localTmpl.Scope)
		}
	}

	// Verify clients
	if len(cfg.Clients) != 1 {
		t.Errorf("expected 1 client, got %d", len(cfg.Clients))
	} else {
		client := cfg.Clients[0]
		if client.Username != "Survival_Server" {
			t.Errorf("expected client username 'Survival_Server', got '%s'", client.Username)
		}
		if client.DisplayName != "生存服" {
			t.Errorf("expected client display_name '生存服', got '%s'", client.DisplayName)
		}
		if len(client.Channels) != 2 {
			t.Errorf("expected 2 client channels, got %d", len(client.Channels))
		}
		if localCh, ok := client.Channels["local"]; ok {
			if localCh.UseTemplate != "standard_local" {
				t.Errorf("expected local channel use_template 'standard_local', got '%s'", localCh.UseTemplate)
			}
		} else {
			t.Error("expected to find 'local' channel in client")
		}
		if miningCh, ok := client.Channels["mining"]; ok {
			if len(miningCh.AllowedWorlds) != 2 {
				t.Errorf("expected 2 allowed_worlds for mining channel, got %d", len(miningCh.AllowedWorlds))
			}
		} else {
			t.Error("expected to find 'mining' channel in client")
		}
	}

	// Validate the config
	if err := cfg.Validate(); err != nil {
		t.Errorf("config validation failed: %v", err)
	}
}
