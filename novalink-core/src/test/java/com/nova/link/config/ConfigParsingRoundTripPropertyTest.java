package com.nova.link.config;

import com.nova.link.auth.SuperAdminCredentials;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for Configuration Parsing Round-Trip.
 * 
 * **Feature: starchat-starlink, Property 16: Configuration Parsing Round-Trip**
 * 
 * For any valid configuration, serializing to YAML and parsing back should 
 * produce an equivalent configuration object.
 * 
 * **Validates: Requirements 20.1-20.6**
 */
public class ConfigParsingRoundTripPropertyTest {

    /**
     * **Feature: starchat-starlink, Property 16: Configuration Parsing Round-Trip**
     * 
     * For any valid server configuration, serializing to YAML and parsing back 
     * should produce an equivalent configuration object.
     * 
     * **Validates: Requirements 20.1-20.6**
     */
    @Property(tries = 100)
    void serverConfigRoundTrip(
            @ForAll("validHostnames") String bindAddress,
            @ForAll @IntRange(min = 1024, max = 65535) int port,
            @ForAll @IntRange(min = 1024, max = 65535) int websocketPort,
            @ForAll("validSecretKeys") String secretKey,
            @ForAll @IntRange(min = 1, max = 16) int workerThreads
    ) throws Exception {
        // Ensure ports are different
        Assume.that(port != websocketPort);
        
        // Create config with server settings
        NovaLinkConfig original = NovaLinkConfig.createDefault();
        original.getServer().setBindAddress(bindAddress);
        original.getServer().setPort(port);
        original.getServer().setWebsocketPort(websocketPort);
        original.getServer().setSecretKey(secretKey);
        original.getServer().setWorkerThreads(workerThreads);
        
        // Round-trip through file
        NovaLinkConfig roundTripped = roundTripConfig(original);
        
        // PROPERTY: Server config should be equivalent after round-trip
        assertThat(roundTripped.getServer().getBindAddress())
                .as("Bind address should survive round-trip")
                .isEqualTo(bindAddress);
        
        assertThat(roundTripped.getServer().getPort())
                .as("Port should survive round-trip")
                .isEqualTo(port);
        
        assertThat(roundTripped.getServer().getWebsocketPort())
                .as("WebSocket port should survive round-trip")
                .isEqualTo(websocketPort);
        
        assertThat(roundTripped.getServer().getSecretKey())
                .as("Secret key should survive round-trip")
                .isEqualTo(secretKey);
        
        assertThat(roundTripped.getServer().getWorkerThreads())
                .as("Worker threads should survive round-trip")
                .isEqualTo(workerThreads);
    }

    @Provide
    Arbitrary<String> validHostnames() {
        return Arbitraries.of("0.0.0.0", "127.0.0.1", "192.168.1.1", "localhost", "10.0.0.1");
    }

    @Provide
    Arbitrary<String> validSecretKeys() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(8)
                .ofMaxLength(32);
    }

    /**
     * Property 16 (continued): Database configuration round-trip.
     * 
     * **Validates: Requirements 20.1-20.6**
     */
    @Property(tries = 100)
    void databaseConfigRoundTrip(
            @ForAll("databaseTypes") String dbType,
            @ForAll("validHostnames") String mysqlHost,
            @ForAll @IntRange(min = 1024, max = 65535) int mysqlPort,
            @ForAll("validIdentifiers") String mysqlDatabase,
            @ForAll("validIdentifiers") String mysqlUsername,
            @ForAll @IntRange(min = 1, max = 50) int poolSize,
            @ForAll boolean redisEnabled,
            @ForAll @IntRange(min = 1024, max = 65535) int redisPort
    ) throws Exception {
        // Create config with database settings
        NovaLinkConfig original = NovaLinkConfig.createDefault();
        original.getDatabase().setType(dbType);
        original.getDatabase().getMysql().setHost(mysqlHost);
        original.getDatabase().getMysql().setPort(mysqlPort);
        original.getDatabase().getMysql().setDatabase(mysqlDatabase);
        original.getDatabase().getMysql().setUsername(mysqlUsername);
        original.getDatabase().getMysql().setPoolSize(poolSize);
        original.getDatabase().getRedis().setEnabled(redisEnabled);
        original.getDatabase().getRedis().setPort(redisPort);
        
        // Round-trip through file
        NovaLinkConfig roundTripped = roundTripConfig(original);
        
        // PROPERTY: Database config should be equivalent after round-trip
        assertThat(roundTripped.getDatabase().getType())
                .as("Database type should survive round-trip")
                .isEqualTo(dbType);
        
        assertThat(roundTripped.getDatabase().getMysql().getHost())
                .as("MySQL host should survive round-trip")
                .isEqualTo(mysqlHost);
        
        assertThat(roundTripped.getDatabase().getMysql().getPort())
                .as("MySQL port should survive round-trip")
                .isEqualTo(mysqlPort);
        
        assertThat(roundTripped.getDatabase().getMysql().getDatabase())
                .as("MySQL database should survive round-trip")
                .isEqualTo(mysqlDatabase);
        
        assertThat(roundTripped.getDatabase().getMysql().getUsername())
                .as("MySQL username should survive round-trip")
                .isEqualTo(mysqlUsername);
        
        assertThat(roundTripped.getDatabase().getMysql().getPoolSize())
                .as("MySQL pool size should survive round-trip")
                .isEqualTo(poolSize);
        
        assertThat(roundTripped.getDatabase().getRedis().isEnabled())
                .as("Redis enabled should survive round-trip")
                .isEqualTo(redisEnabled);
        
        assertThat(roundTripped.getDatabase().getRedis().getPort())
                .as("Redis port should survive round-trip")
                .isEqualTo(redisPort);
    }

    @Provide
    Arbitrary<String> databaseTypes() {
        return Arbitraries.of("mysql", "redis", "memory");
    }

    @Provide
    Arbitrary<String> validIdentifiers() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(15);
    }

    /**
     * Property 16 (continued): Security configuration round-trip.
     * 
     * **Validates: Requirements 20.1-20.6**
     */
    @Property(tries = 100)
    void securityConfigRoundTrip(
            @ForAll @Size(min = 0, max = 3) List<@From("validIpAddresses") String> allowedIps,
            @ForAll @IntRange(min = 60, max = 3600) int ipBanDuration
    ) throws Exception {
        // Create config with security settings
        NovaLinkConfig original = NovaLinkConfig.createDefault();
        original.getSecurity().setAllowedIps(new ArrayList<>(allowedIps));
        original.getSecurity().setIpBanDuration(ipBanDuration);
        
        // Round-trip through file
        NovaLinkConfig roundTripped = roundTripConfig(original);
        
        // PROPERTY: Security config should be equivalent after round-trip
        assertThat(roundTripped.getSecurity().getAllowedIps())
                .as("Allowed IPs should survive round-trip")
                .containsExactlyElementsOf(allowedIps);
        
        assertThat(roundTripped.getSecurity().getIpBanDuration())
                .as("IP ban duration should survive round-trip")
                .isEqualTo(ipBanDuration);
    }

    @Provide
    Arbitrary<String> validIpAddresses() {
        return Arbitraries.of("127.0.0.1", "192.168.1.0/24", "10.0.0.1", "172.16.0.1");
    }

    /**
     * Property 16 (continued): Global channels configuration round-trip.
     * 
     * **Validates: Requirements 20.1-20.6**
     */
    @Property(tries = 100)
    void globalChannelsConfigRoundTrip(
            @ForAll("validIdentifiers") String channelId,
            @ForAll("validDisplayNames") String displayName,
            @ForAll("validPermissions") String permission,
            @ForAll @IntRange(min = 1, max = 10000) int maxCapacity
    ) throws Exception {
        // Create config with global channel
        NovaLinkConfig original = NovaLinkConfig.createDefault();
        original.getGlobalChannels().clear();
        
        GlobalChannelConfig channel = new GlobalChannelConfig();
        channel.setDisplayName(displayName);
        channel.setPermission(permission);
        channel.setMaxCapacity(maxCapacity);
        original.getGlobalChannels().put(channelId, channel);
        
        // Round-trip through file
        NovaLinkConfig roundTripped = roundTripConfig(original);
        
        // PROPERTY: Global channel should be equivalent after round-trip
        assertThat(roundTripped.getGlobalChannels())
                .as("Global channels map should contain the channel")
                .containsKey(channelId);
        
        GlobalChannelConfig roundTrippedChannel = roundTripped.getGlobalChannels().get(channelId);
        
        assertThat(roundTrippedChannel.getDisplayName())
                .as("Display name should survive round-trip")
                .isEqualTo(displayName);
        
        assertThat(roundTrippedChannel.getPermission())
                .as("Permission should survive round-trip")
                .isEqualTo(permission);
        
        assertThat(roundTrippedChannel.getMaxCapacity())
                .as("Max capacity should survive round-trip")
                .isEqualTo(maxCapacity);
    }

    @Provide
    Arbitrary<String> validDisplayNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(2)
                .ofMaxLength(20);
    }

    @Provide
    Arbitrary<String> validPermissions() {
        return Arbitraries.of(
                "novachat.channel.global",
                "novachat.channel.local",
                "novachat.admin",
                "novachat.bypass.world"
        );
    }

    /**
     * Property 16 (continued): Templates configuration round-trip.
     * 
     * **Validates: Requirements 20.1-20.6**
     */
    @Property(tries = 100)
    void templatesConfigRoundTrip(
            @ForAll("validIdentifiers") String templateId,
            @ForAll("validDisplayNames") String displayName,
            @ForAll("channelScopes") String scope,
            @ForAll @IntRange(min = 1, max = 1000) int maxCapacity
    ) throws Exception {
        // Create config with template
        NovaLinkConfig original = NovaLinkConfig.createDefault();
        original.getTemplates().clear();
        
        ChannelTemplateConfig template = new ChannelTemplateConfig();
        template.setDisplayName(displayName);
        template.setScope(scope);
        template.setMaxCapacity(maxCapacity);
        original.getTemplates().put(templateId, template);
        
        // Round-trip through file
        NovaLinkConfig roundTripped = roundTripConfig(original);
        
        // PROPERTY: Template should be equivalent after round-trip
        assertThat(roundTripped.getTemplates())
                .as("Templates map should contain the template")
                .containsKey(templateId);
        
        ChannelTemplateConfig roundTrippedTemplate = roundTripped.getTemplates().get(templateId);
        
        assertThat(roundTrippedTemplate.getDisplayName())
                .as("Display name should survive round-trip")
                .isEqualTo(displayName);
        
        assertThat(roundTrippedTemplate.getScope())
                .as("Scope should survive round-trip")
                .isEqualTo(scope);
        
        assertThat(roundTrippedTemplate.getMaxCapacity())
                .as("Max capacity should survive round-trip")
                .isEqualTo(maxCapacity);
    }

    @Provide
    Arbitrary<String> channelScopes() {
        return Arbitraries.of("GLOBAL", "SERVER", "PRIVATE");
    }

    /**
     * Property 16 (continued): Client configuration round-trip.
     * 
     * **Validates: Requirements 20.1-20.6**
     */
    @Property(tries = 100)
    void clientConfigRoundTrip(
            @ForAll("validIdentifiers") String username,
            @ForAll("validIdentifiers") String password,
            @ForAll("validDisplayNames") String displayName,
            @ForAll("validIdentifiers") String channelId,
            @ForAll("validDisplayNames") String channelDisplayName
    ) throws Exception {
        // Create config with client
        NovaLinkConfig original = NovaLinkConfig.createDefault();
        original.getClients().clear();
        
        ClientConfig client = new ClientConfig();
        client.setUsername(username);
        client.setPassword(password);
        client.setDisplayName(displayName);
        
        ServerChannelConfig channel = new ServerChannelConfig();
        channel.setDisplayName(channelDisplayName);
        channel.setScope("SERVER");
        client.getChannels().put(channelId, channel);
        
        original.getClients().add(client);
        
        // Round-trip through file
        NovaLinkConfig roundTripped = roundTripConfig(original);
        
        // PROPERTY: Client should be equivalent after round-trip
        assertThat(roundTripped.getClients())
                .as("Clients list should have one client")
                .hasSize(1);
        
        ClientConfig roundTrippedClient = roundTripped.getClients().get(0);
        
        assertThat(roundTrippedClient.getUsername())
                .as("Username should survive round-trip")
                .isEqualTo(username);
        
        assertThat(roundTrippedClient.getPassword())
                .as("Password should survive round-trip")
                .isEqualTo(password);
        
        assertThat(roundTrippedClient.getDisplayName())
                .as("Display name should survive round-trip")
                .isEqualTo(displayName);
        
        assertThat(roundTrippedClient.getChannels())
                .as("Channels map should contain the channel")
                .containsKey(channelId);
        
        ServerChannelConfig roundTrippedChannel = roundTrippedClient.getChannels().get(channelId);
        
        assertThat(roundTrippedChannel.getDisplayName())
                .as("Channel display name should survive round-trip")
                .isEqualTo(channelDisplayName);
    }

    /**
     * Property 16 (continued): Debug flag round-trip.
     * 
     * **Validates: Requirements 20.1-20.6**
     */
    @Property(tries = 100)
    void debugFlagRoundTrip(@ForAll boolean debug) throws Exception {
        // Create config with debug flag
        NovaLinkConfig original = NovaLinkConfig.createDefault();
        original.setDebug(debug);
        
        // Round-trip through file
        NovaLinkConfig roundTripped = roundTripConfig(original);
        
        // PROPERTY: Debug flag should be equivalent after round-trip
        assertThat(roundTripped.isDebug())
                .as("Debug flag should survive round-trip")
                .isEqualTo(debug);
    }

    /**
     * Property 16 (continued): Super admin credentials round-trip.
     * 
     * **Validates: Requirements 20.1-20.6**
     */
    @Property(tries = 100)
    void superAdminRoundTrip(
            @ForAll("validPasswordHashes") String passwordHash
    ) throws Exception {
        UUID uuid = UUID.randomUUID();
        
        // Create config with super admin
        NovaLinkConfig original = NovaLinkConfig.createDefault();
        original.getSuperAdmins().clear();
        original.getSuperAdmins().add(new SuperAdminCredentials(uuid, passwordHash));
        
        // Round-trip through file
        NovaLinkConfig roundTripped = roundTripConfig(original);
        
        // PROPERTY: Super admin should be equivalent after round-trip
        assertThat(roundTripped.getSuperAdmins())
                .as("Super admins list should have one admin")
                .hasSize(1);
        
        SuperAdminCredentials roundTrippedAdmin = roundTripped.getSuperAdmins().get(0);
        
        assertThat(roundTrippedAdmin.getUuid())
                .as("UUID should survive round-trip")
                .isEqualTo(uuid);
        
        assertThat(roundTrippedAdmin.getPasswordHash())
                .as("Password hash should survive round-trip")
                .isEqualTo(passwordHash);
    }

    @Provide
    Arbitrary<String> validPasswordHashes() {
        // Generate hex strings that look like SHA-256 hashes
        return Arbitraries.strings()
                .withCharRange('a', 'f')
                .withCharRange('0', '9')
                .ofMinLength(32)
                .ofMaxLength(64);
    }

    /**
     * Property 16 (continued): Template with allowed_worlds round-trip.
     * 
     * **Validates: Requirements 20.1-20.6**
     */
    @Property(tries = 100)
    void templateWithAllowedWorldsRoundTrip(
            @ForAll("validIdentifiers") String templateId,
            @ForAll @Size(min = 1, max = 3) List<@From("validIdentifiers") String> allowedWorlds
    ) throws Exception {
        // Create config with template having allowed_worlds
        NovaLinkConfig original = NovaLinkConfig.createDefault();
        original.getTemplates().clear();
        
        ChannelTemplateConfig template = new ChannelTemplateConfig();
        template.setDisplayName("TestTemplate");
        template.setScope("SERVER");
        template.setAllowedWorlds(new ArrayList<>(allowedWorlds));
        original.getTemplates().put(templateId, template);
        
        // Round-trip through file
        NovaLinkConfig roundTripped = roundTripConfig(original);
        
        // PROPERTY: Template with allowed_worlds should be equivalent after round-trip
        assertThat(roundTripped.getTemplates())
                .containsKey(templateId);
        
        ChannelTemplateConfig roundTrippedTemplate = roundTripped.getTemplates().get(templateId);
        
        assertThat(roundTrippedTemplate.getAllowedWorlds())
                .as("Allowed worlds should survive round-trip")
                .containsExactlyElementsOf(allowedWorlds);
    }

    /**
     * Property 16 (continued): Server channel with use_template round-trip.
     * 
     * **Validates: Requirements 20.1-20.6**
     */
    @Property(tries = 100)
    void serverChannelWithTemplateRoundTrip(
            @ForAll("validIdentifiers") String channelId,
            @ForAll("validIdentifiers") String templateId,
            @ForAll("validDisplayNames") String overrideDisplayName
    ) throws Exception {
        // Create config with client having channel that uses template
        NovaLinkConfig original = NovaLinkConfig.createDefault();
        original.getClients().clear();
        
        ClientConfig client = new ClientConfig();
        client.setUsername("testclient");
        client.setPassword("testpassword");
        client.setDisplayName("TestClient");
        
        ServerChannelConfig channel = new ServerChannelConfig();
        channel.setUseTemplate(templateId);
        channel.setDisplayName(overrideDisplayName);
        client.getChannels().put(channelId, channel);
        
        original.getClients().add(client);
        
        // Round-trip through file
        NovaLinkConfig roundTripped = roundTripConfig(original);
        
        // PROPERTY: Channel with use_template should be equivalent after round-trip
        assertThat(roundTripped.getClients()).hasSize(1);
        
        ServerChannelConfig roundTrippedChannel = roundTripped.getClients().get(0)
                .getChannels().get(channelId);
        
        assertThat(roundTrippedChannel.getUseTemplate())
                .as("use_template should survive round-trip")
                .isEqualTo(templateId);
        
        assertThat(roundTrippedChannel.getDisplayName())
                .as("Override display name should survive round-trip")
                .isEqualTo(overrideDisplayName);
    }

    // Helper methods

    private NovaLinkConfig roundTripConfig(NovaLinkConfig original) throws Exception {
        // Create a temporary file for the round-trip
        Path tempFile = Files.createTempFile("novalink-test-", ".yml");
        try {
            ConfigLoader loader = new ConfigLoader(tempFile);
            
            // Set the config and save
            setConfigField(loader, original);
            loader.save();
            
            // Create a new loader and load
            ConfigLoader loader2 = new ConfigLoader(tempFile);
            return loader2.load();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private void setConfigField(ConfigLoader loader, NovaLinkConfig config) throws Exception {
        java.lang.reflect.Field field = ConfigLoader.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(loader, config);
    }
}
